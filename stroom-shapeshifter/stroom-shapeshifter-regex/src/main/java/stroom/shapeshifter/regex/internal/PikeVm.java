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

package stroom.shapeshifter.regex.internal;

import java.util.Arrays;

/**
 * Simulates an {@link Nfa} — a Pike VM: all live threads advance together, one input byte at a
 * time, so no position is ever revisited.
 * <p>
 * Consequences that matter to the design rather than to this class:
 * <ul>
 *   <li><b>Linear time.</b> Each thread visits each program instruction at most once per input
 *       byte, so the worst case is {@code O(input × program)}. Catastrophic backtracking is not
 *       merely unlikely, it is unrepresentable.</li>
 *   <li><b>No recursion.</b> The failure mode Stroom's current DS3 catches — a
 *       {@code StackOverflowError} from a deep backtracking search — cannot arise.</li>
 *   <li><b>Self-contained state.</b> The thread list is the entire execution state. D37
 *       retired streaming but keeps resume-mid-input in escrow on exactly this property: if
 *       it is ever worth building, this is the engine that can, and this is why.</li>
 * </ul>
 * Threads are held in priority order and the first to reach {@code MATCH} wins, with all
 * lower-priority threads discarded. That produces leftmost-first (Perl) semantics, matching
 * {@code java.util.regex} rather than POSIX leftmost-longest.
 * <p>
 * Not thread safe: one instance per {@code ByteMatcher}.
 *
 * <h2>Allocation</h2>
 * The obvious implementation copies a thread's capture slots on every {@code SAVE}, which
 * measured at 21.9 MB of garbage per 165 KB of input — 133× the input size. Thread rows are
 * instead preallocated per list and reused across input positions, so a search allocates nothing
 * at all once warm.
 *
 * <h2>Closures</h2>
 * Epsilon chains are resolved at compile time by {@link Closures}, so adding a thread is a loop
 * over a precomputed array rather than a recursive walk re-derived at every input position.
 */
public final class PikeVm {

    private final Nfa nfa;
    private final Closures closures;
    private final byte[] firstBytes;
    private final int startAnchor;

    private ThreadList current;
    private ThreadList next;

    /** All -1, forever: {@code add} copies it into a fresh row and writes there, so the same
     * array seeds every new attempt. */
    private final int[] seed;

    /** Slots of the best match found so far, valid while {@link #hasMatch} is set. */
    private final int[] matched;
    private boolean hasMatch;

    /** One past the last consultable byte — window state, set with the window rather than
     * passed per search: a ninth {@code search} argument measured −8.6% on the line-anchored
     * scan ({@code AnchoredSearchBenchmark} simulate/line_miss, 2026-08-24) with the body
     * untouched — the frame cost alone — and cost the tree engine an inlining coin-flip
     * worth −21% a fork. Bound as state, every real-workload row sits at baseline; the
     * store's ~0.35 ns shows only on the tree engine's 8 ns instant-rejection rows
     * (−2–4%, accepted in ISSUES.md). */
    private int contextEnd;

    /** Binds the context: one past the last byte {@link #search} may consult. */
    public void setContextEnd(final int contextEnd) {
        this.contextEnd = contextEnd;
    }

    public PikeVm(final Nfa nfa) {
        this.nfa = nfa;
        this.closures = nfa.closures();
        this.firstBytes = nfa.firstBytes();
        this.startAnchor = nfa.startAnchor();
        this.current = new ThreadList(nfa.size(), nfa.slotCount());
        this.next = new ThreadList(nfa.size(), nfa.slotCount());
        this.seed = new int[nfa.slotCount()];
        this.matched = new int[nfa.slotCount()];
        Arrays.fill(seed, -1);
    }

