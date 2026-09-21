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

import stroom.shapeshifter.config.BinaryCast;
import stroom.shapeshifter.config.Cast;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.Dispatch;
import stroom.shapeshifter.config.Severity;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.engine.Instrument;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.graph.CompiledCapture;
import stroom.shapeshifter.engine.graph.CompiledMatch;
import stroom.shapeshifter.engine.graph.CompiledTemplate;
import stroom.shapeshifter.engine.graph.VarName;
import stroom.shapeshifter.engine.match.MatchResult;
import stroom.shapeshifter.engine.match.Splitter;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.value.ByteSource;
import stroom.shapeshifter.engine.value.Comparisons;
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
 * counted match does — restarts its captures' lists on a first match (E19), reports a skipped prefix, sets the
 * engine's counters — is one method the consuming modes share, and what a wanted match with
 * content does — binds captures, runs its body through the body interpreter, measures the
 * output — is one method every mode shares, the classify mode included.
 */
final class Level {

    private final Instrument instrument;
    private final List<Message> messages;
    private final VarRegistry vars;
    private final FunctionRuntime functions;
    private final Body body;

    /** The run's encoding in force, taken on every entry; the same value throughout a run. */
    private Encoding encoding;

    // The frame tree as it opens (design 18 §7 G1, G2): frames numbered from one across the
    // run; per depth, the frame whose body is running there, where its content starts in its
    // array, and whether the level's bytes are located in the input. Sized for a deep dispatch
    // and grown if one goes deeper; the arrays are per Level, and a Level is per run.
    private long nextFrame;
    private long[] frameAt = new long[16];
    private int[] frameContentStart = new int[16];
    private boolean[] levelLocatable = new boolean[16];

