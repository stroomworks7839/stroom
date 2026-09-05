/*
 * Copyright 2016-2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.Instrument;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.compile.CompiledMatch;
import stroom.shapeshifter.engine.compile.CompiledOp;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.compile.CompiledRef;
import stroom.shapeshifter.engine.compile.CompiledTemplate;
import stroom.shapeshifter.engine.config.CaptureBinding;
import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.RunMode;
import stroom.shapeshifter.engine.function.Services;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.text.RegexEncodings;
import stroom.shapeshifter.engine.text.Transcode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The runtime: reads the input, drives the templates, writes the output.
 *
 * <p>Input arrives through a <b>sliding window</b> of the configured buffer size (E13, DS3's own
 * shape): records consume from the window's front and it refills behind them, so a
 * record is never failed for straddling where a read happened to end. The bounds are the
 * contract — memory is capped by the buffer size, and a single match must fit the window's
 * capacity or it cannot be made — which makes failures depend on record size, never on stream
 * position.
 *
 * <p>Within a buffer the shape is simple and recursive. The document template writes its prologue,
 * hands the buffer to the templates of its mode, and writes its epilogue at the end of the stream.
 * A level's templates are dispatched as <b>iterated ordered choice</b> — {@code (A|B|C)*}, DS3's
 * own model (D34): each pass, the first template that matches wins one match, and the choice
 * re-opens from the first template. A match's body can hand a captured group down to another
 * level, which is how a record becomes fields and a field becomes parts.
 */
public final class Executor implements Level.BodyRunner {

    private final CompiledProject compiled;
    private final OutputSink output;
    private final Instrument instrument;
    private final List<Message> messages = new ArrayList<>();
    /** The functions bound to this run, and what they may reach (design 26). */
    private final FunctionRuntime functions;

    /** The dispatcher of one level against one region, which hands winning bodies back here. */
    private final Level level;
    private final VarRegistry vars = new VarRegistry();

    /**
     * The built key indexes, in their own namespace (design/16 §8) — a key and a sequence may
     * share a name because nothing at a use site can confuse the two. Per run, like the
     * registry: an index outliving its stream would answer this one with the last one's
     * records.
     *
     * <p><b>Unscoped, where a sequence is scoped</b>, which is deliberate and is the shape
     * XSLT has: a key is an index over data rather than a binding, and every realistic
     * configuration builds one and uses it at the same level. The sharp edge that buys,
     * named rather than discovered (phase 5 audit): a key holds <i>store indices</i>, so one
     * built inside a scope that later pops still answers, with positions into a store that
     * may since have been cleared. Index staleness is a property of every index-carrying
     * sequence here, not of keys — scoping is what usually hides it, and a key steps outside
     * that. No case needs a key to outlive its sequence, so nothing is built to prevent it.
     */
    private final Map<String, Map<String, Filed>> keyIndexes = new HashMap<>();

    /**
     * The arithmetic sites that have already drawn a strict_values warning this run — once
     * per instruction site, because once per record on a million-record input is not a
     * diagnostic, it is a flood (design/17 §10).
     */
    private final Set<CompiledOp.Transform> warnedNumeric =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Whether the root reads the input in pieces whose counters restart (design/16 §10).
     *
     * <p>A {@code classify} or {@code any} root is dispatched chunk-at-a-time by repeated
     * {@code Level.dispatch} calls, so template counters reset and capture stores clear <b>per
     * chunk</b>. An accumulation there would summarise the last chunk while presenting itself
     * as a summary of the input — a wrong answer wearing the shape of a right one, which is
     * the failure mode this whole section exists to refuse. A whole-buffer run takes the same
     * code path with exactly one chunk, and is therefore fine.
     */
    private boolean chunkedRoot;

    /**
     * The encoding in force: what the configuration declared, or UTF-8 once a UTF-8 byte-order
     * mark has confirmed it. A mark naming anything else refuses the run ({@link #applyMark}).
     */
    private Encoding encoding;

    private Executor(final CompiledProject compiled,
                     final OutputSink sink,
                     final Instrument instrument,
                     final RunMode mode,
                     final Services services) {
        this.compiled = compiled;
        this.output = sink;
        this.instrument = instrument;
        this.encoding = compiled.encoding();
        this.messages.addAll(compiled.warnings());
        this.functions = new FunctionRuntime(compiled.functions(), mode, services, messages);
        this.level = new Level(compiled, instrument, messages, vars, functions, this);
    }

    /**
     * Run a compiled configuration over an input.
     *
     * @param wholeBuffer read the input as a single buffer rather than through the window. The
     *                    progressive matches need it — an absolute seek is meaningless over a
     *                    window — and a whole buffer has no edge for a match to run into
     * @return everything the engine had to say, in the order it said it
     */
    public static List<Message> run(final CompiledProject compiled,
                                    final InputStream input,
                                    final OutputSink sink,
                                    final Instrument instrument,
                                    final boolean wholeBuffer) {
        return run(compiled, input, sink, instrument, wholeBuffer, RunMode.NORMAL, Services.NONE);
    }

    public static List<Message> run(final CompiledProject compiled,
                                    final InputStream input,
                                    final OutputSink sink,
                                    final Instrument instrument,
                                    final boolean wholeBuffer,
                                    final RunMode mode,
                                    final Services services) {
        // Design 19 phase 6: a transcode-family source becomes UTF-8 bytes before the
        // window machinery reads it; report by default, replace under ignore_errors.
        final InputStream source = compiled.transcodeFrom() != null
                ? Transcode.wrap(input, compiled.transcodeFrom().charset(),
                        compiled.project().source().ignoreErrors())
                : input;
        return new Executor(compiled, sink, instrument, mode, services).execute(source, wholeBuffer);
    }

    // -----------------------------------------------------------------------------------
    // The stream
    // -----------------------------------------------------------------------------------

    private List<Message> execute(final InputStream input, final boolean wholeBuffer) {
        try {
            functions.bind();
            run(input, wholeBuffer);
        } catch (final AbortRun ignored) {
            // The fatal message is the last thing the run has to say.
        } catch (final UncheckedIOException e) {
            // The input failed mid-read. Everything already said still stands, so the failure
            // joins the messages rather than throwing them away.
            messages.add(new Message(Severity.FATAL, "Reading the input failed: " + e.getCause()));
        } catch (final OutputSink.StructureException e) {
            // A write the sink could not place — text at document level on the event sink is
            // the case — rather than a structural call, which structure() has already named.
            // The same rule as above: a misplaced write is the run's last message, not an
            // exception through the caller.
            messages.add(new Message(Severity.FATAL, "Output structure: " + e.getMessage()));
        }
        return List.copyOf(messages);
    }

