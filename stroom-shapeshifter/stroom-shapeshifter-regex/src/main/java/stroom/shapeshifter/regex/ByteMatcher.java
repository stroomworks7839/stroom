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

package stroom.shapeshifter.regex;

import stroom.shapeshifter.regex.internal.Backtracker;
import stroom.shapeshifter.regex.internal.FancyBacktracker;
import stroom.shapeshifter.regex.internal.NodeTree;
import stroom.shapeshifter.regex.internal.PikeVm;
import stroom.shapeshifter.regex.internal.Plan;
import stroom.shapeshifter.regex.internal.PlanRunner;
import stroom.shapeshifter.regex.internal.Utf8;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Matches a {@link BytePattern} against byte data.
 * <p>
 * Holds the mutable match state, so it is <b>not thread safe</b>; allocate one per thread and
 * reuse it across matches, as with {@link java.util.regex.Matcher}.
 * <p>
 * Groups are returned as offsets into the caller's array — no copying. Call
 * {@link #groupBytes(int)} only when a copy is genuinely needed.
 */
public final class ByteMatcher {

    private final BytePattern pattern;
    private final Plan plan;
    private final PikeVm vm;
    private final Backtracker backtracker;

    /**
     * The unbounded backtracker, present when the pattern needs it — its constructs run on no
     * other engine — or when a test pinned it. Never chosen by cost: the pattern's syntax is
     * the only way in.
     */
    private final FancyBacktracker fancy;

    /** The experimental tree-walking engine, present only when {@link Engine#TREE} was forced. */
    private final NodeTree.Machine tree;

    /**
     * How much the backtracker's (instruction, position) bitset may occupy before a search goes to
     * the simulation instead. 128 KB covers a 2,000-instruction program over a 500-byte record,
     * which is the shape this engine is built for; beyond it the simulation's fixed cost per
     * position is the better trade.
     */
    private static final int BACKTRACK_BUDGET_BYTES = 128 * 1024;
    private final int groupCount;
    private final int[] slots;

    private byte[] data;
    private int regionFrom;
    private int regionTo;
    private boolean complete = true;
    private boolean matched;

    ByteMatcher(final BytePattern pattern) {
        this.pattern = pattern;
        this.plan = pattern.plan();
        this.tree = pattern.tree() != null
                ? new NodeTree.Machine(pattern.tree())
                : null;
        final boolean needsFancy = tree == null && plan == null
                                   && (pattern.nfa().fancy() || pattern.forced() == Engine.FANCY);
        this.fancy = needsFancy
                ? new FancyBacktracker(pattern.nfa())
                : null;
        this.vm = tree == null && plan == null && !needsFancy
                ? new PikeVm(pattern.nfa())
                : null;
        this.backtracker = tree == null && plan == null && !needsFancy
                ? new Backtracker(pattern.nfa())
                : null;
        this.groupCount = pattern.groupCount();
        this.slots = new int[tree != null
                ? pattern.tree().slotCount()
                : plan != null
                ? plan.slotCount()
                : pattern.nfa().slotCount()];
    }

    public BytePattern pattern() {
        return pattern;
    }

    /** Matches against the whole array, searching forward for the leftmost match. */
    public boolean find(final byte[] data) {
        return match(data, 0, data.length, Anchoring.UNANCHORED);
    }

    /**
     * Matches within a window that may not hold all the input yet.
     * <p>
     * This is the streaming entry point: unlike the boolean methods, it can answer
     * {@link MatchOutcome#NEED_MORE_INPUT}, which the caller resolves by extending the window
     * and asking again.
     */
    public MatchOutcome match(final ByteWindow window, final int from, final Anchoring anchoring) {
        if (from < window.start() || from > window.end()) {
            throw new IndexOutOfBoundsException(
                    "offset " + from + " outside window [" + window.start() + ", "
                    + window.end() + ")");
        }
        this.data = window.array();
        this.regionFrom = window.start();
        this.regionTo = window.end();
        this.complete = window.complete();
        this.matched = false;
        return run(from, anchoring);
    }

    /** Matches at exactly {@code from}. */
    public boolean matchesAt(final byte[] data, final int from) {
        return match(data, from, data.length, Anchoring.ANCHORED);
    }

    /**
     * Matches within {@code [from, to)}.
     *
     * @param anchoring whether the match must begin at {@code from} or may be searched for.
     * @return true if a match was found; group accessors are then valid until the next call.
     */
    public boolean match(final byte[] data,
                         final int from,
                         final int to,
                         final Anchoring anchoring) {
        if (from < 0 || to > data.length || from > to) {
            throw new IndexOutOfBoundsException(
                    "region [" + from + ", " + to + ") outside array of length " + data.length);
        }
        this.data = data;
        this.regionFrom = from;
        this.regionTo = to;
        this.complete = true;
        this.matched = false;
        return run(from, anchoring) == MatchOutcome.MATCH;
    }

    private MatchOutcome run(final int from, final Anchoring anchoring) {
        if (tree != null) {
            Arrays.fill(slots, -1);
            final int end = tree.search(data, regionFrom, from, regionTo,
                    anchoring == Anchoring.ANCHORED, complete, slots);
            matched = end >= 0;
            return outcome(end);
        }
        if (fancy != null) {
            Arrays.fill(slots, -1);
            final int end = fancy.search(data, regionFrom, from, regionTo,
                    anchoring == Anchoring.ANCHORED, complete, slots);
            matched = end >= 0;
            return outcome(end);
        }
        if (vm != null) {
            Arrays.fill(slots, -1);
            final boolean anchored = anchoring == Anchoring.ANCHORED;
            // Backtracking is cheaper per input position, so it is preferred wherever its bitset
            // is affordable — which depends on the input length, and so is decided here rather
            // than at compile time. The simulation is the fallback, and the guarantee: whatever
            // the pattern, one of the two runs in linear time.
            final int end = useBacktracker()
                    ? backtracker.search(
                            data, regionFrom, from, regionTo, anchored, complete, slots)
                    // The VM searches for the leftmost match itself, advancing every live thread
                    // together, rather than restarting an attempt at each offset.
                    : vm.search(data, regionFrom, from, regionTo, anchored, complete, slots);
            matched = end >= 0;
            return outcome(end);
        }

        if (anchoring == Anchoring.ANCHORED) {
            return splitsCharacter(from)
                    ? MatchOutcome.NO_MATCH
                    : outcome(attempt(from));
        }

        final byte[] firstBytes = plan.firstBytes();
        final var leadingAnchor = plan.leadingAnchor();
        for (int start = from; start <= regionTo; start++) {
            if (leadingAnchor != null && !isAnchorPosition(leadingAnchor, start)) {
                // A start-anchored pattern can only match where the anchor holds, which for a
                // typical ^-anchored pattern rules out all but the line starts. Testing that here
                // avoids setting up an attempt that the first instruction would reject anyway.
                continue;
            }
            if (splitsCharacter(start)) {
                continue;
            }
            if (firstBytes != null && (start == regionTo || firstBytes[data[start] & 0xFF] == 0)) {
                // Skip offsets that cannot begin a match. A pattern that can match empty has no
                // first-byte table, and every offset has to be tried.
                continue;
            }
            final int end = attempt(start);
            if (end >= 0 || end == PlanRunner.NEED_MORE) {
                // An undetermined attempt has to stop the search: a later start position could
                // only produce a match this one would have preferred.
                return outcome(end);
            }
        }
        // Nothing matched, but a match beginning near the edge may simply be incomplete.
        return !complete && plan.firstBytes() != null
                ? MatchOutcome.NEED_MORE_INPUT
                : MatchOutcome.NO_MATCH;
    }

    private boolean isAnchorPosition(final stroom.shapeshifter.regex.internal.Hir.Kind anchor,
                                     final int start) {
        if (start == regionFrom) {
            return true;
        }
        return anchor == stroom.shapeshifter.regex.internal.Hir.Kind.START_LINE
               && data[start - 1] == '\n';
    }

    /**
     * Whether an offset falls inside a character, and so cannot begin a match — not even an empty
     * one, which is the only kind that could. An offset that splits a character is of no use to a
     * caller reading the text back.
     * <p>
     * The byte after the region is consulted when there is one and the window is complete, since a
     * region can end inside a character; on a window that can still grow, the byte beyond it has
     * not arrived and must not be read.
     */
    private boolean splitsCharacter(final int at) {
        return (at < regionTo || (complete && at < data.length))
               && Utf8.isContinuation(data[at]);
    }

    /**
     * Backtracking is preferred wherever it is affordable, unless a caller has pinned an engine.
     * Pinning exists so that each engine can be run against the others over the whole corpus:
     * agreement between three implementations, two of them from different algorithm families, is
     * what the correctness argument rests on.
     */
    private boolean useBacktracker() {
        final Engine pinned = pattern.forced();
        if (pinned == Engine.SIMULATE) {
            return false;
        }
        final boolean affordable =
                backtracker.canRun(regionTo - regionFrom, BACKTRACK_BUDGET_BYTES);
        if (pinned == Engine.BACKTRACK && !affordable) {
            throw new IllegalStateException(
                    "backtracking was pinned but cannot run this pattern over "
                    + (regionTo - regionFrom) + " bytes");
        }
        return affordable;
    }

    private MatchOutcome outcome(final int end) {
        if (end == PlanRunner.NEED_MORE) {
            return MatchOutcome.NEED_MORE_INPUT;
        }
        return end >= 0
                ? MatchOutcome.MATCH
                : MatchOutcome.NO_MATCH;
    }

    private int attempt(final int start) {
        Arrays.fill(slots, -1);
        final int end = PlanRunner.run(plan, data, regionFrom, start, regionTo, complete, slots);
        if (end < 0) {
            return end;
        }
        slots[0] = start;
        slots[1] = end;
        matched = true;
        return end;
    }

    // -----------------------------------------------------------------------------------
    // Results
    // -----------------------------------------------------------------------------------

    public int start() {
        return start(0);
    }

    public int end() {
        return end(0);
    }

    public int start(final int group) {
        checkMatched();
        checkGroup(group);
        return slots[2 * group];
    }

    public int end(final int group) {
        checkMatched();
        checkGroup(group);
        return slots[2 * group + 1];
    }

    /** True if the group took part in the match — distinct from having matched empty. */
    public boolean matchedGroup(final int group) {
        checkMatched();
        checkGroup(group);
        return slots[2 * group] >= 0 && slots[2 * group + 1] >= 0;
    }

    /** The span of a group, or null if it did not take part. No bytes are copied. */
    public ByteSpan group(final int group) {
        return matchedGroup(group)
                ? new ByteSpan(data, start(group), end(group))
                : null;
    }

    public ByteSpan group(final String name) {
        final int index = pattern.groupIndex(name);
        if (index < 0) {
            throw new IllegalArgumentException("No group named '" + name + "'");
        }
        return group(index);
    }

    /** A copy of a group's bytes, or null if it did not take part. */
    public byte[] groupBytes(final int group) {
        return matchedGroup(group)
                ? Arrays.copyOfRange(data, start(group), end(group))
                : null;
    }

    /** A group decoded as UTF-8, or null if it did not take part. */
    public String groupString(final int group) {
        return matchedGroup(group)
                ? new String(data, start(group), end(group) - start(group), StandardCharsets.UTF_8)
                : null;
    }

    private void checkMatched() {
        if (!matched) {
            throw new IllegalStateException("No match — call match() first and check the result");
        }
    }

    private void checkGroup(final int group) {
        if (group < 0 || group > groupCount) {
            throw new IndexOutOfBoundsException(
                    "No group " + group + "; pattern has " + groupCount);
        }
    }
}
