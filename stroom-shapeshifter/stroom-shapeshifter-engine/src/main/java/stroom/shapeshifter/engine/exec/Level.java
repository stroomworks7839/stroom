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
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.EngineVars;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.match.MatchResult;
import stroom.shapeshifter.engine.match.Splitter;
import stroom.shapeshifter.engine.match.Steps;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;

import java.util.Arrays;
import java.util.List;

/**
 * Dispatching one level — the templates of a mode — against one region of content, or the
 * root level against the input window.
 *
 * <p>The ordered modes are iterated ordered choice, DS3's own model (D34): each pass, the
 * first template that matches wins one match, and the choice re-opens from the first. Strict
 * and lexer modes ask the anchored question (D36); the lexer takes the longest. A classify
 * level runs every matching template once and consumes nothing; an {@code any} level searches
 * a working copy and excises what it matches. Skipping is reported, never silent. What a
 * counted match does — clears first-match stores (E19), reports a skipped prefix, sets the
 * engine's counters — is one method the consuming modes share, and what a wanted match with
 * content does — binds captures, runs its body through the body interpreter, measures the
 * output — is one method every mode shares, the classify mode included.
 */
final class Level {

    private final CompiledProject compiled;
    private final Instrument instrument;
    private final List<Message> messages;
    private final VarRegistry vars;
    private final FunctionRuntime functions;
    private final Body body;

    /** The run's encoding in force, taken on every entry; the same value throughout a run. */
    private Encoding encoding;

    Level(final CompiledProject compiled,
          final Instrument instrument,
          final List<Message> messages,
          final VarRegistry vars,
          final FunctionRuntime functions,
          final Body body) {
        this.compiled = compiled;
        this.instrument = instrument;
        this.messages = messages;
        this.vars = vars;
        this.functions = functions;
        this.body = body;
    }

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
     * @param encoding     the run's encoding in force, which the level reads guards, previews
     *                     and captures with; the same value on every nested dispatch of a run
     */
    void dispatch(final List<CompiledTemplate> templates,
                  final byte[] data,
                  final int from,
                  final int to,
                  final OutputSink sink,
                  final long inputBase,
                  final boolean ignoreErrors,
                  final int depth,
                  final Dispatch dispatch,
                  final Encoding encoding) {
        this.encoding = encoding;
        if (dispatch == Dispatch.CLASSIFY) {
            classify(templates, data, from, to, sink, inputBase, ignoreErrors, depth);
            return;
        }
        if (dispatch == Dispatch.ANY) {
            anyLevel(templates, data, from, to, sink, inputBase, ignoreErrors, depth);
            return;
        }
        // Strict and lexer levels ask the anchored question of every template: the mode
        // carries the anchoring, whatever the pattern text says (D36).
        final boolean atCursor = dispatch == Dispatch.STRICT || dispatch == Dispatch.LEXER;
        final int[] counts = new int[templates.size()];

        final boolean[] allowed = guards(templates);

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

            if (match.advance() == 0) {
                noProgress(template, "content", cursor - from + match.matchStart());
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

        report(templates, counts, ignoreErrors, data, cursor, to);
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
        if (depth == 0) {
            functions.countRecord();
        }
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
            runBody(candidate, match, matchCount, sink, locateBase, ignoreErrors, depth);
        }
    }

    /** An eater's win: the body runs — often to say what was swallowed — and nothing counts. */
    private void processEater(final CompiledTemplate candidate,
                              final MatchResult match,
                              final OutputSink sink,
                              final long locateBase,
                              final boolean ignoreErrors,
                              final int depth) {
        final TypedValue swallowed = content(candidate.template(), match);
        if (swallowed != null && !swallowed.isEmpty()) {
            body.body(candidate.body(), match, 1, swallowed.asBytes(), sink,
                    locateBase, ignoreErrors, depth, effective(candidate));
        }
    }

