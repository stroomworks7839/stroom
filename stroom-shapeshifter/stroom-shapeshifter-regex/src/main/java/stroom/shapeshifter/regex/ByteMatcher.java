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

import stroom.shapeshifter.regex.internal.Analysis;
import stroom.shapeshifter.regex.internal.Backtracker;
import stroom.shapeshifter.regex.internal.FancyBacktracker;
import stroom.shapeshifter.regex.internal.Hir;
import stroom.shapeshifter.regex.internal.NodeTree;
import stroom.shapeshifter.regex.internal.PikeVm;
import stroom.shapeshifter.regex.internal.Plan;
import stroom.shapeshifter.regex.internal.PlanRunner;
import stroom.shapeshifter.regex.internal.ReverseScanner;
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
    /** {@code pattern.form().singleByte()}, hoisted out of the search loop — see
     * {@link #splitsCharacter}. */
    private final boolean singleByteForm;
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

    /** The tail-window span: for an END_INPUT-anchored pattern of finite maximum length,
     * the most bytes a match can span — so no unanchored candidate start exists before
     * {@code regionTo - tailSpan} ({@code Analysis.byteLength}'s theorem, the same one that
     * makes bounded lookbehind implementable). -1 when the pattern earns no jump
     * ({@code design/06-performance-plan.md} §6 Phase 2). */
    private final int tailSpan;

    /** The reverse start-finder (§6 Phase 4), for the unbounded end-anchored patterns the
     * tail window cannot serve — or null. */
    private final ReverseScanner reverse;

    /** Whether either end-anchored acceleration applies — the one branch every other
     * pattern pays for both ({@code ISSUES.md}, accepted costs). */
    private final boolean endgame;

    private byte[] data;
    private int regionFrom;
    private int regionTo;
    /** One past the last byte that may be consulted as context — the array's end, bound
     * into the engine before each search. */
    private boolean matched;

    ByteMatcher(final BytePattern pattern) {
        this.pattern = pattern;
        this.singleByteForm = pattern.form().singleByte();
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
        // \G is excluded because its reference point is the search start, which the jump
        // would move — the audit produced \Gabc$ matching where the JDK refuses.
        this.tailSpan = pattern.trailingAnchor() == TrailingAnchor.INPUT
                        && pattern.maxLength() != Analysis.UNBOUNDED_LENGTH
                        && !pattern.anchorsToSearchStart()
                ? pattern.maxLength()
                : -1;
        this.reverse = pattern.reverseNfa() != null
                ? new ReverseScanner(pattern.reverseNfa())
                : null;
        this.endgame = tailSpan >= 0 || reverse != null;
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
     * Matches within {@code [regionFrom, to)}, searching from {@code from} — the find-next
     * spelling: the region stays fixed while the search position advances, so a zero-width
     * assertion at {@code from} still sees the byte before it. Collapsing the two (passing
     * {@code from} as the region start) would make every iteration look like a fresh input,
     * and {@code \b} would report a boundary that is not there.
     *
     * <p>The binding block repeats the four-argument entry's on purpose: that entry's call
     * shape is a measured quantity ({@code ISSUES.md}, the class-shape item), so neither
     * entry delegates to the other.
     *
     * @param anchoring whether the match must begin at {@code from} or may be searched for.
     * @return true if a match was found; group accessors are then valid until the next call.
     */
    public boolean match(final byte[] data,
                         final int regionFrom,
                         final int from,
                         final int to,
                         final Anchoring anchoring) {
        if (regionFrom < 0 || to > data.length || regionFrom > to
            || from < regionFrom || from > to) {
            throw new IndexOutOfBoundsException(
                    "search from " + from + " within region [" + regionFrom + ", " + to
                    + ") outside array of length " + data.length);
        }
        this.data = data;
        this.regionFrom = regionFrom;
        this.regionTo = to;
        this.matched = false;
        return endgame && anchoring != Anchoring.ANCHORED
                ? endgameSearch(from, anchoring)
                : run(from, anchoring);
    }

    /** Matches at exactly {@code from}. */
    public boolean matchesAt(final byte[] data, final int from) {
        return match(data, from, data.length, Anchoring.ANCHORED);
    }

    /**
     * Matches within {@code [from, to)}.
     *
     * <p><b>The array must hold the caller's data up to its length.</b> A region may end
     * mid-character, so deciding whether a match can begin at {@code to} means reading the
     * byte after it — right for a slice of a fully populated array, wrong for a buffer reused
     * across reads, where the bytes past the data are the previous read's. A caller reusing a
     * buffer must blank the tail (as {@code Executor.stream} does) or pass an array that ends
     * where its data ends; leaving it stale makes a legal empty match at the region end fail,
     * silently and according to what was there before.
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
        this.matched = false;
        return endgame && anchoring != Anchoring.ANCHORED
                ? endgameSearch(from, anchoring)
                : run(from, anchoring);
    }

    /**
     * The end-anchored accelerations (§6 Phases 2 and 4), one site ahead of the dispatcher
     * so the five search loops stay byte-identical. A bounded pattern's unanchored search
     * starts at {@code regionTo - tailSpan}: no earlier candidate can produce a match
     * ending at the region end, so leftmost within the window is leftmost overall and
     * captures are untouched. An unbounded one asks the reverse finder for the leftmost
     * start and verifies it with a forward anchored attempt — the proven machinery gives
     * the authoritative match and captures; a refused proposal is never trusted as
     * NO_MATCH, only a finder miss is.
     *
     * <p>This body lives out of line deliberately: both entry points test the one
     * {@code endgame} flag inline and call this only when it holds, so the
     * match() -> run() -> searchPlan() inline chain keeps the size the dispatcher split
     * bought — Phase 4's first cut routed every match through a grown dispatch method and
     * the scan-plan rows paid up to -46% to the documented cliff.
     */
    private boolean endgameSearch(final int from, final Anchoring anchoring) {
        if (tailSpan >= 0) {
            return run(regionTo - from > tailSpan
                    ? regionTo - tailSpan
                    : from, anchoring);
        }
        final int start = reverse.findStart(data, regionFrom, from, regionTo);
        if (start < 0) {
            matched = false;
            return false;
        }
        if (run(start, Anchoring.ANCHORED)) {
            return true;
        }
        // The safety valve: fall through to the unaccelerated scan.
        return run(from, anchoring);
    }

    private boolean run(final int from, final Anchoring anchoring) {
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
            return !splitsCharacter(from) && attempt(from) >= 0;
        }
        return searchPlan(from);
    }

    /** Pinned to the tree engine: a structural bailout is contained, not fallen from. */
    private boolean runPinnedTree(final int from, final boolean anchored) {
        Arrays.fill(slots, -1);
        try {
            final int end = tree.search(data, regionFrom, from, regionTo,
                    anchored, slots);
            matched = end >= 0;
            return matched;
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
    private boolean runFancy(final int from, final boolean anchored) {
        Arrays.fill(slots, -1);
        if (tree != null) {
            try {
                final int end = tree.search(data, regionFrom, from, regionTo,
                        anchored, slots);
                matched = end >= 0;
                return matched;
            } catch (final NodeTree.Bailout e) {
                Arrays.fill(slots, -1); // a clean rerun, not a resume
            }
        }
        final int end = fancy.search(data, regionFrom, from, regionTo,
                anchored, slots);
        matched = end >= 0;
        return matched;
    }

    /**
     * An ambiguous pattern. The tree engine first at every region size — it measured faster
     * than every flat engine on all 36 automaton corpus patterns (D32) — and the simulation
     * remains both fallback and guarantee: whatever the engines give up on, one machine
     * finishes in linear time. A pinned bounded backtracker still runs here, which is what
     * keeps it under differential test.
     */
    private boolean runLinear(final int from, final boolean anchored) {
        Arrays.fill(slots, -1);
        if (backtracker != null) {
            if (!backtracker.canRun(regionTo - regionFrom, BACKTRACK_BUDGET_BYTES)) {
                throw new IllegalStateException(
                        "backtracking was pinned but cannot run this pattern over "
                        + (regionTo - regionFrom) + " bytes");
            }
            final int end = backtracker.search(
                    data, regionFrom, from, regionTo, anchored, slots);
            matched = end >= 0;
            return matched;
        }
        if (tree != null) {
            try {
                final int end = tree.search(data, regionFrom, from, regionTo,
                        anchored, slots);
                matched = end >= 0;
                return matched;
            } catch (final NodeTree.Bailout | MatchLimitException e) {
                Arrays.fill(slots, -1); // the linear engine answers instead
            }
        }
        // The VM searches for the leftmost match itself, advancing every live thread
        // together, rather than restarting an attempt at each offset.
        final int end = vm.search(data, regionFrom, from, regionTo,
                anchored, slots);
        matched = end >= 0;
        return matched;
    }

    /** The scan plan's unanchored search: the leftmost start whose attempt succeeds. */
    private boolean searchPlan(final int from) {
        final byte[] firstBytes = plan.firstBytes();
        final var leadingAnchor = plan.leadingAnchor();
        // The minimum-length gate earns its keep on short unanchored searches — per-match
        // datetime fell 43% the day it was removed on buffer-CSV evidence alone, a lesson in
        // guarding both suites — and since the dispatcher split it no longer costs CSV its
        // inlining cliff.
        int lastStart = regionTo - plan.minLength();
        // An input-anchored pattern cannot start past the region start, so the scan ends
        // there. Decided once, out here, so the line-anchored scan pays nothing for it.
        if (leadingAnchor != null && leadingAnchor != Hir.Kind.START_LINE) {
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
            if (firstBytes != null && firstBytes[data[start] & 0xFF] == 0) {
                // Skip offsets that cannot begin a match. A pattern that can match empty has no
                // first-byte table, and every offset has to be tried; a pattern with one is not
                // nullable, so minLength >= 1 caps the loop at regionTo - 1 and the read is in
                // bounds (the D37 audit's proof — the start == regionTo test it replaced was
                // the deleted edge iteration's, dead since complete views).
                continue;
            }
            if (attempt(start) >= 0) {
                return true;
            }
        }
        return false;
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
     * Whether a match starting at {@code at} would begin inside a character instead of on one.
     * The contract lives on the UTF-8 spelling in {@link Utf8#splitsCharacter}.
     * <p>The phase-4 audit's correctness fix is preserved rather than reverted: a single-byte
     * form has no interior to split, so under RAW or a table a match may still start at bytes
     * 0x80–0xBF, which is the silent unseeding that audit found. What changed is only the
     * spelling — a guarded static call rather than {@code pattern.form().splitsCharacter(...)}.
     * It is the same answer by the sealed set: of the three forms
     * {@link stroom.shapeshifter.regex.internal.ByteForm} permits, the two single-byte ones
     * answer {@code false} unconditionally and the only multi-byte one is UTF-8. A fourth form
     * would break that equivalence, which is why {@code ByteFormInvariantTest} pins it.
     * <p>The spelling matters because this gate runs once per candidate start position — for a
     * {@code (?m)} miss it <em>is</em> the search loop — and the interface call does not inline
     * there. Measured on the encoding plan's before/after pair, 2026-09-03: the polymorphic form
     * cost 5.5× on {@code AnchoredSearchBenchmark} line_miss, 14% on
     * {@code EndAnchoredSearchBenchmark} BOUNDED_HIT and 3% on per-match weblog. Caching the form
     * in a field recovers none of the first and a third of the second; only devirtualising the
     * call recovers all three (design 06 §1).
     */
    private boolean splitsCharacter(final int at) {
        return !singleByteForm && Utf8.splitsCharacter(data, at);
    }

    private int attempt(final int start) {
        Arrays.fill(slots, -1);
        final int end = PlanRunner.run(plan, data, regionFrom, start, regionTo, slots);
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
