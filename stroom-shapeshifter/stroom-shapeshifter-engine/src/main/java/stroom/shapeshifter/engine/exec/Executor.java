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
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.compile.CompiledTemplate;
import stroom.shapeshifter.engine.config.CaptureBinding;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The runtime: reads the input, drives the templates, writes the output.
 *
 * <p>Input arrives in buffers, and <b>a match never spans two of them</b>. That is a real
 * limitation rather than an oversight — it is the Rust engine's behaviour, ported deliberately so
 * that golden parity means something (D33) — and it is why a configuration's buffer size is also
 * the largest record it can handle. The matching layer underneath can do better, and lifting this
 * is the first thing to decide once the port is green.
 *
 * <p>Within a buffer the shape is simple and recursive. The document template writes its prologue,
 * hands the buffer to the templates of its mode, and writes its epilogue at the end of the stream.
 * A level's templates are dispatched as <b>iterated ordered choice</b> — {@code (A|B|C)*}, DS3's
 * own model (D34): each pass, the first template that matches wins one match, and the choice
 * re-opens from the first template. A match's body can hand a captured group down to another
 * level, which is how a record becomes fields and a field becomes parts.
 */
public final class Executor {

    /** The engine's own variable: how many times the current template has matched, 1-based. */
    private static final String MATCH_COUNT = "__match_count";

    /** The engine's own variable: the same count, 0-based, for the XSLT-shaped reading. */
    private static final String MATCH_INDEX = "__match_idx";

    private final CompiledProject compiled;
    private final OutputSink output;
    private final Instrument instrument;
    private final List<Message> messages = new ArrayList<>();
    private final VarRegistry vars = new VarRegistry();

    /**
     * The encoding in force. Starts as whatever the configuration declared, and is replaced if
     * the input opens with a byte-order mark, which is better evidence than a declaration.
     */
    private Encoding encoding;

    private Executor(final CompiledProject compiled, final OutputSink sink, final Instrument instrument) {
        this.compiled = compiled;
        this.output = sink;
        this.instrument = instrument;
        this.encoding = compiled.encoding();
        this.messages.addAll(compiled.warnings());
    }

    /**
     * Run a compiled configuration over an input.
     *
     * @param wholeBuffer read the input as a single buffer rather than in chunks. The progressive
     *                    matches need it — an absolute seek is meaningless over a window — and it
     *                    also suppresses the warning about a template consuming a whole buffer,
     *                    which cannot indicate truncation when there is only one
     * @return everything the engine had to say, in the order it said it
     */
    public static List<Message> run(final CompiledProject compiled,
                                    final InputStream input,
                                    final OutputSink sink,
                                    final Instrument instrument,
                                    final boolean wholeBuffer) {
        return new Executor(compiled, sink, instrument).execute(input, wholeBuffer);
    }

    // -----------------------------------------------------------------------------------
    // The stream
    // -----------------------------------------------------------------------------------