    /**
     * The root level over a stream, through the {@link InputWindow} (E13, design 23): the
     * level asks the window to refill when a match runs into its edge with input still unread,
     * or when a pass finds nothing and more input might complete a record. A match that
     * reaches the edge of a <em>full</em> window is the truncation case: FATAL, and the run
     * ends (design 23 §5.1).
     *
     * <p>Match counts live for the whole stream, as DS3's do — a minimum-match requirement is
     * judged once at the end, not once per read.
     *
     * <p>The winner loop below is {@link #dispatch}'s, written out a second time. Folding the
     * two into one shared method cost 4 to 5% on the regex_lines and ausearch rows (design 27,
     * phase 3 gate), so the duplication is the gate's decision, not an oversight.
     *
     * @param window   the input window, opened and its byte-order mark already applied by the run
     * @param encoding the run's encoding in force, as for {@link #dispatch}
     */
    void stream(final List<CompiledTemplate> templates,
                final InputWindow window,
                final OutputSink sink,
                final boolean ignoreErrors,
                final Dispatch dispatch,
                final Encoding encoding) {
        this.encoding = encoding;
        final boolean atCursor = dispatch == Dispatch.STRICT || dispatch == Dispatch.LEXER;

        final int[] counts = new int[templates.size()];
        final boolean[] allowed = guards(templates);

        while (!window.isEmpty()) {
            final int start = window.start();
            final int filled = window.filled();
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
                final MatchResult attempt = match(candidate, window.bytes(), start, filled, atCursor);
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
                // Nothing matches the window's front. If the window can still grow, the tail
                // may be a partial record — refill and try again; otherwise the stream is done
                // saying what it has to say.
                if (window.canGrow() && window.refill()) {
                    continue;
                }
                break;
            }

            final int end = start + match.advance();
            if (window.edgeCanRecede(end)) {
                // The match ran into the window's edge with input still unread: it may have
                // matched a truncated view. Refill and let it try again against more.
                window.refill();
                continue;
            }

            final CompiledTemplate candidate = templates.get(winner);
            final Template template = candidate.template();

            if (match.advance() == 0) {
                noProgress(template, "input", window.offsetOf(start + match.matchStart()));
                break;
            }

            if (window.reachesEdge(end)) {
                // The match reached the end of a full window — edgeCanRecede has already said
                // there is nothing to compact away. If the stream is exhausted the
                // record simply ended where the input did; the probe byte settles that, since
                // eof only means the -1 has not been read yet (an input of exactly the window's
                // capacity fills it without observing the end). Otherwise the record is larger
                // than the window, whatever its pattern: it has matched the buffer's edge, not
                // the data's shape, and nothing correct can follow — the next window would
                // begin in its middle — so it is fatal, for every root match and regardless of
                // ignore_errors, which is for skipping past something, not for having nowhere
                // to go (design 23 §5.1, ruled 2026-09-04; DS3's grow-and-recover not adopted).
                if (!window.probeExhausted()) {
                    messages.add(new Message(Severity.FATAL,
                            "Template '" + template.name() + "' consumed the entire buffer ("
                            + match.advance() + " bytes) with input still unread: the record is "
                            + "larger than source buffer_size (currently " + window.capacity()
                            + "). Increase buffer_size."));
                    throw new AbortRun();
                }
            }

            if (template.consume()) {
                processEater(candidate, match, sink, window.consumed(), ignoreErrors, 0);
                window.consume(match.advance());
                continue;
            }

            counts[winner]++;
            processMatch(candidate, match, counts[winner], window.bytes(), start,
                    window.consumed(), sink, ignoreErrors, 0, true);
            window.consume(match.advance());
        }

