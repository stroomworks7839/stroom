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
import stroom.shapeshifter.regex.internal.Hir;
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

    /**
     * How much the backtracker's (instruction, position) bitset may occupy before a search is
     * refused. The bounded backtracker runs only when pinned (D32), so there is no automatic
     * fall-through to another engine: a pinned search that exceeds the budget throws
     * {@link IllegalStateException}. 128 KB covers a 2,000-instruction program over a 500-byte
     * record, which is the shape this engine is built for; beyond it the simulation's fixed cost
     * per position is the better trade.
     */
    private static final int BACKTRACK_BUDGET_BYTES = 128 * 1024;

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

    /**
     * The tree-walking engine, present for every fancy and every ambiguous pattern — it runs
     * first on both since D31/D32 — and when {@link Engine#TREE} was pinned. Null when the
     * pattern compiled to a scan plan, or when another engine was pinned.
     */
    private final NodeTree.Machine tree;

    private final int groupCount;
    private final int[] slots;

    private byte[] data;
    private int regionFrom;
    private int regionTo;
    private boolean complete = true;
    /** One past the last byte that may be consulted as context — the window's contextEnd. */
    private int validTo;
    private boolean matched;

    ByteMatcher(final BytePattern pattern) {
        this.pattern = pattern;
        this.plan = pattern.plan();
        // Which machines this matcher may need (D31). A pinned engine builds only itself;
        // otherwise a fancy pattern carries the tree plus its flat fallback, and an ambiguous
        // one carries the tree plus both linear engines.
        final Engine pinned = pattern.forced();
        final boolean fancyProgram = plan == null && pattern.nfa() != null
                                     && pattern.nfa().fancy();
        this.tree = pattern.tree() != null && (pinned == null || pinned == Engine.TREE)
                ? new NodeTree.Machine(pattern.tree())
                : null;
        final boolean needsFancy = plan == null && pattern.nfa() != null
                                   && (fancyProgram || pinned == Engine.FANCY)
                                   && pinned != Engine.TREE;
        this.fancy = needsFancy
                ? new FancyBacktracker(pattern.nfa())
                : null;
        final boolean needsLinear = plan == null && pattern.nfa() != null && !fancyProgram
                                    && pinned != Engine.FANCY && pinned != Engine.TREE;
        // A pinned bounded backtracker takes every search itself (or refuses), so the VM would
        // be unreachable beside it; slot sizing reads the NFA directly, not the VM.
        this.vm = needsLinear && pinned != Engine.BACKTRACK
                ? new PikeVm(pattern.nfa())
                : null;
        // The bounded backtracker retired from the default path (D32): every corpus pattern
        // measured faster on the tree engine once bounded class repeats compiled to one node.
        // It remains a pinnable engine, and the differential suite's third witness.
        this.backtracker = needsLinear && pinned == Engine.BACKTRACK
                ? new Backtracker(pattern.nfa())
                : null;
        this.groupCount = pattern.groupCount();
        this.slots = new int[tree != null
                ? pattern.tree().slotCount()
                : plan != null
                ? plan.slotCount()
                : pattern.nfa().slotCount()];
    }

    /** The pattern this matcher was created from. */
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
                    + window.end() + "]");
        }
        this.data = window.array();
        this.regionFrom = window.start();
        this.regionTo = window.end();
        this.validTo = window.contextEnd();
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
        this.validTo = data.length;
        this.complete = true;
        this.matched = false;
        return run(from, anchoring) == MatchOutcome.MATCH;
    }

    private MatchOutcome run(final int from, final Anchoring anchoring) {
        // One small dispatcher, one method per engine. Kept deliberately tiny: this method and
        // the search loops it calls are the hottest call sites in the library, and letting
        // them grow into one another once cost the CSV workload 24% to a JIT inlining cliff —
        // found by the tier 0 audit's performance guard, bisected to a semantically trivial
        // edit whose only crime was method size.
        final boolean anchored = anchoring == Anchoring.ANCHORED;
        if (tree != null && fancy == null && vm == null) {
            return runPinnedTree(from, anchored);
        }
        if (fancy != null) {
            return runFancy(from, anchored);
        }
        if (vm != null || backtracker != null) {
            return runLinear(from, anchored);
        }
        if (anchored) {
            return splitsCharacter(from)
                    ? MatchOutcome.NO_MATCH
                    : outcome(attempt(from));
        }
        return searchPlan(from);
    }

    /** Pinned to the tree engine: a structural bailout is contained, not fallen from. */
    private MatchOutcome runPinnedTree(final int from, final boolean anchored) {
        Arrays.fill(slots, -1);
        try {
            tree.setContextEnd(validTo);
            final int end = tree.search(data, regionFrom, from, regionTo,
                    anchored, complete, slots);
            matched = end >= 0;
            return outcome(end);
        } catch (final NodeTree.Bailout e) {
            throw new MatchLimitException(
                    "the tree engine was pinned but the input stacks more loop iterations "
                    + "than the call stack tolerates; unpin it, or restructure the pattern");
        }
    }

    /**
     * A fancy pattern: the tree engine first — it measured at or ahead of the JDK on every
     * fancy workload — with the flat backtracker as the structural fallback when recursion
     * depth gives out (D31). Both share the step budget's contract.
     */
    private MatchOutcome runFancy(final int from, final boolean anchored) {
        Arrays.fill(slots, -1);
        if (tree != null) {
            try {
                tree.setContextEnd(validTo);
                final int end = tree.search(data, regionFrom, from, regionTo,
                        anchored, complete, slots);
                matched = end >= 0;
                return outcome(end);
            } catch (final NodeTree.Bailout e) {
                Arrays.fill(slots, -1); // a clean rerun, not a resume
            }
        }
        fancy.setContextEnd(validTo);
        final int end = fancy.search(data, regionFrom, from, regionTo,
                anchored, complete, slots);
        matched = end >= 0;
        return outcome(end);
    }

    /**
     * An ambiguous pattern. The tree engine first at every region size — it measured faster
     * than every flat engine on all 36 automaton corpus patterns (D32) — and the simulation
     * remains both fallback and guarantee: whatever the engines give up on, one machine
     * finishes in linear time. A pinned bounded backtracker still runs here, which is what
     * keeps it under differential test.
     */
    private MatchOutcome runLinear(final int from, final boolean anchored) {
        Arrays.fill(slots, -1);
        if (backtracker != null) {
            if (!backtracker.canRun(regionTo - regionFrom, BACKTRACK_BUDGET_BYTES)) {
                throw new IllegalStateException(
                        "backtracking was pinned but cannot run this pattern over "
                        + (regionTo - regionFrom) + " bytes");
            }
            backtracker.setContextEnd(validTo);
            final int end = backtracker.search(
                    data, regionFrom, from, regionTo, anchored, complete, slots);
            matched = end >= 0;
            return outcome(end);
        }
        if (tree != null) {
            try {
                tree.setContextEnd(validTo);
                final int end = tree.search(data, regionFrom, from, regionTo,
                        anchored, complete, slots);
                matched = end >= 0;
                return outcome(end);
            } catch (final NodeTree.Bailout | MatchLimitException e) {
                Arrays.fill(slots, -1); // the linear engine answers instead
            }
        }
        // The VM searches for the leftmost match itself, advancing every live thread
        // together, rather than restarting an attempt at each offset.
        vm.setContextEnd(validTo);
        final int end = vm.search(data, regionFrom, from, regionTo,
                anchored, complete, slots);
        matched = end >= 0;
        return outcome(end);
    }

    /** The scan plan's unanchored search: the leftmost start whose attempt succeeds. */
    private MatchOutcome searchPlan(final int from) {
        final byte[] firstBytes = plan.firstBytes();
        final var leadingAnchor = plan.leadingAnchor();
        // The minimum-length gate earns its keep on short unanchored searches — per-match
        // datetime fell 43% the day it was removed on buffer-CSV evidence alone, a lesson in
        // guarding both suites — and since the dispatcher split it no longer costs CSV its
        // inlining cliff. Complete windows only, or NEED_MORE would be lost.
        int lastStart = complete
                ? regionTo - plan.minLength()
                : regionTo;
        // An input-anchored pattern cannot start past the region start, so on a window that
        // cannot grow the scan ends there. Decided once, out here, so the line-anchored scan
        // pays nothing for it; a growing window keeps its edge iterations.
        if (complete && leadingAnchor != null && leadingAnchor != Hir.Kind.START_LINE) {
            lastStart = Math.min(lastStart, regionFrom);
        }
        for (int start = from; start <= lastStart; start++) {
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

    private boolean isAnchorPosition(final Hir.Kind anchor,
                                     final int start) {
        if (start == regionFrom) {
            return true;
        }
        return anchor == Hir.Kind.START_LINE
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
        return Utf8.splitsCharacter(data, at, regionTo, complete, validTo);
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

    /** The buffer offset where the match starts. */
    public int start() {
        return start(0);
    }

    /** The buffer offset one past the last byte of the match. */
    public int end() {
        return end(0);
    }

    /** The buffer offset where a group starts, or -1 if the group did not take part. */
    public int start(final int group) {
        checkMatched();
        checkGroup(group);
        return slots[2 * group];
    }

    /** The buffer offset one past a group's last byte, or -1 if the group did not take part. */
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

    /** The span of a named group, or null if it did not take part; the name must exist. */
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