    /**
     * Searches for a match. The context must be bound via {@link #setContextEnd} before
     * each call, or the answer at the region edge is wrong.
     *
     * @param anchored true to require the match to begin at {@code start}.
     * @param slots    filled with the winning capture slots when a match is found.
     * @return the match end offset, or {@link PlanRunner#NO_MATCH}.
     */
    public int search(final byte[] data,
                      final int regionFrom,
                      final int start,
                      final int to,
                      final boolean anchored,
                      final int[] slots) {
        // No bind assert here, unlike the other three engines: the ~18 bytes it adds to this
        // method measured -2% on anchored_hit and destabilised its forks (2026-08-24), so the
        // contract sentence above carries the guard alone.
        current.clear();
        next.clear();
        hasMatch = false;

        // No thread seeds where fewer bytes remain than the shortest match spans, so seeding —
        // and the loop, once nothing is live — stops there.
        int lastSeed = to - nfa.minLength;
        // An input-anchored pattern cannot start past the region start, so the seeding ends
        // there. Decided once, out here, so the line-anchored scan pays nothing for it.
        if (startAnchor == Nfa.ANCHOR_INPUT) {
            lastSeed = Math.min(lastSeed, regionFrom);
        }

        for (int pos = start; ; pos++) {
            if (current.size == 0 && hasMatch) {
                break; // nothing live can beat the match already found
            }
            if (!hasMatch && (!anchored || pos == start) && pos <= lastSeed) {
                if (canStartAt(data, regionFrom, to, pos)) {
                    // A new attempt starting here, at lowest priority so earlier starts win.
                    addThread(current, 0, seed, data, regionFrom, to, pos);
                }
            }
            if (current.size == 0 && (anchored || pos > to || pos > lastSeed)) {
                break;
            }

            next.clear();
            final int value = pos < to
                    ? data[pos] & 0xFF
                    : -1;

            for (int i = 0; i < current.size; i++) {
                final int pc = current.pcs[i];
                // The row is passed straight into the closure rather than copied: the closure
                // restores every slot it touches, so the row is unchanged on return.
                final int[] threadSlots = current.slots[i];
                switch (nfa.op[pc]) {
                    case Nfa.BYTE_RANGE -> {
                        if (value >= 0 && value >= nfa.a[pc] && value <= nfa.b[pc]) {
                            addThread(next, nfa.next[pc], threadSlots, data, regionFrom, to,
                                    pos + 1);
                        }
                    }
                    case Nfa.BYTE_CLASS -> {
                        if (value >= 0 && nfa.classes[nfa.a[pc]][value] != 0) {
                            addThread(next, nfa.next[pc], threadSlots, data, regionFrom, to,
                                    pos + 1);
                        }
                    }
                    case Nfa.BYTE_DISPATCH -> {
                        if (value >= 0) {
                            final int successor = nfa.dispatch[nfa.a[pc]][value];
                            if (successor >= 0) {
                                addThread(next, successor, threadSlots, data, regionFrom, to,
                                        pos + 1);
                            }
                        }
                    }
                    case Nfa.MATCH -> {
                        System.arraycopy(threadSlots, 0, matched, 0, matched.length);
                        hasMatch = true;
                        // Everything after this in the list is lower priority, so it cannot
                        // produce a preferred match. This is what makes the semantics
                        // leftmost-first rather than leftmost-longest.
                        i = current.size;
                    }
                    default -> {
                        // Epsilon instructions are resolved in addThread, so nothing else can
                        // appear in a thread list.
                    }
                }
            }

            final ThreadList swap = current;
            current = next;
            next = swap;

            if (pos >= to) {
                break;
            }
        }

        if (!hasMatch) {
            return PlanRunner.NO_MATCH;
        }
        System.arraycopy(matched, 0, slots, 0, Math.min(matched.length, slots.length));
        return matched[1];
    }

    /** Whether the start anchor, if any, permits a match to begin here. */
    private boolean anchorHoldsAt(final byte[] data,
                                  final int regionFrom,
                                  final int to,
                                  final int pos) {
        // Whether an interior position is a line start is already settled by the byte before
        // it — the same class of determination as the first-byte table.
        return startAnchor == Nfa.ANCHOR_NONE || pos == regionFrom
               || (startAnchor != Nfa.ANCHOR_INPUT && pos <= to && data[pos - 1] == '\n');
    }