    private List<Message> execute(final InputStream input, final boolean wholeBuffer) {
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
        final List<CompiledTemplate> roots = compiled.templates().stream()
                .filter(t -> !(t.match() instanceof CompiledMatch.Source))
                .filter(t -> java.util.Objects.equals(t.template().mode(), streamMode))
                .toList();

        // The root level's gate is the configuration's own ignoreErrors — DS3's flag on the
        // dataSplitter element itself — or the document template's directive saying so.
        final boolean rootIgnoreErrors = compiled.project().source().ignoreErrors()
                || (streamDirective != null && streamDirective.ignoreErrors());

        final MatchResult nothing = MatchResult.empty();
        List<OutputNode> prologue = List.of();
        List<OutputNode> epilogue = List.of();
        if (source != null) {
            final List<OutputNode> body = source.template().body();
            final int apply = indexOfApply(body);
            prologue = apply < 0 ? body : body.subList(0, apply);
            epilogue = apply < 0 ? List.of() : body.subList(apply + 1, body.size());
        }

        for (final Template template : compiled.project().templates()) {
            for (final CaptureBinding capture : template.captures()) {
                vars.register(capture.name());
            }
        }

        if (!prologue.isEmpty()) {
            body(prologue, nothing, 0, new byte[0], output, 0L, rootIgnoreErrors, 0);
        }

        final int bufferSize = wholeBuffer
                ? Integer.MAX_VALUE
                : Math.max(1, compiled.project().source().bufferSize());
        boolean first = true;
        long read = 0;
        for (byte[] chunk = read(input, bufferSize); chunk != null; chunk = read(input, bufferSize)) {
            int from = 0;
            if (first) {
                first = false;
                final Encoding.ByteOrderMark mark = Encoding.detectByteOrderMark(chunk);
                if (mark != null) {
                    encoding = mark.encoding();
                    from = mark.length();
                }
            }
            if (from >= chunk.length) {
                continue;
            }

            final int consumed = level(roots, chunk, from, chunk.length, output, read, rootIgnoreErrors, 0);
            if (!wholeBuffer && consumed > 0 && from + consumed == chunk.length
                && chunk.length - from == bufferSize) {
                messages.add(new Message(Severity.WARNING, "Expressions consumed entire buffer ("
                                                           + consumed
                                                           + " bytes). If data is truncated, increase source "
                                                           + "buffer_size (currently " + bufferSize + ")."));
            }
            read += chunk.length - from;
        }

        if (!epilogue.isEmpty()) {
            body(epilogue, nothing, 0, new byte[0], output, 0L, rootIgnoreErrors, 0);
        }
        return List.copyOf(messages);
    }

    private static ApplyDirective applyDirective(final Template template) {
        for (final OutputNode node : template.body()) {
            if (node instanceof OutputNode.ApplyTemplates apply) {
                return apply.directive();
            }
        }
        return null;
    }

    private static int indexOfApply(final List<OutputNode> body) {
        for (int i = 0; i < body.size(); i++) {
            if (body.get(i) instanceof OutputNode.ApplyTemplates) {
                return i;
            }
        }
        return -1;
    }

    // -----------------------------------------------------------------------------------
    // One level against one region
    // -----------------------------------------------------------------------------------