    private void run(final InputStream input, final boolean wholeBuffer) {
        final CompiledTemplate source = compiled.templates().stream()
                .filter(t -> t.match() instanceof CompiledMatch.Source)
                .findFirst()
                .orElse(null);

        // The document template's body is split at its apply-templates: what comes before is
        // written once at the start, what comes after once at the end, and the apply-templates
        // itself is the loop over the input. Everything the loop dispatches to is the templates
        // of the mode it names.
        final ApplyDirective streamDirective = source == null ? null : applyDirective(source.template());
        final String streamMode = streamDirective == null ? null : streamDirective.mode();
        final Dispatch rootDispatch = Dispatch.effective(
                streamDirective == null ? null : streamDirective.dispatch(), compiled.project());
        final List<CompiledTemplate> roots = compiled.templates().stream()
                .filter(t -> !(t.match() instanceof CompiledMatch.Source))
                .filter(t -> Objects.equals(t.template().mode(), streamMode))
                .toList();

        // The root level's gate is the configuration's own ignoreErrors — DS3's flag on the
        // dataSplitter element itself — or the document template's directive saying so.
        final boolean rootIgnoreErrors = compiled.project().source().ignoreErrors()
                || (streamDirective != null && streamDirective.ignoreErrors());

        final MatchResult nothing = MatchResult.empty();
        final RootSplit split = source == null ? RootSplit.NONE : RootSplit.of(source.body());

        for (final Template template : compiled.project().templates()) {
            for (final CaptureBinding capture : template.captures()) {
                vars.register(capture.name());
            }
        }

        // What comes before the apply-templates, with any element it sits inside opened on the
        // way down (design 21 phase 2b: `element records { apply-templates }` is the shape the
        // migration takes, and the sink's deferred start tag is what makes opening-then-looping
        // serialise as if the body had run in one piece).
        for (int i = 0; i < split.prologues.size(); i++) {
            body(split.prologues.get(i), nothing, 0, new byte[0], output, 0L, rootIgnoreErrors, 0, encoding);
            if (i < split.opened.size()) {
                final CompiledOp.Element element = split.opened.get(i);
                structure(() -> output.startElement(element.name(), element.namespace(), element.omitIfEmpty()),
                        "element '" + element.name() + "'");
            }
        }

        final int bufferSize = wholeBuffer
                ? Integer.MAX_VALUE
                : Math.max(1, compiled.project().source().bufferSize());
        chunkedRoot = !wholeBuffer
                      && (rootDispatch == Dispatch.CLASSIFY || rootDispatch == Dispatch.ANY);
        if (wholeBuffer || rootDispatch == Dispatch.CLASSIFY || rootDispatch == Dispatch.ANY) {
            // Whole-buffer inputs are addressed in one piece, and the non-consuming root
            // dispatches work window-at-a-time; neither slides.
            boolean first = true;
            long read = 0;
            for (byte[] chunk = InputWindow.read(input, bufferSize);
                 chunk != null;
                 chunk = InputWindow.read(input, bufferSize)) {
                int from = 0;
                if (first) {
                    first = false;
                    final Encoding.ByteOrderMark mark = InputWindow.byteOrderMark(chunk, chunk.length);
                    if (mark != null) {
                        applyMark(mark);
                        from = mark.length();
                        // The mark is part of the input: absolute offsets count its bytes.
                        read = from;
                    }
                }
                if (from >= chunk.length) {
                    continue;
                }
                level.dispatch(roots, chunk, from, chunk.length, output, read,
                        rootIgnoreErrors, 0, rootDispatch, encoding);
                read += chunk.length - from;
            }
        } else {
            final InputWindow window = InputWindow.open(input, bufferSize);
            if (window.mark() != null) {
                applyMark(window.mark());
            }
            level.stream(roots, window, bufferSize, output, rootIgnoreErrors, rootDispatch, encoding);
        }

        // And what comes after it, closing the opened elements on the way back up.
        for (int i = split.tails.size() - 1; i >= 0; i--) {
            body(split.tails.get(i), nothing, 0, new byte[0], output, 0L, rootIgnoreErrors, 0, encoding);
            if (i > 0) {
                final CompiledOp.Element element = split.opened.get(i - 1);
                structure(output::endElement, "element '" + element.name() + "'");
            }
        }
    }

    /**
     * What a byte-order mark at the front of the input means for the run. A UTF-8 mark is
     * skipped and confirms the encoding. A UTF-16 mark names an encoding the regex library has
     * no lowering for: such a source is transcoded whole to UTF-8 before the window ever sees
     * it (design 19 phase 6), so a mark reaching the window means the source was declared as
     * something else, and the run is refused by name rather than matching UTF-8 machines
     * against UTF-16 bytes.
     */
    private void applyMark(final Encoding.ByteOrderMark mark) {
        if (RegexEncodings.needsTranscode(mark.encoding())) {
            messages.add(new Message(Severity.FATAL, "The input begins with a " + mark.encoding().label()
                    + " byte-order mark, but the source "
                    + (encoding == Encoding.AUTO ? "declares no encoding" : "is declared " + encoding.label())
                    + ": declare " + mark.encoding().label()
                    + " on the source so the stream is transcoded whole"));
            throw new AbortRun();
        }
        encoding = mark.encoding();
    }

    /**
     * The document template's first {@code apply-templates}, looked for at the top of its body
     * and inside any {@code element} that encloses it — the same descent {@link RootSplit} makes.
     */
    private static ApplyDirective applyDirective(final Template template) {
        return applyDirective(template.body());
    }

    private static ApplyDirective applyDirective(final List<OutputNode> body) {
        for (final OutputNode node : body) {
            if (node instanceof OutputNode.ApplyTemplates apply) {
                return apply.directive();
            }
            if (node instanceof OutputNode.Element element) {
                final ApplyDirective inside = applyDirective(element.body());
                if (inside != null) {
                    return inside;
                }
            }
        }
        return null;
    }