    /** Whether a match could begin at this byte at all, per the program's first-byte table. */
    private boolean canStartAt(final byte[] data,
                               final int regionFrom,
                               final int to,
                               final int pos) {
        if (!anchorHoldsAt(data, regionFrom, to, pos)) {
            return false;
        }
        if (nfa.form.splitsCharacter(data, pos, contextEnd)) {
            return false;
        }
        if (firstBytes == null) {
            return true; // the pattern can match empty, so it could start anywhere
        }
        // A table exists only for a non-nullable pattern, so minLength >= 1 caps seeding at
        // to - 1: pos < to always holds here and the read is in bounds (the D37 audit's proof).
        return firstBytes[data[pos] & 0xFF] != 0;
    }

    /**
     * Adds every thread reachable from {@code pc} without consuming input.
     * <p>
     * The reachable set, the captures to record and the assertions to check were all worked out
     * at compile time, so this walks a flat array in preference order rather than recursing
     * through the program. Deduplicating by target bounds the work per input position.
     */
    private void addThread(final ThreadList list,
                           final int pc,
                           final int[] slots,
                           final byte[] data,
                           final int regionFrom,
                           final int to,
                           final int pos) {
        final int[] reachable = closures.targets(pc);
        for (int i = 0; i < reachable.length; i++) {
            final int target = reachable[i];
            if (list.contains(target)) {
                continue;
            }

            final int[] pathAsserts = closures.asserts(pc, i);
            boolean holds = true;
            for (final int kind : pathAsserts) {
                if (!Words.assertionHolds(Hir.Kind.VALUES[kind], data, regionFrom, to, pos, nfa.form)) {
                    holds = false;
                    break;
                }
            }
            if (!holds) {
                continue;
            }

            list.mark(target);
            // Every SAVE on the path happened at this same position, since none of the
            // instructions between here and the target consumed a byte.
            list.add(target, slots, closures.saves(pc, i), pos);
        }
    }

    /**
     * Threads in priority order, with a generation-stamped set for O(1) deduplication.
     * <p>
     * Slot rows are allocated on first use and then reused for the life of the matcher, so
     * steady-state matching does not allocate. Rows are allocated lazily rather than up front
     * because the list is sized for the whole program while the number of threads live at once
     * is usually far smaller.
     */
    private static final class ThreadList {

        private final int[] pcs;
        private final int[][] slots;
        private final int[] seenAt;
        private final int slotCount;
        private int generation;
        private int size;

        ThreadList(final int programSize, final int slotCount) {
            this.pcs = new int[programSize];
            this.slots = new int[programSize][];
            this.seenAt = new int[programSize];
            this.slotCount = slotCount;
            Arrays.fill(seenAt, -1);
            this.generation = 0;
        }

        void clear() {
            size = 0;
            if (++generation == Integer.MIN_VALUE) {
                // Once per 2^31 clears — roughly two gigabytes of input through one matcher
                // — the counter wraps, and stale stamps from a full cycle ago would read as
                // current. The bounded backtracker lost real matches to exactly this disease
                // at its 127-generation scale; the branch is one predictable compare.
                Arrays.fill(seenAt, -1);
                generation = 0;
            }
        }

        boolean contains(final int pc) {
            return seenAt[pc] == generation;
        }

        void mark(final int pc) {
            seenAt[pc] = generation;
        }

        void add(final int pc, final int[] threadSlots, final int[] pathSaves, final int pos) {
            if (slots[size] == null) {
                slots[size] = new int[slotCount];
            }
            final int[] row = slots[size];
            System.arraycopy(threadSlots, 0, row, 0, slotCount);
            for (final int slot : pathSaves) {
                row[slot] = pos;
            }
            pcs[size] = pc;
            size++;
        }
    }
}