    /**
     * Dispatch one level — the templates of a mode — against one region of content.
     *
     * <p>Each pass tries the level's templates in order from the first; the first that matches
     * consumes one match, and the next pass starts again from the first template. A template that
     * fails, is past its {@code maxMatch}, or whose guard declines eats nothing and the next is
     * tried. Order therefore expresses priority, and a template can never be starved by an
     * earlier sibling being "finished" — there is no finished, only not-matching-here.
     *
     * <p>Skipping is reported, never silent (D34). An unanchored match that starts past the
     * cursor consumes the skipped prefix — that is what consuming to the match end means — and
     * says so; content no pass could match at all is reported once, for the level. Both reports
     * are DS3's, kept for its reasons: no data loss without a message, and steady pressure toward
     * start-anchored expressions, which are also the cheap ones.
     *
     * @param ignoreErrors the level's gate, from the directive that dispatched it — DS3's group
     *                     flag — or from the source configuration at the root
     * @return how many bytes the level consumed
     */
    private int level(final List<CompiledTemplate> templates,
                      final byte[] data,
                      final int from,
                      final int to,
                      final OutputSink sink,
                      final long inputBase,
                      final boolean ignoreErrors,
                      final int depth) {
        final int[] counts = new int[templates.size()];

        // Guards are evaluated once, on the way in — not per pass. The distinction is
        // load-bearing: a guard reads scope, and scope includes the parent's match counter,
        // which DS3-style onlyMatch guards compare against. Once matching starts, each winner
        // overwrites that counter with its own count, so a guard re-read mid-level would compare
        // a template against itself. DS3 gets this for free by passing the parent's count down
        // as a parameter; evaluating here, while the scope still describes the parent, is the
        // same thing said with variables.
        final boolean[] allowed = new boolean[templates.size()];
        for (int i = 0; i < templates.size(); i++) {
            final Template template = templates.get(i).template();
            allowed[i] = template.guard() == null
                         || Conditions.evaluate(
                    template.guard(), MatchResult.empty(), 1, vars, encoding, compiled.patterns());
        }

        int cursor = from;
        boolean matched = true;

        while (cursor < to && matched) {
            matched = false;
            for (int i = 0; i < templates.size(); i++) {
                if (!allowed[i]) {
                    continue;
                }
                final CompiledTemplate candidate = templates.get(i);
                final Template template = candidate.template();
                final int maxMatch = template.matchLimits().maxMatch();
                if (maxMatch >= 0 && counts[i] >= maxMatch) {
                    continue;
                }
                final long timing = instrument.startTiming();
                final MatchResult match = match(candidate, data, cursor, to);
                instrument.stopTiming(template.id(), timing, match != null);
                if (match == null) {
                    continue;
                }

                counts[i]++;
                final int matchCount = counts[i];

                // A template's first match of this dispatch begins a new sequence, and a new
                // sequence starts from nothing: its captures' stores are cleared, so a record
                // matching fewer times than the one before it cannot leave the previous
                // record's tail to be read past its own length. This is DS3's own rule — its
                // storeData clears on the first store of a sequence. The absent-template case
                // is deliberately not covered, because DS3 does not cover it either: a template
                // that never matches leaves its stores untouched, previous record and all (E19).
                if (matchCount == 1) {
                    for (final CaptureBinding capture : template.captures()) {
                        if (!(capture.select() instanceof CaptureBinding.CaptureSource.KeyValue)) {
                            vars.store(capture.name()).clear();
                        }
                    }
                }

                if (match.matchStart() > 0 && !ignoreErrors && !template.ignoreErrors()) {
                    messages.add(new Message(Severity.ERROR,
                            "Expression '" + template.name()
                            + "' failed to match from the start of the content. Skipped: ["
                            + preview(data, cursor, cursor + match.matchStart()) + "]"));
                }

                // The engine's own variables, readable by any reference: how many times this
                // template has matched. A child template's reference to a parent's multi-valued
                // capture uses this to pick the right one, which is how a header column lines up
                // with the data column beneath it.
                vars.store(MATCH_INDEX).set(1, new TypedValue.Int(matchCount - 1));
                vars.store(MATCH_COUNT).set(1, new TypedValue.Int(matchCount));

                final boolean wanted = template.matchLimits().onlyMatch() == null
                                       || template.matchLimits().onlyMatch().contains(matchCount);
                if (wanted) {
                    // A delimiter match's content is the field, not the field plus its
                    // delimiter; every other kind of match means the whole of what it matched.
                    final int contentGroup =
                            template.match() instanceof MatchExpression.Delimiter ? 1 : 0;
                    final TypedValue content = match.group(contentGroup) != null
                            ? match.group(contentGroup)
                            : match.group(0);

                    if (content != null && !content.isEmpty()) {
                        instrument.onMatch(template.id(), template.name(),
                                locate(inputBase, cursor - from + match.matchStart()),
                                match.advance(), matchCount, depth);
                        bindCaptures(candidate, match, matchCount);

                        final long before = sink.position();
                        body(template.body(), match, matchCount, content.asBytes(), sink,
                                inputBase, ignoreErrors, depth);
                        instrument.onOutput(template.id(), matchCount, before,
                                sink.position() - before);
                    }
                }

                if (match.advance() > 0) {
                    cursor += match.advance();
                    matched = true;
                }
                // First match wins the pass; the choice re-opens from the first template.
                break;
            }
        }

        boolean minMatchFailed = false;
        for (int i = 0; i < templates.size(); i++) {
            final Template template = templates.get(i).template();
            final int minMatch = template.matchLimits().minMatch();
            if (minMatch > 0 && counts[i] < minMatch) {
                minMatchFailed = true;
                messages.add(new Message(Severity.ERROR,
                        "Expression '" + template.name()
                        + "' did not match the required number of times (match count: "
                        + counts[i] + ")"));
            }
        }

        // Content no pass could match, reported once for the level — unless a minimum-match
        // error already explained the same failure. Stroom's own record fixes both halves of
        // this rule: 005's fully-unmatched line is reported even though nothing matched, and
        // 014's is not, because its three minMatch errors already said what was wrong.
        if (!minMatchFailed && !ignoreErrors && hasContent(data, cursor, to)) {
            messages.add(new Message(Severity.ERROR,
                    "Expressions failed to match all of the content. Unmatched: ["
                    + preview(data, cursor, to) + "]"));
        }
        return cursor - from;
    }