    /**
     * The document template's body, split around its apply-templates.
     *
     * <p>Level 0 is the body itself; each element enclosing the apply adds a level. Running the
     * prologues in order with each level's element opened after its prologue, then the loop,
     * then the tails in reverse with each element closed after its tail, is the body run in one
     * piece with the loop where the apply-templates was. A body with no apply-templates is all
     * prologue.
     */
    private record RootSplit(List<List<CompiledOp>> prologues,
                             List<CompiledOp.Element> opened,
                             List<List<CompiledOp>> tails) {

        private static final RootSplit NONE = new RootSplit(List.of(), List.of(), List.of());

        static RootSplit of(final List<CompiledOp> body) {
            final List<List<CompiledOp>> prologues = new ArrayList<>();
            final List<CompiledOp.Element> opened = new ArrayList<>();
            final List<List<CompiledOp>> tails = new ArrayList<>();
            List<CompiledOp> level = body;
            while (true) {
                final int at = indexOfApplyOrEnclosingElement(level);
                if (at < 0) {
                    prologues.add(level);
                    tails.add(List.of());
                    break;
                }
                prologues.add(level.subList(0, at));
                tails.add(level.subList(at + 1, level.size()));
                if (level.get(at) instanceof CompiledOp.Element element) {
                    opened.add(element);
                    level = element.body();
                } else {
                    break;
                }
            }
            return new RootSplit(prologues, opened, tails);
        }

        private static int indexOfApplyOrEnclosingElement(final List<CompiledOp> body) {
            for (int i = 0; i < body.size(); i++) {
                final CompiledOp op = body.get(i);
                if (op instanceof CompiledOp.Apply
                    || (op instanceof CompiledOp.Element element && containsApply(element.body()))) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean containsApply(final List<CompiledOp> body) {
            for (final CompiledOp op : body) {
                if (op instanceof CompiledOp.Apply
                    || (op instanceof CompiledOp.Element element && containsApply(element.body()))) {
                    return true;
                }
            }
            return false;
        }
    }

    // -----------------------------------------------------------------------------------
    // The body
    // -----------------------------------------------------------------------------------

    /** The body interpreter, which a level hands a winning match's body to (the callback of {@link Level}). */
    @Override
    public void body(final List<CompiledOp> ops,
                      final MatchResult match,
                      final int matchCount,
                      final byte[] content,
                      final OutputSink sink,
                      final long inputBase,
                      final boolean ignoreErrors,
                      final int depth,
                      final Encoding contentEncoding) {
        for (final CompiledOp op : ops) {
            switch (op) {
                case CompiledOp.Text text -> sink.write(text.bytes());
                case CompiledOp.ValueOf valueOf ->
                        CompiledRefs.write(valueOf.ref(), match, matchCount, vars, contentEncoding, sink);
                case CompiledOp.Apply apply -> {
                    // A directive naming a template is the recursive form, which the compiler
                    // has already inlined; running it here would recurse for ever.
                    if (apply.directive().templateRef() == null) {
                        apply(apply, match, matchCount, content, sink, inputBase, ignoreErrors, depth, contentEncoding);
                    }
                }
                case CompiledOp.If value -> {
                    if (test(value.test(), match, matchCount, contentEncoding)) {
                        body(value.then(), match, matchCount, content, sink,
                                inputBase, ignoreErrors, depth, contentEncoding);
                    }
                }
                case CompiledOp.Choose value -> {
                    boolean taken = false;
                    for (final CompiledOp.When branch : value.when()) {
                        if (test(branch.test(), match, matchCount, contentEncoding)) {
                            body(branch.body(), match, matchCount, content, sink,
                                    inputBase, ignoreErrors, depth, contentEncoding);
                            taken = true;
                            break;
                        }
                    }
                    if (!taken) {
                        body(value.otherwise(), match, matchCount, content, sink,
                                inputBase, ignoreErrors, depth, contentEncoding);
                    }
                }
                case CompiledOp.Switch value -> {
                    final String selected = textOf(value.select(), match, matchCount, contentEncoding);
                    boolean taken = false;
                    for (final CompiledOp.Case switchCase : value.cases()) {
                        if (switchCase.value().equals(selected)) {
                            body(switchCase.body(), match, matchCount, content, sink,
                                    inputBase, ignoreErrors, depth, contentEncoding);
                            taken = true;
                            break;
                        }
                    }
                    if (!taken) {
                        body(value.defaultBody(), match, matchCount, content, sink,
                                inputBase, ignoreErrors, depth, contentEncoding);
                    }
                }
                case CompiledOp.Variable value ->
                        variable(value, match, matchCount, content, inputBase, ignoreErrors, depth, contentEncoding);
                case CompiledOp.Element value -> {
                    structure(() -> sink.startElement(value.name(), value.namespace(), value.omitIfEmpty()),
                            "element '" + value.name() + "'");
                    body(value.body(), match, matchCount, content, sink,
                            inputBase, ignoreErrors, depth, contentEncoding);
                    structure(sink::endElement, "element '" + value.name() + "'");
                }
                case CompiledOp.Attribute value -> {
                    structure(() -> sink.startAttribute(value.name(), value.omitIfEmpty()),
                            "attribute '" + value.name() + "'");
                    body(value.body(), match, matchCount, content, sink,
                            inputBase, ignoreErrors, depth, contentEncoding);
                    structure(sink::endAttribute, "attribute '" + value.name() + "'");
                }
                case CompiledOp.Namespace value ->
                        structure(() -> sink.namespace(value.prefix(), value.uri()),
                                "namespace '" + value.prefix() + "'");
                case CompiledOp.Call value ->
                        call(value, match, matchCount, content, sink, inputBase, ignoreErrors, depth, contentEncoding);
                case CompiledOp.ValueMap value -> {
                    final String selected = textOf(value.select(), match, matchCount, contentEncoding);
                    String mapped = null;
                    for (final OutputNode.Entry entry : value.entries()) {
                        if (entry.from().equals(selected)) {
                            mapped = entry.to();
                            break;
                        }
                    }
                    if (mapped == null) {
                        mapped = value.defaultValue();
                    }
                    emit(TypedValue.of(mapped == null ? "" : mapped), value.name(), matchCount, sink);
                }
                case CompiledOp.Transform value ->
                        transform(value, match, matchCount, sink, contentEncoding);
                case CompiledOp.CallFunction value ->
                        call(value, match, matchCount, sink, inputBase, contentEncoding);
                case CompiledOp.Sequence value -> {
                    // Declared here, emptied here: an accumulation that outlived its previous
                    // run would carry the last stream's values into this one.
                    vars.shadow(value.name());
                    vars.store(value.name()).clear();
                }
                case CompiledOp.Append value -> {
                    final TypedValue appended = CompiledRefs.resolveValue(
                            value.select(), match, matchCount, vars, contentEncoding);
                    if (appended != null) {
                        guardAccumulation(value.name());
                        // Absent appends nothing rather than a hole: in a dense sequence an
                        // index is a position, so a gap would mean nothing at all.
                        final Store store = vars.store(value.name());
                        final int at = Math.max(1, store.lastIndex() + 1);
                        guardSequenceSize(value.name(), at);
                        store.set(at, appended);
                    }
                }
                case CompiledOp.Fold value -> emit(fold(value), value.name(), matchCount, sink);
                case CompiledOp.DistinctValues value -> distinct(value);
                case CompiledOp.Tokenize value -> {
                    final TypedValue input = CompiledRefs.resolveValue(
                            value.select(), match, matchCount, vars, contentEncoding);
                    if (value.name() == null) {
                        if (input != null) {
                            // Written straight out, it keeps the joined rendering it always had.
                            sink.write(Transforms.tokenize(List.of(input), value.delimiter()).asBytes());
                        }
                    } else {
                        // Nothing to split is the empty sequence, which a walk runs over zero
                        // times. Leaving the name alone would walk the last record's pieces.
                        bindDense(value.name(), input == null
                                ? List.of()
                                : Transforms.split(input, value.delimiter()));
                    }
                }
                case CompiledOp.Key value -> {
                    // Built where it is written, so the cost is paid somewhere visible.
                    keyIndexes.put(value.name(), index(value.select(), value.groupBy(),
                            match, matchCount, contentEncoding));
                }
                case CompiledOp.KeyGet value -> {
                    final TypedValue wanted = CompiledRefs.resolveValue(
                            value.select(), match, matchCount, vars, contentEncoding);
                    final Map<String, Filed> index = keyIndexes.getOrDefault(value.key(), Map.of());
                    // A value with no entry binds an empty sequence, which a walk runs over
                    // zero times — the same non-answer XSLT's key() gives, not an error.
                    // An absent lookup value finds the entries that had no key — the same
                    // symmetry grouping uses, where absence is a group rather than an
                    // exclusion (phase 4). XSLT would return empty for key('k', ()); this
                    // engine treats "no value" as a value one can ask about, consistently.
                    final Filed filed = index.get(wanted == null ? null : wanted.asString());
                    final List<Integer> found = filed == null ? List.of() : filed.members();
                    bindDense(value.name(), found.stream()
                            .map(entry -> (TypedValue) new TypedValue.Int(entry))
                            .toList());
                }
                case CompiledOp.ForEachGroup value ->
                        forEachGroup(value, match, matchCount, content, sink,
                                inputBase, ignoreErrors, depth, contentEncoding);
                case CompiledOp.ForEach value ->
                        forEach(value, match, matchCount, content, sink,
                                inputBase, ignoreErrors, depth, contentEncoding);
                case CompiledOp.ParseDate value -> {
                    final TypedValue input = CompiledRefs.resolveValue(
                            value.select(), match, matchCount, vars, contentEncoding);
                    TypedValue result = null;
                    if (input != null) {
                        // The reference is a date read like any other (design/17 §9.2): a
                        // captured field today, D10's context seam tomorrow. Absent when the
                        // pattern needs it means an absent result, never a guessed year.
                        final TypedValue reference = value.reference() == null
                                ? null
                                : Comparisons.cast(CompiledRefs.resolveValue(
                                        value.reference(), match, matchCount, vars, contentEncoding),
                                        Cast.DATE);
                        result = Dates.parse(value.parser(), input.asString(),
                                (TypedValue.Instant) reference);
                    }
                    emit(result, value.name(), matchCount, sink);
                }
                case CompiledOp.EmitError value -> {
                    final String text = CompiledRefs.resolveText(
                            value.message(), match, matchCount, vars, contentEncoding);
                    messages.add(new Message(value.severity(), text == null ? "" : text));
                    if (value.severity() == Severity.FATAL) {
                        // The message is recorded; the run ends here (D36).
                        throw new AbortRun();
                    }
                }
            }
        }
    }

    private boolean test(final Condition condition,
                         final MatchResult match,
                         final int matchCount,
                         final Encoding contentEncoding) {
        return Conditions.evaluate(condition, match, matchCount, vars, contentEncoding, compiled.patterns());
    }

    private String textOf(final CompiledRef ref, final MatchResult match, final int matchCount,
                          final Encoding contentEncoding) {
        final String resolved = CompiledRefs.resolveText(ref, match, matchCount, vars, contentEncoding);
        return resolved == null ? "" : resolved;
    }

    /**
     * Call a function (design 26): the arguments resolved in the body's scope, positions kept
     * and absence null, sequences handed over whole; the runtime makes the call and says what
     * a skipped, failed or erroring call means; the result is written or bound like any value.
     */
    private void call(final CompiledOp.CallFunction op,
                      final MatchResult match,
                      final int matchCount,
                      final OutputSink sink,
                      final long inputBase,
                      final Encoding contentEncoding) {
        final FunctionDefinition definition = op.definition();
        if (functions.skippedInPreview(definition)) {
            emit(null, op.name(), matchCount, sink);
            return;
        }
        final List<Kind> kinds = definition.signature().argKinds();
        final int written = op.select().size();
        final List<TypedValue> values = new ArrayList<>(written);
        final List<TypedValue> raw = new ArrayList<>(written);
        final List<List<TypedValue>> sequences = new ArrayList<>(written);
        for (int i = 0; i < written; i++) {
            final String store = op.sequences().get(i);
            if (store != null) {
                raw.add(null);
                values.add(null);
                sequences.add(entries(store));
                continue;
            }
            final TypedValue resolved = CompiledRefs.resolveValue(
                    op.select().get(i), match, matchCount, vars, contentEncoding);
            raw.add(resolved);
            values.add(resolved == null ? null : castTo(resolved, kinds.get(i)));
            sequences.add(null);
        }
        final TypedValue result = functions.invoke(definition.name(), new Arguments(values, raw, sequences),
                Level.locate(inputBase, match.matchStart()), match.advance() - match.matchStart());
        emit(result, op.name(), matchCount, sink);
    }

    /** A value read as a kind through the casting table (design 17 §3.1); null when it has no such reading. */
    private static TypedValue castTo(final TypedValue value, final Kind kind) {
        return switch (kind) {
            case ANY, SEQUENCE -> value;
            case STRING -> Comparisons.cast(value, Cast.STRING);
            case NUMBER -> Comparisons.cast(value, Cast.NUMBER);
            case INTEGER -> {
                final Long whole = value.asInteger();
                yield whole == null ? null : new TypedValue.Int(whole);
            }
            case BOOLEAN -> Comparisons.cast(value, Cast.BOOLEAN);
            case DATE -> Comparisons.cast(value, Cast.DATE);
        };
    }

    /**
     * Run a transform function and either write its result or bind it to a variable.
     *
     * <p>An input that resolves to nothing is dropped rather than passed along as an empty
     * string, so a function receiving two references and finding one absent sees one input, not
     * two of which one is blank. And a function returning nothing writes nothing — which is what
     * makes a join of no values disappear instead of leaving a stray separator.
     */
    private void transform(final CompiledOp.Transform op,
                           final MatchResult match,
                           final int matchCount,
                           final OutputSink sink,
                           final Encoding contentEncoding) {
        final List<CompiledRef> select = op.select();
        final String name = op.name();
        final Function<List<TypedValue>, TypedValue> function = op.function();
        final List<TypedValue> inputs = new ArrayList<>(select.size());
        for (final CompiledRef ref : select) {
            final TypedValue resolved = CompiledRefs.resolveValue(ref, match, matchCount, vars, contentEncoding);
            if (resolved != null) {
                inputs.add(resolved);
            }
        }
        if (op.numericKind() != null && compiled.project().source().strictValues()
            && !warnedNumeric.contains(op)) {
            // A present value with no numeric reading — a missing field is normal and stays
            // quiet; a value that is there and is not a number is the evidence strict_values
            // exists to surface (design/17 §10).
            for (final TypedValue input : inputs) {
                if (input.asNumber() == null) {
                    warnedNumeric.add(op);
                    messages.add(new Message(Severity.WARNING,
                            "strict_values: a non-numeric value reached " + op.numericKind()
                            + ": [" + preview(input) + "]"));
                    break;
                }
            }
        }
        emit(function.apply(inputs), name, matchCount, sink);
    }

    /** A short, printable slice of an offending value for the strict_values message. */
    private static String preview(final TypedValue value) {
        final String text = value.asString();
        return text.length() > 40 ? text.substring(0, 40) + "…" : text;
    }

    /**
     * Write a produced value, or bind it to a variable if the instruction named one.
     *
     * <p>Naming a variable binds it, always. An instruction with nothing to say writes nothing
     * — the "empty is absent" rule — but it must still <i>bind</i> absence, because a name left
     * untouched is a name still holding the previous record's answer, and a reference with no
     * index takes the latest there is. E19 named this as the residual for transform results and
     * said its own entry was the precedent if anything ever read one across records;
     * design/16's iteration made exactly that ordinary. The clear is at the match index, which
     * is how a capture that did not match already says the same thing.
     */
    private void emit(final TypedValue value, final String name, final int matchCount, final OutputSink sink) {
        if (name == null) {
            if (value != null) {
                sink.write(value.asBytes());
            }
        } else if (value == null) {
            vars.store(name).remove(matchCount);
        } else {
            // The typed value binds as itself — no re-encode, and the type survives to any
            // later typed read (design/17 §3.2).
            vars.store(name).set(matchCount, value);
        }
    }

    /**
     * Design/16 §10's chunked-root refusal, on the <b>write</b> rather than on every read
     * (phase 6 audit).
     *
     * <p>{@code append} is the only instruction whose purpose is to make a value outlive the
     * record that produced it, so it is the accumulation, and refusing it refuses every
     * configuration that deliberately accumulates under a root that reads in pieces. Guarding
     * reads as well looked thorough and was wrong: a sequence bound and walked inside one
     * record's body — a {@code tokenize} and a walk over its pieces — crosses no record
     * boundary and cannot be summarised wrongly, and was being refused fatally for a hazard
     * it did not have.
     *
     * <p>What this deliberately does not cover: reading a <b>capture</b> store under such a
     * root, which accumulates across records at the root level and is cleared per chunk.
     * That clearing predates this design; what is new is that a grouping can now read such a
     * store and present a per-chunk answer. No refusal catches it, because nothing at the
     * read site distinguishes a capture store from a per-record binding — it is named here
     * rather than left for someone to find.
     */
    private void guardAccumulation(final String name) {
        if (chunkedRoot) {
            messages.add(new Message(Severity.FATAL, "Appends to sequence '" + name + "' under "
                    + "a classify or any root: the input is read in pieces whose counters "
                    + "restart, so the accumulation would summarise only the last piece. Use "
                    + "an ordered root dispatch, or read the input whole."));
            throw new AbortRun();
        }
    }

    /** The size a sequence may not exceed, and the stop when it does. */
    private void guardSequenceSize(final String name, final int size) {
        final int limit = compiled.project().source().maxSequenceEntries();
        if (size > limit) {
            messages.add(new Message(Severity.FATAL, "Sequence '" + name + "' exceeded "
                    + "max_sequence_entries (" + limit + "). A truncated aggregate is a wrong "
                    + "answer rather than a partial one, so the run stops here. Raise the "
                    + "limit if the accumulation is genuinely this large."));
            throw new AbortRun();
        }
    }

    /** The populated entries of a named sequence, in ascending index order, or empty. */
    private List<TypedValue> entries(final String name) {
        final List<Store> stores = vars.get(name);
        if (stores == null || stores.isEmpty()) {
            return List.of();
        }
        final Store store = stores.getFirst();
        final List<TypedValue> values = new ArrayList<>(store.size());
        for (int i = 0; i < store.size(); i++) {
            final TypedValue value = store.get(i);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /** Bind values as a dense sequence, indexed from one — position, with no holes. */
    private void bindDense(final String name, final List<TypedValue> values) {
        guardSequenceSize(name, values.size());
        final Store store = vars.store(name);
        store.clear();
        for (int i = 0; i < values.size(); i++) {
            store.set(i + 1, values.get(i));
        }
    }

    /**
     * Fold a sequence to one value (design/16 §8).
     *
     * <p>The empty sequence answers as XPath does, which is not the same answer twice:
     * {@code sum(())} is zero and {@code avg(())} is empty. Zero is a real total of nothing;
     * a mean of nothing is not a number, and returning zero for it would be a number that
     * looks like an answer.
     */
    private TypedValue fold(final CompiledOp.Fold op) {
        final List<TypedValue> values = entries(op.select());
        return switch (op.kind()) {
            case COUNT -> new TypedValue.Int(values.size());
            case SUM -> values.isEmpty() ? new TypedValue.Int(0) : Transforms.add(values);
            case AVG -> {
                if (values.isEmpty()) {
                    yield null;
                }
                final TypedValue total = Transforms.add(values);
                final Double sum = total == null ? null : total.asNumber();
                yield sum == null ? null : new TypedValue.Real(sum / values.size());
            }
            case MIN, MAX -> extreme(values, op.as(), op.kind() == CompiledOp.FoldKind.MIN);
        };
    }

    /**
     * The smallest or largest entry under §8's ordering. An entry whose cast fails does not
     * participate — the same "this value did not participate" that reads false in a condition
     * and sorts last in an ordering — and if none participates the answer is absent.
     */
    private static TypedValue extreme(final List<TypedValue> values,
                                      final Cast as,
                                      final boolean smallest) {
        TypedValue best = null;
        for (final TypedValue value : values) {
            // Uncast, an ordering compares string forms: the one total reading (17 §8).
            final TypedValue candidate = Comparisons.cast(value, as == null ? Cast.STRING : as);
            if (candidate == null) {
                continue;
            }
            if (best == null) {
                best = candidate;
                continue;
            }
            final Integer order = Comparisons.compare(candidate, best);
            if (order != null && (smallest ? order < 0 : order > 0)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * File a sequence's entries by key, in order of first appearance — the one index both
     * grouping and {@code key} are built on (design/16 §6, §8). Keys resolve with
     * {@code __index} bound, so a key can name a parallel store: "these records, by their
     * category" is said by indexing positions rather than values.
     */
    private Map<String, Filed> index(final String select,
                                     final CompiledRef groupBy,
                                     final MatchResult match,
                                     final int matchCount,
                                     final Encoding contentEncoding) {
        final Map<String, Filed> members = new LinkedHashMap<>();
        final List<Store> stores = vars.get(select);
        if (stores == null || stores.isEmpty()) {
            return members;
        }
        final Store store = stores.getFirst();
        vars.push();
        vars.shadow(EngineVars.INDEX);
        for (int index = 0; index < store.size(); index++) {
            final TypedValue entry = store.get(index);
            if (entry == null) {
                continue;
            }
            vars.store(EngineVars.INDEX).set(1, new TypedValue.Int(index));
            final TypedValue key = groupBy == null
                    ? entry
                    : CompiledRefs.resolveValue(groupBy, match, matchCount, vars, contentEncoding);
            members.computeIfAbsent(key == null ? null : key.asString(),
                    ignored -> new Filed(key, new ArrayList<>())).members().add(index);
        }
        vars.pop();
        return members;
    }

    /**
     * One entry of an index: the key as it was read, and the store positions filed under it.
     * The key is kept as a value rather than as its identity string because a grouping binds
     * it to {@code __group_key}, where an author expects what they grouped on.
     */
    private record Filed(TypedValue key, List<Integer> members) {

    }

    /**
     * Group a sequence's entries and run the body once per group (design/16 §6).
     *
     * <p>Groups form in order of first appearance — a {@link java.util.LinkedHashMap} built in
     * one pass, which is the whole implementation. What is grouped is the <b>index set</b>:
     * members are store indices, bound as {@code __group}, so a nested walk over them can read
     * any parallel store at the record each names. Keys are compared by string form, the same
     * total reading an uncast ordering uses.
     *
     * <p>{@code __group_size} is known before the group's body opens, which is what lets an
     * author write a count into the opening tag — the thing the byte engine could not do when
     * {@code adjacent_groups} found its trailing-empty-group limit.
     */
    private void forEachGroup(final CompiledOp.ForEachGroup op,
                              final MatchResult match,
                              final int matchCount,
                              final byte[] content,
                              final OutputSink sink,
                              final long inputBase,
                              final boolean ignoreErrors,
                              final int depth,
                              final Encoding contentEncoding) {
        final List<Store> stores = vars.get(op.select());
        if (stores == null || stores.isEmpty()) {
            return;
        }

        // The same index a key builds (design/16 §8): grouping walks every entry of it,
        // a key reaches one entry by value. One builder, two readings.
        final Map<String, Filed> members = index(op.select(), op.groupBy(), match, matchCount, contentEncoding);
        if (members.isEmpty()) {
            return;
        }

        vars.push();
        vars.shadow(EngineVars.GROUP);
        vars.shadow(EngineVars.GROUP_KEY);
        vars.shadow(EngineVars.GROUP_SIZE);
        for (final Filed group : members.values()) {
            final List<Integer> indices = group.members();
            bindDense(EngineVars.GROUP, indices.stream()
                    .map(index -> (TypedValue) new TypedValue.Int(index))
                    .toList());
            final TypedValue key = group.key();
            if (key == null) {
                vars.store(EngineVars.GROUP_KEY).clear();
            } else {
                vars.store(EngineVars.GROUP_KEY).set(1, key);
            }
            vars.store(EngineVars.GROUP_SIZE).set(1, new TypedValue.Int(indices.size()));
            body(op.body(), match, matchCount, content, sink,
                    inputBase, ignoreErrors, depth, contentEncoding);
        }
        vars.pop();
    }

    /**
     * The entries in sorted order (design/16 §5) — <b>as an {@code int[]}, not as buffered
     * output</b>, which is the whole reason sorting is cheap here: the values are already in
     * memory, so ordering them reorders indices into a store rather than deferring anything
     * that has been written.
     *
     * <p>Keys are evaluated once per entry, up front, with {@code __index} and the item
     * binding in scope so a key can read the item or a parallel store at the same match.
     * Evaluating per comparison instead would re-resolve a reference O(n log n) times.
     *
     * <p>The sort is <b>stable</b>, and the list it sorts is in ascending store index, so
     * ties keep data order without an explicit tie-break — that is the same guarantee said
     * once rather than twice.
     */
    private List<Integer> sorted(final CompiledOp.ForEach op,
                                 final List<Integer> populated,
                                 final Store store,
                                 final MatchResult match,
                                 final int matchCount,
                                 final Encoding contentEncoding) {
        final int keyCount = op.sort().size();
        final TypedValue[][] keys = new TypedValue[populated.size()][keyCount];

        vars.push();
        if (op.as() != null) {
            vars.shadow(op.as());
        }
        vars.shadow(EngineVars.INDEX);
        // __position and __last are deliberately *not* shadowed here, which reverses part of
        // the phase 3 audit (phase 4 audit). They were, to stop a key reading a position that
        // did not exist yet — but this walk's scope has not been pushed, so what they resolve
        // to is an *enclosing* walk's position, which is a real value and legitimately
        // readable. Shadowing made that outer read absent and contradicted the scoping model
        // everywhere else in the engine. The compiler still warns, because reading them here
        // is far more likely to mean "this entry's position", which is what does not exist.
        for (int i = 0; i < populated.size(); i++) {
            final int index = populated.get(i);
            vars.store(EngineVars.INDEX).set(1, new TypedValue.Int(index));
            if (op.as() != null) {
                vars.store(op.as()).set(1, store.get(index));
            }
            for (int k = 0; k < keyCount; k++) {
                final CompiledOp.SortKey key = op.sort().get(k);
                final TypedValue raw = CompiledRefs.resolveValue(
                        key.by(), match, matchCount, vars, contentEncoding);
                // Uncast, an ordering compares string forms — the one total reading.
                keys[i][k] = Comparisons.cast(raw, key.as() == null ? Cast.STRING : key.as());
            }
        }
        vars.pop();

        final List<Integer> positions = new ArrayList<>(populated.size());
        for (int i = 0; i < populated.size(); i++) {
            positions.add(i);
        }
        positions.sort((left, right) -> {
            for (int k = 0; k < keyCount; k++) {
                final int comparison = compareKeys(keys[left][k], keys[right][k], op.sort().get(k).order());
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        });
        return positions.stream().map(populated::get).toList();
    }

    /**
     * One key's comparison. <b>Absent sorts last in either direction</b> — the unparseable
     * entries end up together at the bottom rather than migrating to the top when the order
     * flips, which is what a reader would read as data (design/16 §5).
     */
    private static int compareKeys(final TypedValue left,
                                   final TypedValue right,
                                   final OutputNode.Order order) {
        if (left == null || right == null) {
            return left == right ? 0 : (left == null ? 1 : -1);
        }
        final Integer comparison = Comparisons.compare(left, right);
        if (comparison == null) {
            return 0;
        }
        return order == OutputNode.Order.DESCENDING ? -comparison : comparison;
    }

    /** The distinct entries, first appearance kept, compared by string form. */
    private void distinct(final CompiledOp.DistinctValues op) {
        final Set<String> seen = new LinkedHashSet<>();
        final List<TypedValue> distinct = new ArrayList<>();
        for (final TypedValue value : entries(op.select())) {
            if (seen.add(value.asString())) {
                distinct.add(value);
            }
        }
        bindDense(op.name(), distinct);
    }

    /**
     * Walk a sequence, running the body once per populated entry (design/16 §4).
     *
     * <p>Populated entries in ascending index order, which is the one rule that reads both
     * indexing disciplines: a capture-indexed store's holes are skipped and its index still
     * means the match that produced it, while a dense one has no holes to skip. Index and
     * position are bound separately because they answer different questions — the index
     * reaches sibling data at the same match, the position is what {@code position()} means.
     *
     * <p>The iteration runs in its own scope, so the bindings do not outlive it and a nested
     * {@code for-each} shadows rather than overwrites the one around it.
     */
    private void forEach(final CompiledOp.ForEach op,
                         final MatchResult match,
                         final int matchCount,
                         final byte[] content,
                         final OutputSink sink,
                         final long inputBase,
                         final boolean ignoreErrors,
                         final int depth,
                         final Encoding contentEncoding) {
        final List<Store> stores = vars.get(op.select());
        if (stores == null || stores.isEmpty()) {
            return;
        }
        final Store store = stores.getFirst();
        final List<Integer> populated = new ArrayList<>();
        for (int i = 0; i < store.size(); i++) {
            if (store.get(i) != null) {
                populated.add(i);
            }
        }
        if (populated.isEmpty()) {
            return;
        }
        final List<Integer> order = op.sort().isEmpty()
                ? populated
                : sorted(op, populated, store, match, matchCount, contentEncoding);

        vars.push();
        if (op.as() != null) {
            vars.shadow(op.as());
        }
        vars.shadow(EngineVars.INDEX);
        vars.shadow(EngineVars.POSITION);
        vars.shadow(EngineVars.LAST);
        // Known before the first body runs, which is what makes a last-entry test cheap and
        // correct — and is the fix for the trailing-empty-group limit adjacent_groups found.
        vars.store(EngineVars.LAST).set(1, new TypedValue.Int(order.size()));
        for (int position = 0; position < order.size(); position++) {
            // Position follows the ordering; the index still points at the record, so a key
            // that reordered the walk does not disturb what a body reads (design/16 §5).
            final int index = order.get(position);
            vars.store(EngineVars.INDEX).set(1, new TypedValue.Int(index));
            vars.store(EngineVars.POSITION).set(1, new TypedValue.Int(position + 1L));
            if (op.as() != null) {
                vars.store(op.as()).set(1, store.get(index));
            }
            body(op.body(), match, matchCount, content, sink,
                    inputBase, ignoreErrors, depth, contentEncoding);
        }
        vars.pop();
    }

    /**
     * A structural call, with the sink's refusal turned into the run's last message. The sink
     * knows the rule (an attribute after content, a close with nothing open); the executor knows
     * which instruction broke it, and a fatal is where a misshapen document stops rather than a
     * half-written one continuing.
     */
    private void structure(final Runnable call, final String instruction) {
        try {
            call.run();
        } catch (final OutputSink.StructureException e) {
            messages.add(new Message(Severity.FATAL, "Output structure at " + instruction + ": " + e.getMessage()));
            throw new AbortRun();
        }
    }

    /**
     * Bind a variable to what a nested body produces.
     *
     * <p>The body runs in its own scope and into its own buffer, and what comes out depends on
     * what it did. If it captured into the variable's name — which is what happens when the body
     * dispatches to templates that capture — those captures are promoted whole, keeping their
     * per-match structure so that a later reference can still ask for the third one. Otherwise
     * the text it wrote becomes the value. If it did neither, the variable is cleared rather
     * than left holding the previous record's value.
     */
    private void variable(final CompiledOp.Variable value,
                          final MatchResult match,
                          final int matchCount,
                          final byte[] content,
                          final long inputBase,
                          final boolean ignoreErrors,
                          final int depth,
                          final Encoding contentEncoding) {
        vars.push();
        vars.shadow(value.name());

        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        body(value.body(), match, matchCount, content,
                OutputSink.of(buffer), inputBase, ignoreErrors, depth, contentEncoding);

        List<Store> captured = vars.fromCurrentScope(value.name());
        if (captured != null && captured.stream().noneMatch(store -> store.lastIndex() >= 0)) {
            captured = null;
        }
        final List<Store> promoted = captured == null ? null : List.copyOf(captured);
        vars.pop();

        if (promoted != null) {
            final List<Store> target = vars.entry(value.name());
            target.clear();
            target.addAll(promoted);
        } else if (buffer.size() > 0) {
            vars.store(value.name()).set(matchCount, TypedValue.of(buffer.toByteArray()));
        } else {
            vars.store(value.name()).remove(matchCount);
        }
    }

    /**
     * Invoke a template by name, with parameters and without matching anything.
     *
     * <p>Parameters live in their own scope, so a call cannot leave its arguments behind for the
     * next one. Declared parameters the caller did not supply take their defaults.
     */
    private void call(final CompiledOp.Call value,
                      final MatchResult match,
                      final int matchCount,
                      final byte[] content,
                      final OutputSink sink,
                      final long inputBase,
                      final boolean ignoreErrors,
                      final int depth,
                      final Encoding contentEncoding) {
        final CompiledTemplate target = compiled.template(value.name());
        if (target == null) {
            return;
        }

        // Arguments resolve in the caller's scope — an argument may legitimately read the very
        // variable its parameter will shadow — and only then does the call's own scope open.
        final List<byte[]> resolved = new ArrayList<>(value.args().size());
        for (final CompiledOp.Arg arg : value.args()) {
            resolved.add(CompiledRefs.resolve(arg.value(), match, matchCount, vars, contentEncoding));
        }

        vars.push();
        for (int i = 0; i < value.args().size(); i++) {
            final CompiledOp.Arg arg = value.args().get(i);
            // Shadow before storing: store() searches outwards, and a parameter whose name
            // collides with a capture registered globally would otherwise write straight
            // through the new scope and outlive the call.
            vars.shadow(arg.name());
            if (resolved.get(i) != null) {
                vars.store(arg.name()).set(1, TypedValue.of(resolved.get(i)));
            }
        }
        for (final Template.ParamDecl declared : target.template().param()) {
            vars.shadow(declared.name());
            final boolean supplied = value.args().stream()
                    .anyMatch(arg -> arg.name().equals(declared.name()));
            if (!supplied && declared.defaultValue() != null) {
                vars.store(declared.name())
                        .set(1, TypedValue.of(declared.defaultValue().getBytes(StandardCharsets.UTF_8)));
            }
        }
        body(target.body(), match, matchCount, content, sink, inputBase, ignoreErrors, depth, contentEncoding);
        vars.pop();
    }

    /**
     * Hand some content to another set of templates.
     *
     * <p>Which content is a reference, and it is usually a group of the match just made — that is
     * how a row is broken into fields, and a field into parts. The templates that get it are the
     * level of the named mode, dispatched as ordered choice, with the directive's
     * {@code ignoreErrors} as the level's reporting gate.
     */
    private void apply(final CompiledOp.Apply op,
                       final MatchResult match,
                       final int matchCount,
                       final byte[] parentContent,
                       final OutputSink sink,
                       final long parentBase,
                       final boolean inheritedIgnoreErrors,
                       final int depth,
                       final Encoding contentEncoding) {
        final ApplyDirective directive = op.directive();
        if (depth >= directive.maxDepth()) {
            return;
        }

        // "Group 0" means the content this template is working on, which is not always group 0
        // of its match: a delimiter template's content is the field, and its group 0 carries the
        // delimiter too. Resolving group 0 here would hand the trailing separator down to the
        // child templates, which is what turns a CSV header's last column name into "what\n".
        // So the content the parent already selected is passed straight through — and whether
        // that is what the select means was decided at compile time.
        final byte[] content = op.wholeParentContent()
                ? parentContent
                : CompiledRefs.resolve(op.select(), match, matchCount, vars, contentEncoding);
        if (content == null || content.length == 0) {
            return;
        }

        // Content taken straight from the parent, or from one of its groups, is still part of
        // the input and can be pointed at. Content built from a variable cannot be.
        final long childBase = op.locatable() ? parentBase : Instrument.UNLOCATABLE;
        if (childBase == Instrument.UNLOCATABLE) {
            instrument.onMatchContent(null, content);
        }

        final String mode = directive.effectiveMode();
        // A compile-time fact read as a field — nothing filters the template list per call.
        final List<CompiledTemplate> candidates = compiled.templates(mode);

        // A recursive apply gets its own scope, so that a nested level's captures cannot leak
        // back into the level that invoked it — and so that they are released on the way out.
        final boolean recursive = directive.templateRef() != null
                                  || (directive.mode() != null
                                      && directive.mode().startsWith(ApplyDirective.RECURSIVE_PREFIX));
        if (recursive) {
            vars.push();
            candidates.forEach(candidate -> candidate.template().captures()
                    .forEach(capture -> vars.shadow(capture.name())));
        }

        // DS3 inherits ignoreErrors down the tree: a level inside an ignoring container is
        // gated even when its own directive says nothing.
        level.dispatch(candidates, content, 0, content.length, sink, childBase,
                inheritedIgnoreErrors || directive.ignoreErrors(), depth + 1, op.dispatch(), encoding);

        if (recursive) {
            vars.pop();
        }
    }


}