    Level(final Instrument instrument,
          final List<Message> messages,
          final VarRegistry vars,
          final FunctionRuntime functions,
          final Body body) {
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
     * @param encoding     the run's encoding in force, which the level decodes previews with and
     *                     resolves each template's effective encoding against — the tag on every
     *                     group it matches; the same value on every nested dispatch of a run
     */
    void dispatch(final CompiledTemplate[] templates,
                  final byte[] data,
                  final int from,
                  final int to,
                  final Output out,
                  final long inputBase,
                  final boolean ignoreErrors,
                  final int depth,
                  final Dispatch dispatch,
                  final Encoding encoding,
                  final ByteSource source) {
        this.encoding = encoding;
        enterLevel(depth, inputBase);
        if (dispatch == Dispatch.CLASSIFY) {
            classify(templates, data, from, to, out, inputBase, ignoreErrors, depth, source);
            return;
        }
        if (dispatch == Dispatch.ANY) {
            anyLevel(templates, data, from, to, out, inputBase, ignoreErrors, depth);
            return;
        }
        // Strict and lexer levels ask the anchored question of every template: the mode
        // carries the anchoring, whatever the pattern text says (D36).
        final boolean atCursor = dispatch == Dispatch.STRICT || dispatch == Dispatch.LEXER;
        final int[] counts = new int[templates.length];

        final boolean[] allowed = guards(templates, depth);

        int cursor = from;

        while (cursor < to) {
            int winner = -1;
            MatchResult match = null;
            for (int i = 0; i < templates.length; i++) {
                if (allowed != null && !allowed[i]) {
                    continue;
                }
                final CompiledTemplate candidate = templates[i];
                final int maxMatch = candidate.maxMatch();
                if (!candidate.consume() && maxMatch >= 0 && counts[i] >= maxMatch) {
                    continue;
                }
                final long timing = instrument.startTiming();
                final MatchResult attempt = match(candidate, data, cursor, to, atCursor, source);
                attempted(depth, candidate.template().id(), timing, attempt != null,
                        locate(inputBase, cursor - from), cursor);
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
            final CompiledTemplate candidate = templates[winner];
            final Template template = candidate.template();

            if (match.advance() == 0) {
                noProgress(template, "content", cursor - from + match.matchStart());
                break;
            }

            // An eater: advance, don't count (D36). No counters move, no lists restart,
            // no skip report — the eater is the authored skip.
            if (candidate.consume()) {
                processEater(candidate, match, out, inputBase, ignoreErrors, depth);
                cursor += match.advance();
                continue;
            }

            counts[winner]++;
            processMatch(candidate, match, counts[winner], data, cursor,
                    locate(inputBase, cursor - from), out, ignoreErrors, depth, true);
            cursor += match.advance();
            // The choice re-opens from the first template.
        }

        report(templates, counts, ignoreErrors, data, cursor, to);
    }

    /**
     * Process a counting winner: the first-match restart of its captures' lists (E19), the skip report, the
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
                              final Output out,
                              final boolean ignoreErrors,
                              final int depth,
                              final boolean reportSkips) {
        if (depth == 0) {
            functions.countRecord();
        }
        final Template template = candidate.template();
        if (matchCount == 1) {
            // The lists this template's captures fill restart with its first match of a
            // sequence — DS3's rule (E19), kept as the capture's own; which names are lists was
            // settled when the template compiled.
            for (final VarName name : candidate.clearNames()) {
                vars.clear(name);
            }
        }
        if (reportSkips && match.matchStart() > 0 && !ignoreErrors && !template.ignoreErrors()) {
            messages.add(new Message(Severity.ERROR,
                    "Expression '" + template.name()
                    + "' failed to match from the start of the content. Skipped: ["
                    + preview(data, cursor, cursor + match.matchStart()) + "]"));
        }
        vars.frames().match(matchCount);
        // A linear scan over an int[], not a Set.contains: this runs on every match, and the
        // set is a handful of indices at most — usually one (design 33 §11).
        final int[] onlyMatch = candidate.onlyMatch();
        boolean wanted = onlyMatch == null;
        if (!wanted) {
            for (final int only : onlyMatch) {
                if (only == matchCount) {
                    wanted = true;
                    break;
                }
            }
        }
        if (wanted) {
            runBody(candidate, match, matchCount, out, locateBase, ignoreErrors, depth);
        }
    }

    /** An eater's win: the body runs — often to say what was swallowed — and nothing counts. */
    private void processEater(final CompiledTemplate candidate,
                              final MatchResult match,
                              final Output out,
                              final long locateBase,
                              final boolean ignoreErrors,
                              final int depth) {
        final TypedValue swallowed = content(candidate, match);
        if (swallowed != null && !swallowed.isEmpty()) {
            enter(candidate);
            body.body(candidate.body(), match, 1, swallowed, out,
                    locateBase, ignoreErrors, depth);
            exit(candidate);
        }
    }

    /**
     * Enter a template's execution: its declarations come into being, unset (design 35 §4).
     * Only a template that declares pays anything — the field templates of a record push
     * nothing — and what an entry costs is an index and a few array writes per declared name.
     */
    private void enter(final CompiledTemplate candidate) {
        if (candidate.declared().length > 0) {
            vars.push(candidate.declared(), candidate.initial());
        }
    }

    /** Leave it: every declared name restores to what it held outside, which was unset. */
    private void exit(final CompiledTemplate candidate) {
        if (candidate.declared().length > 0) {
            vars.pop();
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
    void stream(final CompiledTemplate[] templates,
                final InputWindow window,
                final Output out,
                final boolean ignoreErrors,
                final Dispatch dispatch,
                final Encoding encoding) {
        this.encoding = encoding;
        final boolean atCursor = dispatch == Dispatch.STRICT || dispatch == Dispatch.LEXER;

        final int[] counts = new int[templates.length];
        final boolean[] allowed = guards(templates, 0);
        enterLevel(0, window.consumed());

        while (!window.isEmpty()) {
            final int start = window.start();
            final int filled = window.filled();
            int winner = -1;
            MatchResult match = null;
            for (int i = 0; i < templates.length; i++) {
                if (allowed != null && !allowed[i]) {
                    continue;
                }
                final CompiledTemplate candidate = templates[i];
                final int maxMatch = candidate.maxMatch();
                if (!candidate.consume() && maxMatch >= 0 && counts[i] >= maxMatch) {
                    continue;
                }
                final long timing = instrument.startTiming();
                final MatchResult attempt = match(candidate, window.bytes(), start, filled, atCursor,
                        window.source());
                attempted(0, candidate.template().id(), timing, attempt != null, window.offsetOf(start), start);
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

            final CompiledTemplate candidate = templates[winner];
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

            if (candidate.consume()) {
                processEater(candidate, match, out, window.consumed(), ignoreErrors, 0);
                window.consume(match.advance());
                continue;
            }

            counts[winner]++;
            processMatch(candidate, match, counts[winner], window.bytes(), start,
                    window.consumed(), out, ignoreErrors, 0, true);
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
    private void anyLevel(final CompiledTemplate[] templates,
                         final byte[] data,
                         final int from,
                         final int to,
                         final Output out,
                         final long inputBase,
                         final boolean ignoreErrors,
                         final int depth) {
        final byte[] work = Arrays.copyOfRange(data, from, to);
        // The working buffer is compacted in place as templates eat from it, so a group over
        // it would be overwritten under a slice: this level's groups are copies whatever the
        // caller's source was (design 37 §5). For the same reason no frame here is a slice of
        // the parent's content: each reports its bytes.
        levelLocatable[depth] = false;
        final ByteSource source = new ByteSource.Copying(work);
        int length = work.length;
        long base = inputBase;

        final int[] counts = new int[templates.length];
        final boolean[] allowed = guards(templates, depth);

        boolean matched = true;
        while (length > 0 && matched) {
            matched = false;
            for (int i = 0; i < templates.length; i++) {
                if (allowed != null && !allowed[i]) {
                    continue;
                }
                final CompiledTemplate candidate = templates[i];
                final int maxMatch = candidate.maxMatch();
                if (!candidate.consume() && maxMatch >= 0 && counts[i] >= maxMatch) {
                    continue;
                }
                final long timing = instrument.startTiming();
                final MatchResult match = match(candidate, work, 0, length, false, source);
                // The working buffer compacts as templates eat: its indexes mean nothing in the
                // parent's content, so an any-level attempt has only its input offset.
                attempted(depth, candidate.template().id(), timing, match != null, locate(base, 0),
                        Instrument.NOT_A_SLICE);
                if (match == null) {
                    continue;
                }

                final int start = match.matchStart();
                final int end = match.advance();
                if (end <= start) {
                    // Break, not return: the level can say nothing more, but the minimum-match
                    // and unmatched-content reporting below still has its say — exactly as the
                    // equivalent break in the ordered modes reaches it.
                    noProgress(candidate.template(), "content", start);
                    break;
                }

                if (!candidate.consume()) {
                    counts[i]++;
                    processMatch(candidate, match, counts[i], work, 0, base, out,
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
    private void classify(final CompiledTemplate[] templates,
                         final byte[] data,
                         final int from,
                         final int to,
                         final Output out,
                         final long inputBase,
                         final boolean ignoreErrors,
                         final int depth,
                         final ByteSource source) {
        // Guards once on the way in, as every mode (design 27 ruling 11): a guard read after an
        // earlier sibling's match would see that sibling's counters and captures.
        final boolean[] allowed = guards(templates, depth);
        for (int i = 0; i < templates.length; i++) {
            if (allowed != null && !allowed[i]) {
                continue;
            }
            final CompiledTemplate candidate = templates[i];
            final Template template = candidate.template();
            final long timing = instrument.startTiming();
            final MatchResult match = match(candidate, data, from, to, false, source);
            attempted(depth, template.id(), timing, match != null, locate(inputBase, 0), from);
            if (match == null) {
                continue;
            }
            // A record is a top-level match, here as in processMatch: counted before the
            // content check, so that what a record's functions see is the same either way.
            if (depth == 0) {
                functions.countRecord();
            }
            vars.frames().match(1);
            runBody(candidate, match, 1, out, inputBase, ignoreErrors, depth);
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
     *
     * <p>Returns <b>null when every template may be tried</b>, which is the shape a level takes
     * when nothing in it is guarded — most levels in most configurations — and which then costs
     * neither the array nor the walk (design 29 §3.1).
     */
    private boolean[] guards(final CompiledTemplate[] templates, final int depth) {
        boolean any = false;
        for (final CompiledTemplate candidate : templates) {
            if (candidate.guard() != null) {
                any = true;
                break;
            }
        }
        if (!any) {
            return null;
        }
        final boolean[] allowed = new boolean[templates.length];
        for (int i = 0; i < templates.length; i++) {
            final CompiledTemplate candidate = templates[i];
            allowed[i] = candidate.guard() == null
                         || Conditions.evaluate(candidate.guard(),
                    MatchResult.empty(), 1, vars);
        }
        if (instrument != Instrument.NONE) {
            for (int i = 0; i < templates.length; i++) {
                if (templates[i].guard() != null) {
                    instrument.onGuard(parentFrame(depth), templates[i].template().id(), allowed[i]);
                }
            }
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
    private void report(final CompiledTemplate[] templates,
                        final int[] counts,
                        final boolean ignoreErrors,
                        final byte[] data,
                        final int from,
                        final int to) {
        boolean minMatchFailed = false;
        for (int i = 0; i < templates.length; i++) {
            final Template template = templates[i].template();
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
     * delimiter too, which is why a delimiter's group 0 is not it. Which group that is was
     * decided when the template compiled; the fallback to group 0 is for a match that produced
     * no such group.
     */
    private static TypedValue content(final CompiledTemplate candidate, final MatchResult match) {
        final int contentGroup = candidate.contentGroup();
        return match.group(contentGroup) != null ? match.group(contentGroup) : match.group(0);
    }

    /** A wanted match with content: instrumented, its captures bound, its body run, its output measured. */
    private void runBody(final CompiledTemplate candidate,
                         final MatchResult match,
                         final int matchCount,
                         final Output out,
                         final long locateBase,
                         final boolean ignoreErrors,
                         final int depth) {
        final Template template = candidate.template();
        final TypedValue content = content(candidate, match);
        if (content == null || content.isEmpty()) {
            return;
        }
        final long frameId = openFrame(depth, content);
        final long inputOffset = locate(locateBase, match.matchStart());
        final int contentOffset = contentOffset(depth, content, inputOffset);
        instrument.onMatch(frameId, parentFrame(depth), template.id(), template.name(), inputOffset,
                match.advance() - match.matchStart(), contentOffset, contentLength(content), matchCount, depth);
        if (instrument != Instrument.NONE) {
            if (contentOffset == Instrument.NOT_A_SLICE) {
                instrument.onMatchContent(frameId, contentBytes(content));
            }
            reportGroups(candidate, match, matchCount, frameId, content);
        }
        // Declared before the captures bind, so a template capturing a name it declares — a
        // recursive walk keeping each level's own — binds this execution's, not the outer one's.
        enter(candidate);
        bindCaptures(candidate, match, matchCount, frameId, content);
        final long before = out.sink().position();
        if (instrument == Instrument.NONE) {
            body.body(candidate.body(), match, matchCount, content, out,
                    locateBase, ignoreErrors, depth);
        } else {
            final long outer = currentFrame;
            currentFrame = frameId;
            body.watchedBody(candidate.body(), match, matchCount, content, out,
                    locateBase, ignoreErrors, depth, frameId);
            currentFrame = outer;
        }
        exit(candidate);
        instrument.onOutput(frameId, template.id(), matchCount, before, out.sink().position() - before,
                out.sink().unit());
    }

    // -----------------------------------------------------------------------------------
    // The frame tree the instrument sees (design 18 §7). All of it is bookkeeping in arrays
    // indexed by depth: nothing allocates per match, and a run nobody watches pays the array
    // writes and no more.
    // -----------------------------------------------------------------------------------

    private void enterLevel(final int depth, final long inputBase) {
        if (depth >= levelLocatable.length) {
            final int size = Math.max(depth + 1, levelLocatable.length * 2);
            levelLocatable = Arrays.copyOf(levelLocatable, size);
            frameAt = Arrays.copyOf(frameAt, size);
            frameContentStart = Arrays.copyOf(frameContentStart, size);
        }
        levelLocatable[depth] = inputBase < Instrument.UNLOCATABLE;
    }

    /** The innermost frame whose body is running, for a watched run's messages; the document otherwise. */
    private long currentFrame = Instrument.ROOT_FRAME;

    long currentFrame() {
        return currentFrame;
    }

    private long openFrame(final int depth, final TypedValue content) {
        final long frameId = ++nextFrame;
        frameAt[depth] = frameId;
        frameContentStart[depth] = content instanceof final TypedValue.Bytes bytes
                ? bytes.readOffset()
                : Instrument.NOT_A_SLICE;
        return frameId;
    }

    private long parentFrame(final int depth) {
        return depth == 0
                ? Instrument.ROOT_FRAME
                : frameAt[depth - 1];
    }

    /**
     * Where a frame's content starts in its parent's, or {@link Instrument#NOT_A_SLICE}.
     *
     * <p>At depth zero the parent is the document, whose content is the input, and the root's
     * groups are copies (design 37 §5) that carry no offset of their own: the content is placed
     * where the match begins, which is where every kind's content begins. Deeper, a level runs
     * over its parent's content array and its groups are slices of it, so the content's offset
     * in that array is its offset in the parent's content — with one approximation the engine's
     * input offsets already make: a level dispatched on a group other than the content group is
     * placed as if that group began where the content does. A level whose bytes came from a
     * variable is no slice of anything the parent frame holds.
     */
    private int contentOffset(final int depth, final TypedValue content, final long inputOffset) {
        if (!levelLocatable[depth] || !(content instanceof final TypedValue.Bytes bytes)) {
            return Instrument.NOT_A_SLICE;
        }
        if (depth == 0) {
            return inDocument(inputOffset);
        }
        final int parentStart = frameContentStart[depth - 1];
        return parentStart == Instrument.NOT_A_SLICE
                ? Instrument.NOT_A_SLICE
                : bytes.readOffset() - parentStart;
    }

    /** An input offset as an offset into the document frame's content, which is the input. */
    private static int inDocument(final long inputOffset) {
        return inputOffset >= Instrument.UNLOCATABLE || inputOffset > Integer.MAX_VALUE
                ? Instrument.NOT_A_SLICE
                : (int) inputOffset;
    }

    private static int contentLength(final TypedValue content) {
        return content instanceof final TypedValue.Bytes bytes
                ? bytes.readLength()
                : content.asUtf8().length;
    }

    private static byte[] contentBytes(final TypedValue content) {
        if (content instanceof final TypedValue.Bytes bytes) {
            return Arrays.copyOfRange(bytes.readArray(), bytes.readOffset(), bytes.readOffset() + bytes.readLength());
        }
        return content.asUtf8();
    }

    /** An attempt at an index into the level's array, reported with its place in the parent's content. */
    private void attempted(final int depth,
                           final String templateId,
                           final long timing,
                           final boolean matched,
                           final long inputOffset,
                           final int index) {
        int contentOffset = Instrument.NOT_A_SLICE;
        if (index != Instrument.NOT_A_SLICE && levelLocatable[depth]) {
            if (depth == 0) {
                contentOffset = inDocument(inputOffset);
            } else if (frameContentStart[depth - 1] != Instrument.NOT_A_SLICE) {
                contentOffset = index - frameContentStart[depth - 1];
            }
        }
        instrument.stopTiming(parentFrame(depth), templateId, timing, matched, inputOffset, contentOffset);
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

    /**
     * @param source how a group of the data becomes a value: a copy over the window, a slice
     *               over a value (design 37 §5)
     */
    private MatchResult match(final CompiledTemplate compiledTemplate,
                              final byte[] data,
                              final int from,
                              final int to,
                              final boolean atCursor,
                              final ByteSource source) {
        if (from >= to) {
            return null;
        }
        return switch (compiledTemplate.match()) {
            case final CompiledMatch.Delimiter delimiter -> Splitter.split(data, from, to,
                    delimiter.delimiter(), delimiter.escape(),
                    delimiter.containerStart(), delimiter.containerEnd(),
                    effective(compiledTemplate), source);
            case final CompiledMatch.Regex regex ->
                    regexMatch(regex, data, from, to, atCursor, effective(compiledTemplate), source);
            case final CompiledMatch.Pattern pattern ->
                    patternMatch(pattern, data, from, to, atCursor, effective(compiledTemplate), source, 0);
            case final CompiledMatch.Parts parts ->
                    partsMatch(parts, data, from, to, effective(compiledTemplate), source);
            case final CompiledMatch.All ignored -> new MatchResult(
                    new TypedValue[]{source.slice(from, to, effective(compiledTemplate))}, to - from, 0);
            case final CompiledMatch.Source ignored -> null;
            case final CompiledMatch.Named ignored -> null;
        };
    }

    private static MatchResult regexMatch(final CompiledMatch.Regex regex,
                                          final byte[] data,
                                          final int from,
                                          final int to,
                                          final boolean atCursor,
                                          final Encoding encoding,
                                          final ByteSource source) {
        // The node owns its matcher (D35) and asks the question the library's published
        // anchor fact licenses: for an input-anchored pattern the anchored and unanchored
        // questions provably agree, so the node asks the one with the bare prologue. The
        // fact's single source is the library's parser — nothing here reads pattern text.
        final ByteMatcher matcher = regex.matcher();
        final Anchoring question = atCursor ? Anchoring.ANCHORED : regex.anchoring();
        if (!matcher.match(data, from, to, question)) {
            return null;
        }
        // Each group as the source makes it: a copy over the window, a slice over a value
        // (design 37 §5, 3c). Not one span with the groups as ranges of it (3d): that read
        // win_sec −6% to −10% and log_sessions −3% against this, six rounds of six — the span
        // logic took this method from 177 to 261 bytes and the match arm stopped inlining into
        // the dispatch loop, and a root regex's many groups became slices at sites tuned for
        // whole values. The splitter keeps the span rule, where it won.
        final int groupCount = regex.pattern().groupCount() + 1;
        final TypedValue[] groups = new TypedValue[groupCount];
        for (int i = 0; i < groupCount; i++) {
            if (matcher.matchedGroup(i)) {
                groups[i] = source.slice(matcher.start(i), matcher.end(i), encoding);
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

    /**
     * A pattern tree's match is the regex's (design 38 §5); what it adds is the casts on its
     * labelled groups, applied once to the groups the match bound. A cast that does not read —
     * the wrong width, a varint that never ends — leaves the group absent.
     *
     * @param positionBase what a {@code position} cast counts from: zero for a template's own
     *                     pattern, and the part's offset in a match sequence, so a position is
     *                     always from the start of the match, whichever part it sits in
     */
    private static MatchResult patternMatch(final CompiledMatch.Pattern pattern,
                                            final byte[] data,
                                            final int from,
                                            final int to,
                                            final boolean atCursor,
                                            final Encoding encoding,
                                            final ByteSource source,
                                            final int positionBase) {
        final MatchResult match = regexMatch(pattern.regex(), data, from, to, atCursor, encoding, source);
        if (match == null || !pattern.anyCast()) {
            return match;
        }
        final ByteMatcher matcher = pattern.regex().matcher();
        final TypedValue[] groups = match.groups();
        final int start = matcher.start();
        for (int i = 1; i < groups.length; i++) {
            final BinaryCast cast = pattern.cast(i);
            if (cast != null && matcher.matchedGroup(i)) {
                groups[i] = BinaryCasts.apply(cast, groups[i], positionBase + matcher.start(i) - start);
            }
        }
        return match;
    }

    /**
     * A match sequence (design 38 §3b), run part by part from the cursor: a pattern matches
     * anchored where the last part ended and contributes its groups; a take consumes a length
     * of bytes as one group; a seek moves the cursor; a read binds a value by its cast's width
     * (design 39). A length is a number, a group already
     * bound read as an integer, or a variable. Fewer bytes than a take or a seek asks for fails
     * the match, which is what a truncated record should do. Nothing backtracks across parts.
     */
    private MatchResult partsMatch(final CompiledMatch.Parts parts,
                                   final byte[] data,
                                   final int from,
                                   final int to,
                                   final Encoding encoding,
                                   final ByteSource source) {
        final TypedValue[] groups = new TypedValue[parts.groupCount() + 1];
        int cursor = from;
        for (final CompiledMatch.CompiledPart part : parts.parts()) {
            // The verbs are behind calls, and every arm yields the cursor after it or -1: the
            // loop is a dispatcher with one failure test, kept under the JIT's hot-method size
            // so it inlines into the match (design 38 §8's census of this path), and each verb
            // is a small method that inlines into it where it is hot.
            cursor = switch (part) {
                case final CompiledMatch.CompiledPart.Pattern pattern ->
                        patternPart(pattern, data, cursor, from, to, groups, encoding, source);
                case final CompiledMatch.CompiledPart.Take take -> take(take, groups, cursor, to, encoding, source);
                case final CompiledMatch.CompiledPart.Seek seek -> seek(seek, groups, cursor, from, to);
                // A literal at the cursor: the bytes compared where they lie (design 41 §6).
                case final CompiledMatch.CompiledPart.Literal literal ->
                        literal(literal, data, cursor, to, groups, encoding, source);
                // A value at the cursor, by the cast's own width, with no pattern run (design
                // 39, D56): the bytes are read where they lie.
                case final CompiledMatch.CompiledPart.Read read ->
                        BinaryCasts.read(read.cast(), data, cursor, from, to, groups, read.group());
            };
            if (cursor < 0) {
                return null;
            }
        }
        groups[0] = source.slice(from, cursor, encoding);
        return new MatchResult(groups, cursor - from, 0);
    }

    /** The cursor after a pattern part, its groups copied into the sequence's after its offset, or -1. */
    private static int patternPart(final CompiledMatch.CompiledPart.Pattern pattern,
                                   final byte[] data,
                                   final int cursor,
                                   final int from,
                                   final int to,
                                   final TypedValue[] groups,
                                   final Encoding encoding,
                                   final ByteSource source) {
        final MatchResult one = patternMatch(pattern.pattern(), data, cursor, to, true, encoding, source,
                cursor - from);
        if (one == null) {
            return -1;
        }
        final TypedValue[] own = one.groups();
        System.arraycopy(own, 1, groups, pattern.groupOffset() + 1, own.length - 1);
        return cursor + one.advance();
    }

    /** The cursor after a literal that is there, its bytes as the label's group if it has one, or -1. */
    private static int literal(final CompiledMatch.CompiledPart.Literal literal,
                               final byte[] data,
                               final int cursor,
                               final int to,
                               final TypedValue[] groups,
                               final Encoding encoding,
                               final ByteSource source) {
        final byte[] bytes = literal.bytes();
        if (cursor + bytes.length > to) {
            return -1;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (data[cursor + i] != bytes[i]) {
                return -1;
            }
        }
        if (literal.group() != 0) {
            groups[literal.group()] = source.slice(cursor, cursor + bytes.length, encoding);
        }
        return cursor + bytes.length;
    }

    /** The cursor after a take, its bytes as the part's group, or -1 when they do not reach. */
    private int take(final CompiledMatch.CompiledPart.Take take,
                     final TypedValue[] groups,
                     final int cursor,
                     final int to,
                     final Encoding encoding,
                     final ByteSource source) {
        final int length = length(take.length(), groups);
        if (length < 0 || cursor + length > to) {
            return -1;
        }
        groups[take.group()] = source.slice(cursor, cursor + length, encoding);
        return cursor + length;
    }

    /** The cursor after a seek, or -1 when it cannot be made. */
    private int seek(final CompiledMatch.CompiledPart.Seek seek,
                     final TypedValue[] groups,
                     final int cursor,
                     final int from,
                     final int to) {
        final int length = length(seek.length(), groups);
        final int target = seek.absolute() ? from + length : cursor + length;
        return length < 0 || target < cursor || target > to ? -1 : target;
    }

    /**
     * A length at run time, or -1 when what it names is absent or not a number. A literal is
     * its number and a cast group is already an integer, so neither boxes; only a variable
     * takes the general reading.
     */
    private int length(final CompiledMatch.CompiledLength length, final TypedValue[] groups) {
        final long count;
        switch (length) {
            case final CompiledMatch.CompiledLength.Literal literal -> count = literal.count();
            case final CompiledMatch.CompiledLength.Group group -> {
                if (!(groups[group.group()] instanceof final TypedValue.Integer integer)) {
                    return -1;
                }
                count = integer.value();
            }
            case final CompiledMatch.CompiledLength.Var var -> {
                final TypedValue value = vars.get(var.name());
                final Long read = value == null ? null : value.asInteger();
                if (read == null) {
                    return -1;
                }
                count = read;
            }
        }
        return count < 0 || count > Integer.MAX_VALUE ? -1 : (int) count;
    }

    private void bindCaptures(final CompiledTemplate compiledTemplate,
                              final MatchResult match,
                              final int matchCount,
                              final long frameId,
                              final TypedValue content) {
        // This method is kept whole, and so above the JIT's hot-method size, on purpose: inlined
        // into the level's match loop it crowds the regex match itself out of that compilation
        // ("already compiled into a big method"), which read regex_lines 20% down when the
        // key-value pair was moved out of line to let it inline (2026-09-15).
        for (final CompiledCapture capture : compiledTemplate.captures()) {
            if (capture.source() instanceof final CompiledCapture.Source.KeyValue keyValue) {
                // The pair goes into the map the capture names (design 35 §5): the key read out
                // of the data is a key, not a variable, and a later pair with the same key
                // replaces the earlier — a map has keys, not positions. The key is the bytes
                // the match holds, as a value; nothing decodes it to text on the way.
                final TypedValue key = CompiledRefs.resolveValue(keyValue.key(), match, matchCount, vars);
                if (key != null) {
                    final TypedValue value = CompiledRefs.resolveValue(keyValue.value(), match, matchCount, vars);
                    if (value != null) {
                        vars.put(capture.name(), key, cast(value, capture.as()));
                    }
                }
                continue;
            }
            // A capture is a slice of the input, stored as the match tagged it: nothing is
            // transcoded until a consumer asks for text (design 25).
            final TypedValue read = switch (capture.source()) {
                case final CompiledCapture.Source.Group group -> match.group(group.group());
                case final CompiledCapture.Source.Select select -> {
                    final byte[] bytes = CompiledRefs.resolve(select.ref(), match, matchCount, vars);
                    yield bytes == null ? null : TypedValue.utf8(bytes);
                }
                case final CompiledCapture.Source.KeyValue ignored ->
                        throw new IllegalStateException("key-value bound above");
            };
            final TypedValue value = cast(read, capture.as());
            if (value != null && instrument != Instrument.NONE) {
                // Placed when the group and the content are ranges of one array - a slice's
                // array is the level's, a copy's is its own - and the group lies within the
                // content (a delimiter's group 0 holds the delimiter its field does not).
                final int at = read instanceof final TypedValue.Bytes bytes
                        && content instanceof final TypedValue.Bytes range
                        && bytes.readArray() == range.readArray()
                        && bytes.readOffset() >= range.readOffset()
                        && bytes.readOffset() + bytes.readLength() <= range.readOffset() + range.readLength()
                        ? bytes.readOffset() - range.readOffset()
                        : Instrument.NOT_A_SLICE;
                instrument.onCapture(frameId, compiledTemplate.template().id(), capture.name().name(), value,
                        matchCount, at, at == Instrument.NOT_A_SLICE
                                ? 0
                                : ((TypedValue.Bytes) read).readLength());
            }
            // A capture is a value source (design 35 §4): it assigns the scalar it names, or puts
            // at this match's position in the list it names. An unmatched capture — or a cast
            // that failed — is absence, assigned or appended the same way (design 25 §9, 35 §8),
            // so a name never keeps what the previous record left and positions stay aligned.
            if (capture.target() == Declaration.Type.LIST) {
                // Match numbers are 1-based positions; the list is indexed from 0 (design 35 §5).
                vars.setAt(capture.name(), matchCount - 1, value);
            } else {
                vars.set(capture.name(), value);
            }
        }
    }


    /** A watched match's groups, each placed in the frame's content the way a capture is. */
    private void reportGroups(final CompiledTemplate candidate,
                              final MatchResult match,
                              final int matchCount,
                              final long frameId,
                              final TypedValue content) {
        final TypedValue[] groups = match.groups();
        final String[] known = candidate.match().groupNames();
        final String[] names = new String[groups.length];
        final int[] offsets = new int[groups.length];
        final int[] lengths = new int[groups.length];
        for (int i = 0; i < groups.length; i++) {
            names[i] = i < known.length
                    ? known[i]
                    : null;
            offsets[i] = placeIn(groups[i], content);
            lengths[i] = offsets[i] == Instrument.NOT_A_SLICE
                    ? 0
                    : ((TypedValue.Bytes) groups[i]).readLength();
        }
        instrument.onGroups(frameId, candidate.template().id(), matchCount, names, offsets, lengths);
    }

    /**
     * Where a value lies in a content, or {@link Instrument#NOT_A_SLICE}: placed when the two are
     * ranges of one array — a slice's array is the level's, a copy's is its own — and the value
     * lies within the content.
     */
    private static int placeIn(final TypedValue value, final TypedValue content) {
        return value instanceof final TypedValue.Bytes bytes
               && content instanceof final TypedValue.Bytes range
               && bytes.readArray() == range.readArray()
               && bytes.readOffset() >= range.readOffset()
               && bytes.readOffset() + bytes.readLength() <= range.readOffset() + range.readLength()
                ? bytes.readOffset() - range.readOffset()
                : Instrument.NOT_A_SLICE;
    }

    /**
     * The capture's declared kind, applied once at bind (design 25 §9.1, D50): the casting
     * table's reading, absent when it has none; {@code string} keeps the bytes and fills their
     * UTF-8 form now, so no consumer decodes later.
     */
    private static TypedValue cast(final TypedValue value, final Cast as) {
        if (value == null || as == null) {
            return value;
        }
        final TypedValue cast = Comparisons.cast(value, as);
        if (as == Cast.STRING && cast instanceof final TypedValue.Bytes bytes) {
            bytes.asUtf8();
        }
        return cast;
    }

    /**
     * The encoding a template's captured bytes are in: its own declared override (E3), else
     * whatever the run settled on — declaration or byte-order mark.
     */
    private Encoding effective(final CompiledTemplate candidate) {
        return Encoding.resolve(candidate.encoding(), encoding);
    }

    /** An absolute input offset, unless the base says the content cannot be located. */
    static long locate(final long base, final int offset) {
        return base >= Instrument.UNLOCATABLE ? Instrument.UNLOCATABLE : base + offset;
    }
}
