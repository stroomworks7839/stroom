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
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.text.Transcode;
import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.TrailingAnchor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The runtime: reads the input, drives the templates, writes the output.
 *
 * <p>Input arrives through a <b>sliding window</b> of the configured buffer size (E13, restoring
 * DS3's own shape): records consume from the window's front and it refills behind them, so a
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
public final class Executor {

    private final CompiledProject compiled;
    private final OutputSink output;
    private final Instrument instrument;
    private final List<Message> messages = new ArrayList<>();
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
    private final java.util.Map<String, java.util.Map<String, Filed>> keyIndexes =
            new java.util.HashMap<>();

    /**
     * The arithmetic sites that have already drawn a strict_values warning this run — once
     * per instruction site, because once per record on a million-record input is not a
     * diagnostic, it is a flood (design/17 §10).
     */
    private final java.util.Set<CompiledOp.Transform> warnedNumeric =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Whether the root reads the input in pieces whose counters restart (design/16 §10).
     *
     * <p>A {@code classify} or {@code any} root is dispatched chunk-at-a-time by repeated
     * {@code level()} calls, so template counters reset and capture stores clear <b>per
     * chunk</b>. An accumulation there would summarise the last chunk while presenting itself
     * as a summary of the input — a wrong answer wearing the shape of a right one, which is
     * the failure mode this whole section exists to refuse. A whole-buffer run takes the same
     * code path with exactly one chunk, and is therefore fine.
     */
    private boolean chunkedRoot;

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
        // Phase 6 (design 19): a transcode-family source becomes UTF-8 bytes before the
        // window machinery reads it; report by default, replace under ignore_errors.
        final InputStream source = compiled.transcodeFrom() != null
                ? Transcode.wrap(input, compiled.transcodeFrom().charset(),
                        compiled.project().source().ignoreErrors())
                : input;
        return new Executor(compiled, sink, instrument).execute(source, wholeBuffer);
    }

    // -----------------------------------------------------------------------------------
    // The stream
    // -----------------------------------------------------------------------------------

    /** A fatal emission ends the run; the message is already recorded when this flies. */
    private static final class AbortRun extends RuntimeException {

        AbortRun() {
            super(null, null, false, false);
        }
    }

    private List<Message> execute(final InputStream input, final boolean wholeBuffer) {
        try {
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
                structure(() -> output.startElement(element.name(), element.namespace()),
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
            for (byte[] chunk = read(input, bufferSize); chunk != null; chunk = read(input, bufferSize)) {
                int from = 0;
                if (first) {
                    first = false;
                    final Encoding.ByteOrderMark mark = Encoding.detectByteOrderMark(chunk);
                    if (mark != null) {
                        encoding = mark.encoding();
                        from = mark.length();
                        // The mark is part of the input: absolute offsets count its bytes.
                        read = from;
                    }
                }
                if (from >= chunk.length) {
                    continue;
                }
                level(roots, chunk, from, chunk.length, output, read,
                        rootIgnoreErrors, 0, rootDispatch);
                read += chunk.length - from;
            }
        } else {
            stream(roots, input, bufferSize, output, rootIgnoreErrors, rootDispatch);
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
                      final int depth,
                      final Dispatch dispatch) {
        if (dispatch == Dispatch.CLASSIFY) {
            return classify(templates, data, from, to, sink, inputBase, ignoreErrors, depth);
        }
        if (dispatch == Dispatch.ANY) {
            return anyLevel(templates, data, from, to, sink, inputBase, ignoreErrors, depth);
        }
        // Strict and lexer levels ask the anchored question of every template: the mode
        // carries the anchoring, whatever the pattern text says (D36).
        final boolean atCursor = dispatch == Dispatch.STRICT || dispatch == Dispatch.LEXER;
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
            int winner = -1;
            MatchResult match = null;
            for (int i = 0; i < templates.size(); i++) {
                if (!allowed[i]) {
                    continue;
                }
                final CompiledTemplate candidate = templates.get(i);
                final Template template = candidate.template();
                final int maxMatch = template.matchLimits().maxMatch();
                if (!template.consume() && maxMatch >= 0 && counts[i] >= maxMatch) {
                    continue;
                }
                final long timing = instrument.startTiming();
                final MatchResult attempt = match(candidate, data, cursor, to, atCursor);
                instrument.stopTiming(template.id(), timing, attempt != null);
                if (attempt == null) {
                    continue;
                }
                if (dispatch != Dispatch.LEXER) {
                    winner = i;
                    match = attempt;
                    break;
                }
                // Maximal munch: the longest match wins, ties to list order.
                if (match == null || attempt.advance() > match.advance()) {
                    winner = i;
                    match = attempt;
                }
            }
            if (match == null) {
                break;
            }
            final CompiledTemplate candidate = templates.get(winner);
            final Template template = candidate.template();

            // A zero-advance match in a consuming mode is a grammar bug, not a result:
            // with classify, the Peek step, and composition available, it has no innocent
            // reading left (D36). The body does not run — output from a match that cannot
            // move the level would be output from a mistake.
            if (match.advance() == 0) {
                messages.add(new Message(Severity.ERROR,
                        "Template '" + template.name() + "' matched without advancing at"
                        + " content offset " + (cursor - from + match.matchStart())
                        + "; the level cannot make progress."));
                break;
            }

            // An eater: advance, don't count (D36). No counters move, no stores clear,
            // no skip report — the eater is the authored skip.
            if (template.consume()) {
                processEater(candidate, match, sink, inputBase, ignoreErrors, depth);
                cursor += match.advance();
                matched = true;
                continue;
            }

            counts[winner]++;
            processMatch(candidate, match, counts[winner], data, cursor,
                    locate(inputBase, cursor - from), sink, ignoreErrors, depth, true);
            cursor += match.advance();
            matched = true;
            // The choice re-opens from the first template.
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


    /**
     * Process a counting winner: the first-match store clearing (E19), the skip report, the
     * engine's counter variables, and — when the match is wanted — captures, body and
     * instrumentation. One method because it is the hottest processing the engine does, and
     * every dispatch mode must share its compiled form rather than carrying a copy each.
     *
     * @param locateBase  the value such that {@code locateBase + match.matchStart()} is the
     *                    match's absolute input offset, or {@link Instrument#UNLOCATABLE}
     * @param reportSkips whether a skipped prefix earns a report — the ordered modes say so,
     *                    {@code any} never does, because DS3 gates the report on sequence order
     */
    private void processMatch(final CompiledTemplate candidate,
                              final MatchResult match,
                              final int matchCount,
                              final byte[] data,
                              final int cursor,
                              final long locateBase,
                              final OutputSink sink,
                              final boolean ignoreErrors,
                              final int depth,
                              final boolean reportSkips) {
        final Template template = candidate.template();
        if (matchCount == 1) {
            for (final CaptureBinding capture : template.captures()) {
                if (!(capture.select() instanceof CaptureBinding.CaptureSource.KeyValue)) {
                    vars.store(capture.name()).clear();
                }
            }
        }
        if (reportSkips && match.matchStart() > 0 && !ignoreErrors && !template.ignoreErrors()) {
            messages.add(new Message(Severity.ERROR,
                    "Expression '" + template.name()
                    + "' failed to match from the start of the content. Skipped: ["
                    + preview(data, cursor, cursor + match.matchStart()) + "]"));
        }
        vars.store(EngineVars.MATCH_INDEX).set(1, new TypedValue.Int(matchCount - 1));
        vars.store(EngineVars.MATCH_COUNT).set(1, new TypedValue.Int(matchCount));
        final boolean wanted = template.matchLimits().onlyMatch() == null
                               || template.matchLimits().onlyMatch().contains(matchCount);
        if (wanted) {
            final int contentGroup =
                    template.match() instanceof MatchExpression.Delimiter ? 1 : 0;
            final TypedValue content = match.group(contentGroup) != null
                    ? match.group(contentGroup)
                    : match.group(0);
            if (content != null && !content.isEmpty()) {
                instrument.onMatch(template.id(), template.name(),
                        locate(locateBase, match.matchStart()),
                        match.advance() - match.matchStart(), matchCount, depth);
                bindCaptures(candidate, match, matchCount);
                final long before = sink.position();
                body(candidate.body(), match, matchCount, content.asBytes(), sink,
                        locateBase, ignoreErrors, depth, effective(candidate));
                instrument.onOutput(template.id(), matchCount, before,
                        sink.position() - before);
            }
        }
    }

    /** An eater's win: the body runs — often to say what was swallowed — and nothing counts. */
    private void processEater(final CompiledTemplate candidate,
                              final MatchResult match,
                              final OutputSink sink,
                              final long locateBase,
                              final boolean ignoreErrors,
                              final int depth) {
        final int eaten = candidate.template().match() instanceof MatchExpression.Delimiter ? 1 : 0;
        final TypedValue swallowed = match.group(eaten) != null
                ? match.group(eaten)
                : match.group(0);
        if (swallowed != null && !swallowed.isEmpty()) {
            body(candidate.body(), match, 1, swallowed.asBytes(), sink,
                    locateBase, ignoreErrors, depth, effective(candidate));
        }
    }

    /**
     * The root level over a stream: DS3's sliding window, restored for E13.
     *
     * <p>The contract is DS3's and is deliberate: memory is bounded by the configured buffer
     * size, and a single match must fit the window's capacity or it cannot be made. What
     * slides is the window, not the contract — the unconsumed tail is kept, the window
     * refills behind it, and a record is never failed for merely straddling where a read
     * happened to end. Failures depend on record size, never on stream position.
     *
     * <p>The refill is lazy where DS3's is eager, because eager compaction would copy the
     * whole window per match: consumption advances an offset, and the window compacts and
     * refills only when a match runs into its edge with input still unread, or when a pass
     * finds nothing and more input might complete a record. A match that reaches the edge of
     * a <em>full</em> window is the truncation case, warned about exactly as before.
     *
     * <p>Match counts live for the whole stream, as DS3's do — a minimum-match requirement is
     * judged once at the end, not once per read.
     */
    private void stream(final List<CompiledTemplate> templates,
                        final InputStream input,
                        final int capacity,
                        final OutputSink sink,
                        final boolean ignoreErrors,
                        final Dispatch dispatch) {
        final boolean atCursor = dispatch == Dispatch.STRICT || dispatch == Dispatch.LEXER;
        // One byte of pushback: "the window is full" and "the stream is exhausted" can
        // coincide, and a refusal must not fire on the first when only the second is true.
        final PushbackInputStream source = new PushbackInputStream(input, 1);
        final byte[] window = new byte[capacity];
        int start = 0;
        int filled = fillAndBlankTail(source, window, 0);
        boolean eof = filled < capacity;
        long consumedTotal = 0;

        final Encoding.ByteOrderMark mark = Encoding.detectByteOrderMark(
                Arrays.copyOf(window, Math.min(filled, 4)));
        if (mark != null) {
            encoding = mark.encoding();
            start = Math.min(mark.length(), filled);
            // The mark is part of the input: absolute offsets count its bytes.
            consumedTotal = start;
        }

        final int[] counts = new int[templates.size()];
        final boolean[] allowed = new boolean[templates.size()];
        for (int i = 0; i < templates.size(); i++) {
            final Template template = templates.get(i).template();
            allowed[i] = template.guard() == null
                         || Conditions.evaluate(
                    template.guard(), MatchResult.empty(), 1, vars, encoding, compiled.patterns());
        }

        while (start < filled) {
            int winner = -1;
            MatchResult match = null;
            for (int i = 0; i < templates.size(); i++) {
                if (!allowed[i]) {
                    continue;
                }
                final CompiledTemplate candidate = templates.get(i);
                final Template template = candidate.template();
                final int maxMatch = template.matchLimits().maxMatch();
                if (!template.consume() && maxMatch >= 0 && counts[i] >= maxMatch) {
                    continue;
                }
                final long timing = instrument.startTiming();
                final MatchResult attempt = match(candidate, window, start, filled, atCursor);
                instrument.stopTiming(template.id(), timing, attempt != null);
                if (attempt == null) {
                    continue;
                }
                if (dispatch != Dispatch.LEXER) {
                    winner = i;
                    match = attempt;
                    break;
                }
                if (match == null || attempt.advance() > match.advance()) {
                    winner = i;
                    match = attempt;
                }
            }

            if (match == null) {
                // Nothing matches the window's front. If the window can still grow, the tail
                // may be a partial record — refill and try again; otherwise the stream is done
                // saying what it has to say.
                if (!eof && (start > 0 || filled < capacity)) {
                    filled = compact(window, start, filled);
                    start = 0;
                    final int before = filled;
                    filled += fillAndBlankTail(source, window, filled);
                    eof = filled < capacity;
                    if (filled > before) {
                        continue;
                    }
                }
                break;
            }

            final int end = start + match.advance();
            if (end == filled && !eof && !(start == 0 && filled == capacity)) {
                // The match ran into the window's edge with input still unread: it may have
                // matched a truncated view. Refill and let it try again against more.
                filled = compact(window, start, filled);
                start = 0;
                filled += fillAndBlankTail(source, window, filled);
                eof = filled < capacity;
                continue;
            }

            final CompiledTemplate candidate = templates.get(winner);
            final Template template = candidate.template();

            if (match.advance() == 0) {
                messages.add(new Message(Severity.ERROR,
                        "Template '" + template.name() + "' matched without advancing at"
                        + " input offset " + (consumedTotal + match.matchStart())
                        + "; the level cannot make progress."));
                break;
            }

            if (end == filled && !eof) {
                // For an end-of-input-anchored pattern this is not a maybe: every match ends
                // exactly at the region end, so a full-buffer match with input genuinely
                // unread has provably matched the buffer's edge, not the input's end. The
                // library publishes the fact (BytePattern.trailingAnchor(), single-sourced
                // from its parser, as D35 established for the leading anchor), so the refusal
                // is certain, not a sniff. Two audited subtleties shape this block: eof only
                // means the stream's -1 has not been read yet — an input of exactly the
                // buffer's capacity fills the window without observing it — so the one probe
                // byte settles that before refusing; and the probe can block on a live
                // source, so it runs only here, where a hard refusal actually needs the
                // certainty — the warning below stays hedged and never blocks. Both
                // ignore_errors levels downgrade the refusal to that warning, as the skip
                // error honours them; the port's kept limitation — processing ends after a
                // full-window match — is unchanged either way.
                if (!ignoreErrors && !template.ignoreErrors()
                    && candidate.match() instanceof CompiledMatch.Regex regex
                    && regex.pattern().trailingAnchor() == TrailingAnchor.INPUT) {
                    if (probeExhausted(source)) {
                        // The match genuinely ends at the input's end; nothing to say.
                        eof = true;
                    } else {
                        messages.add(new Message(Severity.ERROR,
                                "Template '" + template.name() + "' is anchored to the end of "
                                + "input but matched to the end of a full buffer with input "
                                + "still unread — the match is against the buffer's edge, not "
                                + "the input's end. Increase source buffer_size (currently "
                                + capacity + ")."));
                        break;
                    }
                } else {
                    messages.add(new Message(Severity.WARNING,
                            "Expressions consumed entire buffer (" + match.advance()
                            + " bytes). If data is truncated, increase source "
                            + "buffer_size (currently " + capacity + ")."));
                }
            }

            if (template.consume()) {
                processEater(candidate, match, sink, consumedTotal, ignoreErrors, 0);
                consumedTotal += match.advance();
                start = end;
                continue;
            }

            counts[winner]++;
            processMatch(candidate, match, counts[winner], window, start,
                    consumedTotal, sink, ignoreErrors, 0, true);
            consumedTotal += match.advance();
            start = end;
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
        if (!minMatchFailed && !ignoreErrors && hasContent(window, start, filled)) {
            messages.add(new Message(Severity.ERROR,
                    "Expressions failed to match all of the content. Unmatched: ["
                    + preview(window, start, filled) + "]"));
        }
    }

    /** Shift the live region to the window's front, returning the new fill level. */
    private static int compact(final byte[] window, final int start, final int filled) {
        System.arraycopy(window, start, window, 0, filled - start);
        return filled - start;
    }

    /**
     * Read into the window and blank whatever the previous buffer left beyond the new fill.
     * <p>
     * The matcher's contract is that the array holds the caller's data up to its length: it
     * probes one byte past the region to decide whether the region ends mid-character, which
     * is right for a slice of a full array and wrong for a reused window, where those bytes
     * are the last buffer's. Left stale, a continuation byte sitting at the fill point tells
     * the matcher a character continues past the region and a legal empty match at the tail
     * is refused — silently, and depending on what an earlier buffer happened to contain.
     * Blanking the tail makes the contract true. It costs a memset of whatever the read left
     * short, which is nothing until the last buffer of a stream, since {@link #fill} loops
     * until the window is full.
     */
    static int fillAndBlankTail(final InputStream input,
                                final byte[] window,
                                final int from) {
        final int got = fill(input, window, from);
        Arrays.fill(window, from + got, window.length, (byte) 0);
        return got;
    }

    /** Read until the window is full or the input ends; returns how many bytes arrived. */
    private static int fill(final InputStream input, final byte[] window, final int from) {
        try {
            int total = from;
            while (total < window.length) {
                final int got = input.read(window, total, window.length - total);
                if (got < 0) {
                    break;
                }
                total += got;
            }
            return total - from;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether the stream is exhausted, learned by one probe byte — pushed back if it
     * exists. May block on a live source, which is why the caller reserves it for the one
     * decision that needs the certainty. */
    private static boolean probeExhausted(final PushbackInputStream source) {
        try {
            final int probe = source.read();
            if (probe < 0) {
                return true;
            }
            source.unread(probe);
            return false;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * An {@code any} level — DS3's {@code matchOrder="any"}, ported for E18: templates search
     * a working copy of the content, and a match is <b>excised</b> — the span is removed and
     * the pieces either side close up — rather than the cursor moving past it. The prefix a
     * match skipped over is kept for later passes, which is the mode's whole point: order in
     * the data does not decide who wins, list order does.
     *
     * <p>There are no skip reports (DS3 gates them on sequence order), and after the first
     * excision nothing can be located in the input any more — positions in a stitched buffer
     * point at nothing — so attribution goes dark rather than lying.
     */
    private int anyLevel(final List<CompiledTemplate> templates,
                         final byte[] data,
                         final int from,
                         final int to,
                         final OutputSink sink,
                         final long inputBase,
                         final boolean ignoreErrors,
                         final int depth) {
        final byte[] work = Arrays.copyOfRange(data, from, to);
        int length = work.length;
        long base = inputBase;
        int excised = 0;

        final int[] counts = new int[templates.size()];
        final boolean[] allowed = new boolean[templates.size()];
        for (int i = 0; i < templates.size(); i++) {
            final Template template = templates.get(i).template();
            allowed[i] = template.guard() == null
                         || Conditions.evaluate(
                    template.guard(), MatchResult.empty(), 1, vars, encoding, compiled.patterns());
        }

        boolean matched = true;
        while (length > 0 && matched) {
            matched = false;
            for (int i = 0; i < templates.size(); i++) {
                if (!allowed[i]) {
                    continue;
                }
                final CompiledTemplate candidate = templates.get(i);
                final Template template = candidate.template();
                final int maxMatch = template.matchLimits().maxMatch();
                if (!template.consume() && maxMatch >= 0 && counts[i] >= maxMatch) {
                    continue;
                }
                final long timing = instrument.startTiming();
                final MatchResult match = match(candidate, work, 0, length, false);
                instrument.stopTiming(template.id(), timing, match != null);
                if (match == null) {
                    continue;
                }

                final int start = match.matchStart();
                final int end = match.advance();
                if (end <= start) {
                    // Break, not return: the level can say nothing more, but the minimum-match
                    // and unmatched-content reporting below still has its say — exactly as the
                    // equivalent break in the ordered modes reaches it.
                    messages.add(new Message(Severity.ERROR,
                            "Template '" + template.name() + "' matched without advancing at"
                            + " content offset " + start + "; the level cannot make progress."));
                    break;
                }

                if (!template.consume()) {
                    counts[i]++;
                    processMatch(candidate, match, counts[i], work, 0, base, sink,
                            ignoreErrors, depth, false);
                }

                // The excision: the matched span leaves, and the pieces close up. From here
                // on, offsets in the working buffer mean nothing in the input.
                System.arraycopy(work, end, work, start, length - end);
                length -= end - start;
                excised += end - start;
                base = Instrument.UNLOCATABLE;
                matched = true;
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
        if (!minMatchFailed && !ignoreErrors && hasContent(work, 0, length)) {
            messages.add(new Message(Severity.ERROR,
                    "Expressions failed to match all of the content. Unmatched: ["
                    + preview(work, 0, length) + "]"));
        }
        return excised;
    }

    /**
     * A classify level: one pass, every matching template runs, nothing consumes (D36).
     *
     * <p>Each template searches the whole region — classification asks "is this in there",
     * not "is this here" — and a match binds its captures and runs its body exactly once, at
     * match number 1. The cursor never moves and nothing is reported: a mode that consumes
     * nothing cannot leave anything unmatched.
     */
    private int classify(final List<CompiledTemplate> templates,
                         final byte[] data,
                         final int from,
                         final int to,
                         final OutputSink sink,
                         final long inputBase,
                         final boolean ignoreErrors,
                         final int depth) {
        for (final CompiledTemplate candidate : templates) {
            final Template template = candidate.template();
            if (template.guard() != null && !Conditions.evaluate(
                    template.guard(), MatchResult.empty(), 1, vars, encoding, compiled.patterns())) {
                continue;
            }
            final long timing = instrument.startTiming();
            final MatchResult match = match(candidate, data, from, to, false);
            instrument.stopTiming(template.id(), timing, match != null);
            if (match == null) {
                continue;
            }
            vars.store(EngineVars.MATCH_INDEX).set(1, new TypedValue.Int(0));
            vars.store(EngineVars.MATCH_COUNT).set(1, new TypedValue.Int(1));
            final int contentGroup = template.match() instanceof MatchExpression.Delimiter ? 1 : 0;
            final TypedValue content = match.group(contentGroup) != null
                    ? match.group(contentGroup)
                    : match.group(0);
            if (content != null && !content.isEmpty()) {
                instrument.onMatch(template.id(), template.name(),
                        locate(inputBase, match.matchStart()),
                        match.advance() - match.matchStart(), 1, depth);
                bindCaptures(candidate, match, 1);
                final long before = sink.position();
                body(candidate.body(), match, 1, content.asBytes(), sink,
                        inputBase, ignoreErrors, depth, effective(candidate));
                instrument.onOutput(template.id(), 1, before, sink.position() - before);
            }
        }
        return 0;
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
    private String preview(final byte[] data, final int from, final int to) {
        final int length = Math.min(200, to - from);
        return encoding.decode(data, from, length).replace("\n", "\\n")
               + (to - from > 200 ? "...TRUNCATED..." : "");
    }

    private MatchResult match(final CompiledTemplate compiledTemplate,
                              final byte[] data,
                              final int from,
                              final int to,
                              final boolean atCursor) {
        if (from >= to) {
            return null;
        }
        return switch (compiledTemplate.match()) {
            case CompiledMatch.Delimiter delimiter -> Splitter.split(data, from, to,
                    delimiter.delimiter(), delimiter.escape(),
                    delimiter.containerStart(), delimiter.containerEnd());
            case CompiledMatch.Regex regex -> regexMatch(regex, data, from, to, atCursor);
            case CompiledMatch.Progressive progressive ->
                    Steps.match(progressive.steps(), data, from, to, compiled.patterns(),
                            effective(compiledTemplate));
            case CompiledMatch.All ignored -> new MatchResult(
                    new TypedValue[]{TypedValue.of(Arrays.copyOfRange(data, from, to))}, to - from, 0);
            case CompiledMatch.Source ignored -> null;
            case CompiledMatch.Named ignored -> null;
        };
    }

    private static MatchResult regexMatch(final CompiledMatch.Regex regex,
                                          final byte[] data,
                                          final int from,
                                          final int to,
                                          final boolean atCursor) {
        // The node owns its matcher (D35) and asks the question the library's published
        // anchor fact licenses: for an input-anchored pattern the anchored and unanchored
        // questions provably agree, so the node asks the one with the bare prologue. The
        // fact's single source is the library's parser — nothing here reads pattern text.
        final ByteMatcher matcher = regex.matcher();
        final Anchoring question = atCursor ? Anchoring.ANCHORED : regex.anchoring();
        if (!matcher.match(data, from, to, question)) {
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
        final Encoding contentEncoding = effective(compiledTemplate);
        for (final CaptureBinding capture : compiledTemplate.template().captures()) {
            final TypedValue value = switch (capture.select()) {
                // A capture is a slice of the input, and is stored in the engine's own form so
                // that everything reading it later can assume UTF-8.
                case CaptureBinding.CaptureSource.Group group -> normalise(match.group(group.group()), contentEncoding);
                case CaptureBinding.CaptureSource.Step step -> normalise(match.group(step.index() + 1),
                        contentEncoding);
                case CaptureBinding.CaptureSource.Select select -> {
                    final byte[] bytes = Refs.resolve(select.select(), match, matchCount, vars, contentEncoding);
                    yield bytes == null ? null : TypedValue.of(bytes);
                }
                case CaptureBinding.CaptureSource.Field ignored -> null;
                case CaptureBinding.CaptureSource.KeyValue keyValue -> {
                    final String key = Refs.resolveText(keyValue.keyRef(), match, matchCount, vars, contentEncoding);
                    if (key != null) {
                        final byte[] bytes = Refs.resolve(keyValue.valueRef(), match, matchCount, vars,
                                contentEncoding);
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

    /**
     * The encoding a template's captured bytes are in: its own declared override (E3), else
     * whatever the run settled on — declaration or byte-order mark.
     */
    private Encoding effective(final CompiledTemplate candidate) {
        return Encoding.resolve(candidate.encoding(), encoding);
    }

    /** Convert a captured value into the engine's internal form, which is UTF-8. */
    private TypedValue normalise(final TypedValue value, final Encoding contentEncoding) {
        if (value == null || contentEncoding.isUtf8Compatible() || !(value instanceof TypedValue.Bytes)) {
            return value;
        }
        return TypedValue.of(Refs.bytes(value, contentEncoding));
    }

    private void body(final List<CompiledOp> ops,
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
                    structure(() -> sink.startElement(value.name(), value.namespace()),
                            "element '" + value.name() + "'");
                    body(value.body(), match, matchCount, content, sink,
                            inputBase, ignoreErrors, depth, contentEncoding);
                    structure(sink::endElement, "element '" + value.name() + "'");
                }
                case CompiledOp.Attribute value -> {
                    structure(() -> sink.startAttribute(value.name()), "attribute '" + value.name() + "'");
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
                    final java.util.Map<String, Filed> index =
                            keyIndexes.getOrDefault(value.key(), java.util.Map.of());
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
                                        stroom.shapeshifter.engine.config.Cast.DATE);
                        result = Dates.parse(value.parser(), input.asString(),
                                (TypedValue.Instant) reference);
                    }
                    emit(result, value.name(), matchCount, sink);
                }
                case CompiledOp.EmitError value -> {
                    final String text = CompiledRefs.resolveText(
                            value.message(), match, matchCount, vars, encoding);
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
    private java.util.Map<String, Filed> index(final String select,
                                               final CompiledRef groupBy,
                                               final MatchResult match,
                                               final int matchCount,
                                               final Encoding contentEncoding) {
        final java.util.Map<String, Filed> members = new java.util.LinkedHashMap<>();
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
        final Store store = stores.getFirst();

        // The same index a key builds (design/16 §8): grouping walks every entry of it,
        // a key reaches one entry by value. One builder, two readings.
        final java.util.Map<String, Filed> members =
                index(op.select(), op.groupBy(), match, matchCount, contentEncoding);
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
        final java.util.Set<String> seen = new java.util.LinkedHashSet<>();
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

        final String mode = directive.templateRef() != null
                ? "__rec_" + directive.templateRef()
                : directive.mode();
        // A compile-time fact read as a field — nothing filters the template list per call.
        final List<CompiledTemplate> candidates = compiled.templates(mode);

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
                inheritedIgnoreErrors || directive.ignoreErrors(), depth + 1, op.dispatch());

        if (recursive) {
            vars.pop();
        }
    }

    /** An absolute input offset, unless the base says the content cannot be located. */
    private static long locate(final long base, final int offset) {
        return base >= Instrument.UNLOCATABLE ? Instrument.UNLOCATABLE : base + offset;
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
