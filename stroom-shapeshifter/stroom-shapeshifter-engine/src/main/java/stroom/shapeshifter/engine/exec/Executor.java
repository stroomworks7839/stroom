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
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.text.Encoding;
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
                .filter(t -> java.util.Objects.equals(t.template().mode(), streamMode))
                .toList();

        // The root level's gate is the configuration's own ignoreErrors — DS3's flag on the
        // dataSplitter element itself — or the document template's directive saying so.
        final boolean rootIgnoreErrors = compiled.project().source().ignoreErrors()
                || (streamDirective != null && streamDirective.ignoreErrors());

        final MatchResult nothing = MatchResult.empty();
        List<CompiledOp> prologue = List.of();
        List<CompiledOp> epilogue = List.of();
        if (source != null) {
            final List<CompiledOp> body = source.body();
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
            body(prologue, nothing, 0, new byte[0], output, 0L, rootIgnoreErrors, 0, encoding);
        }

        final int bufferSize = wholeBuffer
                ? Integer.MAX_VALUE
                : Math.max(1, compiled.project().source().bufferSize());
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

        if (!epilogue.isEmpty()) {
            body(epilogue, nothing, 0, new byte[0], output, 0L, rootIgnoreErrors, 0, encoding);
        }
    }

    private static ApplyDirective applyDirective(final Template template) {
        for (final OutputNode node : template.body()) {
            if (node instanceof OutputNode.ApplyTemplates apply) {
                return apply.directive();
            }
        }
        return null;
    }

    private static int indexOfApply(final List<CompiledOp> body) {
        for (int i = 0; i < body.size(); i++) {
            if (body.get(i) instanceof CompiledOp.Apply) {
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
            {
                final CompiledTemplate candidate = templates.get(winner);
                final Template template = candidate.template();

                // A zero-advance match in a consuming mode is a grammar bug, not a result:
                // with classify, the Peek step, and composition available, it has no innocent
                // reading left (D36). The body does not run — output from a match that cannot
                // move the level would be output from a mistake.
                if (match.advance() == 0) {
                    messages.add(new Message(Severity.ERROR,
                            "Template '" + template.name() + "' matched without advancing at"
                            + " offset " + (cursor - from + match.matchStart())
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
                final int matchCount = counts[winner];
                processMatch(candidate, match, matchCount, data, cursor,
                        locate(inputBase, cursor - from), sink, ignoreErrors, depth);
                cursor += match.advance();
                matched = true;
                // The choice re-opens from the first template.
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


    /**
     * Process a counting winner: the first-match store clearing (E19), the skip report, the
     * engine's counter variables, and — when the match is wanted — captures, body and
     * instrumentation. One method because it is the hottest processing the engine does, and
     * both the nested level and the root stream must share its compiled form rather than
     * carrying a copy each.
     *
     * @param locateBase the value such that {@code locateBase + match.matchStart()} is the
     *                   match's absolute input offset, or {@link Instrument#UNLOCATABLE}
     */
    private void processMatch(final CompiledTemplate candidate,
                              final MatchResult match,
                              final int matchCount,
                              final byte[] data,
                              final int cursor,
                              final long locateBase,
                              final OutputSink sink,
                              final boolean ignoreErrors,
                              final int depth) {
        final Template template = candidate.template();
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
        vars.store(MATCH_INDEX).set(1, new TypedValue.Int(matchCount - 1));
        vars.store(MATCH_COUNT).set(1, new TypedValue.Int(matchCount));
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
                        locate(locateBase, match.matchStart()), match.advance(), matchCount, depth);
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
        final byte[] window = new byte[capacity];
        int start = 0;
        int filled = fill(input, window, 0);
        boolean eof = filled < capacity;
        long consumedTotal = 0;

        final Encoding.ByteOrderMark mark = Encoding.detectByteOrderMark(
                Arrays.copyOf(window, Math.min(filled, 4)));
        if (mark != null) {
            encoding = mark.encoding();
            start = Math.min(mark.length(), filled);
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
                    filled += fill(input, window, filled);
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
                filled += fill(input, window, filled);
                eof = filled < capacity;
                continue;
            }

            final CompiledTemplate candidate = templates.get(winner);
            final Template template = candidate.template();

            if (match.advance() == 0) {
                messages.add(new Message(Severity.ERROR,
                        "Template '" + template.name() + "' matched without advancing at"
                        + " offset " + (consumedTotal + match.matchStart())
                        + "; the level cannot make progress."));
                break;
            }

            if (end == filled && !eof) {
                messages.add(new Message(Severity.WARNING, "Expressions consumed entire buffer ("
                                                           + match.advance()
                                                           + " bytes). If data is truncated, increase source "
                                                           + "buffer_size (currently " + capacity + ")."));
            }

            if (template.consume()) {
                processEater(candidate, match, sink, consumedTotal, ignoreErrors, 0);
                consumedTotal += match.advance();
                start = end;
                continue;
            }

            counts[winner]++;
            processMatch(candidate, match, counts[winner], window, start,
                    consumedTotal, sink, ignoreErrors, 0);
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
                    messages.add(new Message(Severity.ERROR,
                            "Template '" + template.name() + "' matched without advancing at"
                            + " offset " + start + "; the level cannot make progress."));
                    return excised;
                }

                if (!template.consume()) {
                    counts[i]++;
                    final int matchCount = counts[i];
                    if (matchCount == 1) {
                        for (final CaptureBinding capture : template.captures()) {
                            if (!(capture.select() instanceof CaptureBinding.CaptureSource.KeyValue)) {
                                vars.store(capture.name()).clear();
                            }
                        }
                    }
                    vars.store(MATCH_INDEX).set(1, new TypedValue.Int(matchCount - 1));
                    vars.store(MATCH_COUNT).set(1, new TypedValue.Int(matchCount));
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
                                    locate(base, start), end - start, matchCount, depth);
                            bindCaptures(candidate, match, matchCount);
                            final long before = sink.position();
                            body(candidate.body(), match, matchCount, content.asBytes(), sink,
                                    base, ignoreErrors, depth, effective(candidate));
                            instrument.onOutput(template.id(), matchCount, before,
                                    sink.position() - before);
                        }
                    }
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
            vars.store(MATCH_INDEX).set(1, new TypedValue.Int(0));
            vars.store(MATCH_COUNT).set(1, new TypedValue.Int(1));
            final int contentGroup = template.match() instanceof MatchExpression.Delimiter ? 1 : 0;
            final TypedValue content = match.group(contentGroup) != null
                    ? match.group(contentGroup)
                    : match.group(0);
            if (content != null && !content.isEmpty()) {
                instrument.onMatch(template.id(), template.name(),
                        locate(inputBase, match.matchStart()), match.advance(), 1, depth);
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
    private static String preview(final byte[] data, final int from, final int to) {
        final int length = Math.min(200, to - from);
        return new String(data, from, length, StandardCharsets.UTF_8).replace("\n", "\\n")
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
        final stroom.shapeshifter.regex.Anchoring question = atCursor
                ? stroom.shapeshifter.regex.Anchoring.ANCHORED
                : regex.anchoring();
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
                    emit(mapped == null ? "" : mapped, value.name(), matchCount, sink);
                }
                case CompiledOp.Transform value -> transform(value.select(), value.name(), match,
                        matchCount, sink, value.function(), contentEncoding);
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

    private boolean test(final stroom.shapeshifter.engine.config.Condition condition,
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
    private void transform(final List<CompiledRef> select,
                           final String name,
                           final MatchResult match,
                           final int matchCount,
                           final OutputSink sink,
                           final java.util.function.Function<List<String>, String> function,
                           final Encoding contentEncoding) {
        final List<String> inputs = new ArrayList<>(select.size());
        for (final CompiledRef ref : select) {
            final String resolved = CompiledRefs.resolveText(ref, match, matchCount, vars, contentEncoding);
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

        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
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

        vars.push();
        for (final CompiledOp.Arg arg : value.args()) {
            final byte[] resolved = CompiledRefs.resolve(arg.value(), match, matchCount, vars, contentEncoding);
            if (resolved != null) {
                vars.store(arg.name()).set(1, TypedValue.of(resolved));
            }
        }
        for (final Template.ParamDecl declared : target.template().param()) {
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