        report(templates, counts, ignoreErrors, window.bytes(), window.start(), window.filled());
    }

    /**
     * An {@code any} level — DS3's {@code matchOrder="any"} (E18): templates search
     * a working copy of the content, and a match is <b>excised</b> — the span is removed and
     * the pieces either side close up — rather than the cursor moving past it. The prefix a
     * match skipped over is kept for later passes, which is the mode's whole point: order in
     * the data does not decide who wins, list order does.
     *
     * <p>There are no skip reports (DS3 gates them on sequence order), and after the first
     * excision nothing can be located in the input any more — positions in a stitched buffer
     * point at nothing — so attribution goes dark rather than lying.
     */
    private void anyLevel(final List<CompiledTemplate> templates,
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

        final int[] counts = new int[templates.size()];
        final boolean[] allowed = guards(templates);

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
                    noProgress(template, "content", start);
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
                base = Instrument.UNLOCATABLE;
                matched = true;
                break;
            }
        }

        report(templates, counts, ignoreErrors, work, 0, length);
    }

    /**
     * A classify level: one pass, every matching template runs, nothing consumes (D36).
     *
     * <p>Each template searches the whole region — classification asks "is this in there",
     * not "is this here" — and a match binds its captures and runs its body exactly once, at
     * match number 1. The cursor never moves and nothing is reported: a mode that consumes
     * nothing cannot leave anything unmatched.
     */
    private void classify(final List<CompiledTemplate> templates,
                         final byte[] data,
                         final int from,
                         final int to,
                         final OutputSink sink,
                         final long inputBase,
                         final boolean ignoreErrors,
                         final int depth) {
        // Guards once on the way in, as every mode (design 27 ruling 11): a guard read after an
        // earlier sibling's match would see that sibling's counters and captures.
        final boolean[] allowed = guards(templates);
        for (int i = 0; i < templates.size(); i++) {
            if (!allowed[i]) {
                continue;
            }
            final CompiledTemplate candidate = templates.get(i);
            final Template template = candidate.template();
            final long timing = instrument.startTiming();
            final MatchResult match = match(candidate, data, from, to, false);
            instrument.stopTiming(template.id(), timing, match != null);
            if (match == null) {
                continue;
            }
            // A record is a top-level match, here as in processMatch: counted before the
            // content check, so that what a record's functions see is the same either way.
            if (depth == 0) {
                functions.countRecord();
            }
            vars.store(EngineVars.MATCH_INDEX).set(1, new TypedValue.Int(0));
            vars.store(EngineVars.MATCH_COUNT).set(1, new TypedValue.Int(1));
            runBody(candidate, match, 1, sink, inputBase, ignoreErrors, depth);
        }
    }

    /**
     * Guards, evaluated once on the way in — not per pass. The distinction is load-bearing: a
     * guard reads scope, and scope includes the parent's match counter, which DS3-style
     * onlyMatch guards compare against. Once matching starts, each winner overwrites that
     * counter with its own count, so a guard re-read mid-level would compare a template
     * against itself. DS3 gets this for free by passing the parent's count down as a
     * parameter; evaluating here, while the scope still describes the parent, is the same
     * thing said with variables.
     */
    private boolean[] guards(final List<CompiledTemplate> templates) {
        final boolean[] allowed = new boolean[templates.size()];
        for (int i = 0; i < templates.size(); i++) {
            final Template template = templates.get(i).template();
            allowed[i] = template.guard() == null
                         || Conditions.evaluate(
                    template.guard(), MatchResult.empty(), 1, vars, encoding, compiled.patterns());
        }
        return allowed;
    }

    /**
     * A zero-advance match in a consuming mode is a grammar bug, not a result: with classify,
     * the Peek step, and composition available, it has no innocent reading left (D36). The
     * body does not run — output from a match that cannot move the level would be output
     * from a mistake.
     *
     * @param where "content" for an offset within the region, "input" for an absolute one
     */
    private void noProgress(final Template template, final String where, final long offset) {
        messages.add(new Message(Severity.ERROR,
                "Template '" + template.name() + "' matched without advancing at "
                + where + " offset " + offset + "; the level cannot make progress."));
    }

    /**
     * What a level says at its end: every template short of its minimum match count, and then
     * content no pass could match, reported once for the level — unless a minimum-match error
     * already explained the same failure. Stroom's own record fixes both halves of this rule:
     * 005's fully-unmatched line is reported even though nothing matched, and 014's is not,
     * because its three minMatch errors already said what was wrong.
     */
    private void report(final List<CompiledTemplate> templates,
                        final int[] counts,
                        final boolean ignoreErrors,
                        final byte[] data,
                        final int from,
                        final int to) {
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
        if (!minMatchFailed && !ignoreErrors && hasContent(data, from, to)) {
            messages.add(new Message(Severity.ERROR,
                    "Expressions failed to match all of the content. Unmatched: ["
                    + preview(data, from, to) + "]"));
        }
    }

    /**
     * The content a match hands its body: a delimiter template's is the field, group 1, and
     * every other template's is the whole match, group 0 — the group that carries the
     * delimiter too, which is why a delimiter's group 0 is not it.
     */
    private static TypedValue content(final Template template, final MatchResult match) {
        final int contentGroup = template.match() instanceof MatchExpression.Delimiter ? 1 : 0;
        return match.group(contentGroup) != null ? match.group(contentGroup) : match.group(0);
    }

    /** A wanted match with content: instrumented, its captures bound, its body run, its output measured. */
    private void runBody(final CompiledTemplate candidate,
                         final MatchResult match,
                         final int matchCount,
                         final OutputSink sink,
                         final long locateBase,
                         final boolean ignoreErrors,
                         final int depth) {
        final Template template = candidate.template();
        final TypedValue content = content(template, match);
        if (content == null || content.isEmpty()) {
            return;
        }
        instrument.onMatch(template.id(), template.name(), locate(locateBase, match.matchStart()),
                match.advance() - match.matchStart(), matchCount, depth);
        bindCaptures(candidate, match, matchCount);
        final long before = sink.position();
        body.body(candidate.body(), match, matchCount, content.asBytes(), sink,
                locateBase, ignoreErrors, depth, effective(candidate));
        instrument.onOutput(template.id(), matchCount, before, sink.position() - before, sink.unit());
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
                case CaptureBinding.CaptureSource.Field ignored -> throw new IllegalStateException(
                        "Field capture sources are refused at compile time");
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

    /** An absolute input offset, unless the base says the content cannot be located. */
    static long locate(final long base, final int offset) {
        return base >= Instrument.UNLOCATABLE ? Instrument.UNLOCATABLE : base + offset;
    }
}