    /** True if a region holds anything but whitespace. Blank remainders are not worth a message. */
    private static boolean hasContent(final byte[] data, final int from, final int to) {
        for (int i = from; i < to; i++) {
            final byte b = data[i];
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r' && b != '\f' && b != 0x0B) {
                return true;
            }
        }
        return false;
    }

    /** The start of a region, as text fit for a message: newlines escaped, capped at 200 bytes. */
    private static String preview(final byte[] data, final int from, final int to) {
        final int length = Math.min(200, to - from);
        return new String(data, from, length, StandardCharsets.UTF_8).replace("\n", "\\n")
               + (to - from > 200 ? "...TRUNCATED..." : "");
    }

    private MatchResult match(final CompiledTemplate compiledTemplate,
                              final byte[] data,
                              final int from,
                              final int to) {
        if (from >= to) {
            return null;
        }
        return switch (compiledTemplate.match()) {
            case CompiledMatch.Delimiter delimiter -> Splitter.split(data, from, to,
                    delimiter.delimiter(), delimiter.escape(),
                    delimiter.containerStart(), delimiter.containerEnd());
            case CompiledMatch.Regex regex -> regexMatch(regex, data, from, to);
            case CompiledMatch.Progressive progressive ->
                    Steps.match(progressive.steps(), data, from, to, compiled.patterns());
            case CompiledMatch.All ignored -> new MatchResult(
                    new TypedValue[]{TypedValue.of(Arrays.copyOfRange(data, from, to))}, to - from, 0);
            case CompiledMatch.Source ignored -> null;
            case CompiledMatch.Named ignored -> null;
        };
    }

    private static MatchResult regexMatch(final CompiledMatch.Regex regex,
                                          final byte[] data,
                                          final int from,
                                          final int to) {
        final ByteMatcher matcher = regex.pattern().matcher();
        if (!matcher.match(data, from, to, Anchoring.UNANCHORED)) {
            return null;
        }
        final int groupCount = regex.pattern().groupCount() + 1;
        final TypedValue[] groups = new TypedValue[groupCount];
        for (int i = 0; i < groupCount; i++) {
            if (matcher.matchedGroup(i)) {
                groups[i] = TypedValue.of(matcher.groupBytes(i));
            }
        }

        // The cursor normally lands at the end of the match. A template can ask for the end of a
        // group instead, which is how a pattern looks further ahead than it consumes.
        int end = matcher.end();
        if (regex.advance() > 0 && regex.advance() < groupCount && matcher.matchedGroup(regex.advance())) {
            end = matcher.end(regex.advance());
        }
        return new MatchResult(groups, end - from, matcher.start() - from);
    }

    // -----------------------------------------------------------------------------------
    // Captures and body
    // -----------------------------------------------------------------------------------

    private void bindCaptures(final CompiledTemplate compiledTemplate,
                              final MatchResult match,
                              final int matchCount) {
        for (final CaptureBinding capture : compiledTemplate.template().captures()) {
            final TypedValue value = switch (capture.select()) {
                // A capture is a slice of the input, and is stored in the engine's own form so
                // that everything reading it later can assume UTF-8.
                case CaptureBinding.CaptureSource.Group group -> normalise(match.group(group.group()));
                case CaptureBinding.CaptureSource.Step step -> normalise(match.group(step.index() + 1));
                case CaptureBinding.CaptureSource.Select select -> {
                    final byte[] bytes = Refs.resolve(select.select(), match, matchCount, vars, encoding);
                    yield bytes == null ? null : TypedValue.of(bytes);
                }
                case CaptureBinding.CaptureSource.Field ignored -> null;
                case CaptureBinding.CaptureSource.KeyValue keyValue -> {
                    final String key = Refs.resolveText(keyValue.keyRef(), match, matchCount, vars, encoding);
                    if (key != null) {
                        final byte[] bytes = Refs.resolve(keyValue.valueRef(), match, matchCount, vars, encoding);
                        if (bytes != null) {
                            vars.store(key).set(matchCount, TypedValue.of(bytes));
                        }
                    }
                    yield null;
                }
            };
            if (capture.select() instanceof CaptureBinding.CaptureSource.KeyValue) {
                continue;
            }
            final Store store = vars.store(capture.name());
            if (value != null) {
                instrument.onCapture(compiledTemplate.template().id(), capture.name(),
                        value.asBytes(), matchCount);
            }
            if (value == null) {
                // An unmatched capture must read as empty, not as whatever the previous record
                // left there.
                store.remove(matchCount);
            } else {
                store.set(matchCount, value);
            }
        }
    }

    /** Convert a captured value into the engine's internal form, which is UTF-8. */
    private TypedValue normalise(final TypedValue value) {
        if (value == null || encoding.isUtf8Compatible() || !(value instanceof TypedValue.Bytes)) {
            return value;
        }
        return TypedValue.of(Refs.bytes(value, encoding));
    }

    private void body(final List<OutputNode> nodes,
                      final MatchResult match,
                      final int matchCount,
                      final byte[] content,
                      final OutputSink sink,
                      final long inputBase,
                      final boolean ignoreErrors,
                      final int depth) {
        for (final OutputNode node : nodes) {
            switch (node) {
                case OutputNode.Text text -> sink.write(text.value());
                case OutputNode.ValueOf valueOf ->
                        Refs.write(valueOf.select(), match, matchCount, vars, encoding, sink);
                case OutputNode.ApplyTemplates apply -> {
                    // A directive naming a template is the recursive form, which the compiler
                    // has already inlined; running it here would recurse for ever.
                    if (apply.directive().templateRef() == null) {
                        apply(apply.directive(), match, matchCount, content, sink, inputBase, ignoreErrors, depth);
                    }
                }
                case OutputNode.If value -> {
                    if (test(value.test(), match, matchCount)) {
                        body(value.then(), match, matchCount, content, sink, inputBase, ignoreErrors, depth);
                    }
                }
                case OutputNode.Choose value -> {
                    boolean taken = false;
                    for (final OutputNode.WhenBranch branch : value.when()) {
                        if (test(branch.test(), match, matchCount)) {
                            body(branch.body(), match, matchCount, content, sink, inputBase, ignoreErrors, depth);
                            taken = true;
                            break;
                        }
                    }
                    if (!taken) {
                        body(value.otherwise(), match, matchCount, content, sink, inputBase, ignoreErrors, depth);
                    }
                }
                case OutputNode.Switch value -> {
                    final String selected = textOf(value.select(), match, matchCount);
                    boolean taken = false;
                    for (final OutputNode.SwitchCase switchCase : value.cases()) {
                        if (switchCase.value().equals(selected)) {
                            body(switchCase.body(), match, matchCount, content, sink, inputBase, ignoreErrors, depth);
                            taken = true;
                            break;
                        }
                    }
                    if (!taken) {
                        body(value.defaultBody(), match, matchCount, content, sink, inputBase, ignoreErrors, depth);
                    }
                }
                case OutputNode.Variable value ->
                        variable(value, match, matchCount, content, inputBase, ignoreErrors, depth);
                case OutputNode.CallTemplate value ->
                        call(value, match, matchCount, content, sink, inputBase, ignoreErrors, depth);
                case OutputNode.ValueMap value -> {
                    final String selected = textOf(value.select(), match, matchCount);
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
                    emit(mapped == null ? "" : mapped, value.name(), matchCount, sink);
                }
                case OutputNode.Translate value -> transform(value.select(), value.name(), match,
                        matchCount, sink, inputs -> Transforms.translate(inputs, value.from(), value.to()));
                case OutputNode.StringJoin value -> transform(value.select(), value.name(), match,
                        matchCount, sink, inputs -> Transforms.stringJoin(inputs, value.separator()));
                case OutputNode.Replace value -> transform(value.select(), value.name(), match,
                        matchCount, sink, inputs -> {
                            if (inputs.isEmpty()) {
                                return null;
                            }
                            return value.isRegex()
                                    ? Transforms.replaceRegex(pattern(value.pattern()), inputs.getFirst(),
                                    value.replacement())
                                    : Transforms.replaceLiteral(inputs, value.pattern(), value.replacement());
                        });
                case OutputNode.LowerCase value -> transform(value.select(), value.name(), match,
                        matchCount, sink, Transforms::lowerCase);
                case OutputNode.UpperCase value -> transform(value.select(), value.name(), match,
                        matchCount, sink, Transforms::upperCase);
                case OutputNode.NormalizeSpace value -> transform(value.select(), value.name(), match,
                        matchCount, sink, Transforms::normalizeSpace);
                case OutputNode.Trim value -> transform(value.select(), value.name(), match,
                        matchCount, sink, Transforms::trim);
                case OutputNode.Substring value -> transform(value.select(), value.name(), match,
                        matchCount, sink, inputs -> Transforms.substring(inputs, value.start(), value.length()));
                case OutputNode.Tokenize value -> transform(value.select(), value.name(), match,
                        matchCount, sink, inputs -> Transforms.tokenize(inputs, value.delimiter()));
                case OutputNode.Number value -> transform(value.select(), value.name(), match,
                        matchCount, sink, Transforms::number);
            }
        }
    }

    private boolean test(final stroom.shapeshifter.engine.config.Condition condition,
                         final MatchResult match,
                         final int matchCount) {
        return Conditions.evaluate(condition, match, matchCount, vars, encoding, compiled.patterns());
    }

    private String textOf(final RefExpression expression, final MatchResult match, final int matchCount) {
        final String resolved = Refs.resolveText(expression, match, matchCount, vars, encoding);
        return resolved == null ? "" : resolved;
    }

    private stroom.shapeshifter.regex.BytePattern pattern(final String text) {
        final stroom.shapeshifter.regex.BytePattern pattern = compiled.patterns().get(text);
        if (pattern == null) {
            throw new IllegalStateException("Pattern was not compiled: " + text);
        }
        return pattern;
    }

    /**
     * Run a transform function and either write its result or bind it to a variable.
     *
     * <p>An input that resolves to nothing is dropped rather than passed along as an empty
     * string, so a function receiving two references and finding one absent sees one input, not
     * two of which one is blank. And a function returning nothing writes nothing — which is what
     * makes a join of no values disappear instead of leaving a stray separator.
     */
    private void transform(final List<RefExpression> select,
                           final String name,
                           final MatchResult match,
                           final int matchCount,
                           final OutputSink sink,
                           final java.util.function.Function<List<String>, String> function) {
        final List<String> inputs = new ArrayList<>(select.size());
        for (final RefExpression expression : select) {
            final String resolved = Refs.resolveText(expression, match, matchCount, vars, encoding);
            if (resolved != null) {
                inputs.add(resolved);
            }
        }
        final String result = function.apply(inputs);
        if (result != null) {
            emit(result, name, matchCount, sink);
        }
    }

    /** Write a produced value, or bind it to a variable if the instruction named one. */
    private void emit(final String value, final String name, final int matchCount, final OutputSink sink) {
        if (name == null) {
            sink.write(value);
        } else {
            vars.store(name).set(matchCount, TypedValue.of(value.getBytes(StandardCharsets.UTF_8)));
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
    private void variable(final OutputNode.Variable value,
                          final MatchResult match,
                          final int matchCount,
                          final byte[] content,
                          final long inputBase,
                          final boolean ignoreErrors,
                          final int depth) {
        vars.push();
        vars.shadow(value.name());

        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        body(value.body(), match, matchCount, content, OutputSink.of(buffer), inputBase, ignoreErrors, depth);

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
    private void call(final OutputNode.CallTemplate value,
                      final MatchResult match,
                      final int matchCount,
                      final byte[] content,
                      final OutputSink sink,
                      final long inputBase,
                      final boolean ignoreErrors,
                      final int depth) {
        final CompiledTemplate target = compiled.templates().stream()
                .filter(candidate -> candidate.template().name().equals(value.name()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return;
        }

        vars.push();
        for (final OutputNode.Param param : value.withParam()) {
            final byte[] resolved = Refs.resolve(param.value(), match, matchCount, vars, encoding);
            if (resolved != null) {
                vars.store(param.name()).set(1, TypedValue.of(resolved));
            }
        }
        for (final Template.ParamDecl declared : target.template().param()) {
            final boolean supplied = value.withParam().stream()
                    .anyMatch(param -> param.name().equals(declared.name()));
            if (!supplied && declared.defaultValue() != null) {
                vars.store(declared.name())
                        .set(1, TypedValue.of(declared.defaultValue().getBytes(StandardCharsets.UTF_8)));
            }
        }
        body(target.template().body(), match, matchCount, content, sink, inputBase, ignoreErrors, depth);
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
    private void apply(final ApplyDirective directive,
                       final MatchResult match,
                       final int matchCount,
                       final byte[] parentContent,
                       final OutputSink sink,
                       final long parentBase,
                       final boolean inheritedIgnoreErrors,
                       final int depth) {
        if (depth >= directive.maxDepth()) {
            return;
        }

        // "Group 0" means the content this template is working on, which is not always group 0
        // of its match: a delimiter template's content is the field, and its group 0 carries the
        // delimiter too. Resolving group 0 here would hand the trailing separator down to the
        // child templates, which is what turns a CSV header's last column name into "what\n".
        // So the content the parent already selected is passed straight through.
        final byte[] content = isWholeParentContent(directive.select())
                ? parentContent
                : Refs.resolve(directive.select(), match, matchCount, vars, encoding);
        if (content == null || content.length == 0) {
            return;
        }

        // Content taken straight from the parent, or from one of its groups, is still part of
        // the input and can be pointed at. Content built from a variable cannot be.
        final long childBase = isWholeParentContent(directive.select()) || isLocalGroup(directive.select())
                ? parentBase
                : Instrument.UNLOCATABLE;
        if (childBase == Instrument.UNLOCATABLE) {
            instrument.onMatchContent(null, content);
        }

        final String mode = directive.templateRef() != null
                ? "__rec_" + directive.templateRef()
                : directive.mode();
        final List<CompiledTemplate> candidates = compiled.templates().stream()
                .filter(t -> java.util.Objects.equals(t.template().mode(), mode))
                .toList();

        // A recursive apply gets its own scope, so that a nested level's captures cannot leak
        // back into the level that invoked it — and so that they are released on the way out.
        final boolean recursive = directive.templateRef() != null
                                  || (directive.mode() != null && directive.mode().startsWith("__rec_"));
        if (recursive) {
            vars.push();
            candidates.forEach(candidate -> candidate.template().captures()
                    .forEach(capture -> vars.shadow(capture.name())));
        }

        // DS3 inherits ignoreErrors down the tree: a level inside an ignoring container is
        // gated even when its own directive says nothing.
        level(candidates, content, 0, content.length, sink, childBase,
                inheritedIgnoreErrors || directive.ignoreErrors(), depth + 1);

        if (recursive) {
            vars.pop();
        }
    }

    /** An absolute input offset, unless the base says the content cannot be located. */
    private static long locate(final long base, final int offset) {
        return base >= Instrument.UNLOCATABLE ? Instrument.UNLOCATABLE : base + offset;
    }

    /** True if an expression is one group of this match, wherever that group came from. */
    private static boolean isLocalGroup(final RefExpression expression) {
        return expression.parts().size() == 1
               && expression.parts().getFirst() instanceof RefExpression.RefPart.Capture capture
               && capture.varId() == null
               && capture.matchIndex() == null;
    }

    /** True if an expression is exactly "group 0 of this match, whichever one that is". */
    private static boolean isWholeParentContent(final RefExpression expression) {
        return expression.parts().size() == 1
               && expression.parts().getFirst() instanceof RefExpression.RefPart.Capture capture
               && capture.varId() == null
               && capture.group() == 0
               && capture.matchIndex() == null;
    }

    // -----------------------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------------------

    /** Read one buffer, or null at the end of the input. */
    private static byte[] read(final InputStream input, final int size) {
        try {
            if (size == Integer.MAX_VALUE) {
                final byte[] all = input.readAllBytes();
                return all.length == 0 ? null : all;
            }
            final byte[] buffer = new byte[size];
            int total = 0;
            while (total < size) {
                final int read = input.read(buffer, total, size - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            return total == 0 ? null : (total == size ? buffer : Arrays.copyOf(buffer, total));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
