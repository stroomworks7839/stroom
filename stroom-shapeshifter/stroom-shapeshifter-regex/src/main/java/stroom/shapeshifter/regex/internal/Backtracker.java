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
 * Depth-first matching over the same program the {@link PikeVm} simulates, bounded so that it
 * cannot take exponential time.
 *
 * <h2>Why this engine exists</h2>
 * The simulation advances every live thread in lockstep, which is what makes it linear whatever
 * the input — and what makes it cost a flat 21 to 27 nanoseconds per input byte regardless of the
 * pattern, against 1.5 to 9.5 for a scan plan. That cost is per input <em>position</em>: the outer
 * loop, the thread-list swap, the closure indirections, the capture bookkeeping. Measurement put
 * the whole of tier 2's penalty there rather than in anything that scales with the pattern
 * (design/05-engine-benchmarks.md §8).
 * <p>
 * Backtracking pays none of it. One path is followed at a time, captures are written where they
 * happen instead of being copied between threads, and no per-position structures are maintained.
 * The price is that a naive backtracker is exponential, which is exactly the failure mode this
 * engine exists to make unrepresentable.
 *
 * <h2>What bounds it</h2>
 * A bitset of every (instruction, position) pair already tried. Reaching a pair a second time
 * cannot produce a match the first visit would not have found — the rest of the program and the
 * rest of the input are the same — so the second visit is abandoned. That caps the work at
 * program size × input length, which is the simulation's bound, while the typical case does far
 * less. Captures differ between the two arrivals, but only paths that <em>fail</em> are pruned,
 * and a failing path's captures are never used.
 * <p>
 * The bitset is why this engine is bounded in memory as well as time. It was the default for
 * affordable searches from D26 until D32, when the tree engine measured faster on every corpus
 * pattern; since then it runs only when pinned — which keeps it compiled, correct and under
 * differential test, as the correctness argument's third witness from a second algorithm
 * family.
 *
 * <h2>What it will not run</h2>
 * A program containing the empty-iteration guard ({@link Nfa#MARK} and {@link Nfa#PROGRESS}).
 * Those make the future depend on where the current iteration began, not only on the instruction
 * and the position, so the bitset would prune a path whose outcome genuinely differs. Rather than
 * widen the key, this engine refuses them ({@link #canRun}); unpinned they run elsewhere, and a
 * pin meets the refusal as an error. They come from repetitions whose body can match empty,
 * which the compiler warns about anyway.
 */
public final class Backtracker {

    private final Nfa nfa;
    private final byte[] firstBytes;
    private final int startAnchor;

    /** Whether the program is one this engine may run at all; see the class comment. */
    private final boolean supported;

    /**
     * One <em>byte</em> per (instruction, position), stamped with the search's generation rather
     * than cleared between searches. A bitset is eight times smaller but has to be zeroed on every
     * search, and over the short records this engine is chosen for that clearing costs more than
     * the matching: the array is proportional to the program, which is thousands of instructions,
     * while the input is tens of bytes. Stamping moves that cost to once every 127 searches.
     */
    private byte[] visited;
    private int visitedPositions;
    private int generation;

    // The choice points still to try, as parallel arrays: an explicit stack, since a program can
    // nest more deeply than the Java stack would tolerate.
    private int[] stackPc = new int[64];
    private int[] stackPos = new int[64];
    private int[] stackUndo = new int[64];
    private int stackSize;

    // Slot writes to undo when a choice point is resumed.
    private int[] undoSlot = new int[64];
    private int[] undoValue = new int[64];
    private int undoSize;

    public Backtracker(final Nfa nfa) {
        this.nfa = nfa;
        this.firstBytes = nfa.firstBytes();
        this.startAnchor = nfa.startAnchor();
        boolean guards = false;
        for (int pc = 0; pc < nfa.size(); pc++) {
            if (nfa.op[pc] == Nfa.MARK || nfa.op[pc] == Nfa.PROGRESS) {
                guards = true;
                break;
            }
        }
        this.supported = !guards;
    }

    /** Whether this engine can run the program at all, and within the caller's memory budget. */
    public boolean canRun(final int length, final int budgetBytes) {
        return supported && (long) nfa.size() * (length + 1) <= budgetBytes;
    }

    /**
     * Searches for a match, with the same contract as {@link PikeVm#search}.
     *
     * @return the match end offset, or {@link PlanRunner#NO_MATCH}.
     */
    public int search(final byte[] data,
                      final int regionFrom,
                      final int start,
                      final int to,
                      final boolean anchored,
                      final int[] slots) {
        prepare(regionFrom, to);

        // Attempting stops once fewer bytes remain than the shortest match spans.
        int lastStart = to - nfa.minLength;
        // An input-anchored pattern cannot start past the region start, so the walk ends
        // there. Decided once, out here, so the line-anchored walk pays nothing for it.
        if (startAnchor == Nfa.ANCHOR_INPUT) {
            lastStart = Math.min(lastStart, regionFrom);
        }
        for (int at = start; at <= lastStart; at++) {
            if (at < to && at > regionFrom && startAnchor != Nfa.ANCHOR_NONE
                && (startAnchor == Nfa.ANCHOR_INPUT || data[at - 1] != '\n')) {
                // See the fancy engine: the cheapest and, for anchored patterns, the most
                // selective gate.
                if (anchored) {
                    break;
                }
                continue;
            }
            if (nfa.form.splitsCharacter(data, at)) {
                // A match may not begin inside a character — and an anchored search may not
                // begin anywhere else, so it is over (as the simulation already answers).
                if (anchored) {
                    break;
                }
                continue;
            }
            // A table exists only for a non-nullable pattern, so minLength >= 1 caps the loop
            // at to - 1: at == to is unreachable and the read is in bounds (the D37 audit's
            // proof; the dropped test was the deleted edge iteration's).
            if (firstBytes != null && firstBytes[data[at] & 0xFF] == 0) {
                if (anchored) {
                    break;
                }
                continue;
            }
            Arrays.fill(slots, -1);
            if (attempt(data, regionFrom, at, to, slots)) {
                return slots[1];
            }
            if (anchored) {
                break;
            }
        }
        return PlanRunner.NO_MATCH;
    }

    /** One start position, explored depth first in preference order. */
    private boolean attempt(final byte[] data,
                            final int regionFrom,
                            final int start,
                            final int to,
                            final int[] slots) {
        stackSize = 0;
        undoSize = 0;
        int pc = 0;
        int pos = start;

        for (;;) {
            if (visit(pc, pos, regionFrom)) {
                switch (nfa.op[pc]) {
                    case Nfa.BYTE_RANGE -> {
                        if (pos < to && (data[pos] & 0xFF) >= nfa.a[pc] && (data[pos] & 0xFF) <= nfa.b[pc]) {
                            pc = nfa.next[pc];
                            pos++;
                            continue;
                        }
                    }
                    case Nfa.BYTE_CLASS -> {
                        if (pos < to && nfa.classes[nfa.a[pc]][data[pos] & 0xFF] != 0) {
                            pc = nfa.next[pc];
                            pos++;
                            continue;
                        }
                    }
                    case Nfa.BYTE_DISPATCH -> {
                        if (pos < to) {
                            final int successor = nfa.dispatch[nfa.a[pc]][data[pos] & 0xFF];
                            if (successor >= 0) {
                                pc = successor;
                                pos++;
                                continue;
                            }
                        }
                    }
                    case Nfa.SPLIT -> {
                        // The alternative is kept for later, so the preferred branch is followed
                        // first and a match found down it wins — which is leftmost-first.
                        push(nfa.b[pc], pos);
                        pc = nfa.a[pc];
                        continue;
                    }
                    case Nfa.JUMP -> {
                        pc = nfa.a[pc];
                        continue;
                    }
                    case Nfa.SAVE -> {
                        undo(nfa.a[pc], slots[nfa.a[pc]]);
                        slots[nfa.a[pc]] = pos;
                        pc++;
                        continue;
                    }
                    case Nfa.ASSERT -> {
                        if (Words.assertionHolds(Hir.Kind.VALUES[nfa.a[pc]],
                                data, regionFrom, to, pos, nfa.form)) {
                            pc++;
                            continue;
                        }
                    }
                    case Nfa.MATCH -> {
                        // The match span was written by SAVE 0 and SAVE 1 on the way here,
                        // exactly as in the other engines.
                        return true;
                    }
                    default -> {
                        // MARK and PROGRESS never appear: canRun refuses such programs.
                        throw new IllegalStateException("unexpected instruction " + nfa.op[pc]);
                    }
                }
            }

            // This path is done. Resume the most recent choice point, undoing what it wrote.
            if (stackSize == 0) {
                return false;
            }
            stackSize--;
            unwind(stackUndo[stackSize], slots);
            pc = stackPc[stackSize];
            pos = stackPos[stackSize];
        }
    }

    /**
     * True if this (instruction, position) has not been tried yet, and marks it. A second arrival
     * is abandoned: it has the same program and the same input left, so it cannot find a match the
     * first arrival missed.
     */
    private boolean visit(final int pc, final int pos, final int regionFrom) {
        final int index = pc * visitedPositions + (pos - regionFrom);
        if (visited[index] == generation) {
            return false;
        }
        visited[index] = (byte) generation;
        return true;
    }

    private void prepare(final int regionFrom, final int to) {
        visitedPositions = to - regionFrom + 1;
        final int cells = nfa.size() * visitedPositions;
        if (visited == null || visited.length < cells) {
            visited = new byte[cells];
            generation = 1;
            return;
        }
        generation++;
        if (generation > Byte.MAX_VALUE) {
            // Only now does anything have to be zeroed, and only once every 127 searches —
            // and the WHOLE array, not this search's prefix. A partial clear once left stale
            // generation marks above a small search's cells, and 126 searches later a
            // different input met its own year-old marks and lost a real match to them.
            Arrays.fill(visited, (byte) 0);
            generation = 1;
        }
    }

    private void push(final int pc, final int pos) {
        if (stackSize == stackPc.length) {
            stackPc = Arrays.copyOf(stackPc, stackSize * 2);
            stackPos = Arrays.copyOf(stackPos, stackSize * 2);
            stackUndo = Arrays.copyOf(stackUndo, stackSize * 2);
        }
        stackPc[stackSize] = pc;
        stackPos[stackSize] = pos;
        stackUndo[stackSize] = undoSize;
        stackSize++;
    }

    private void undo(final int slot, final int previous) {
        if (undoSize == undoSlot.length) {
            undoSlot = Arrays.copyOf(undoSlot, undoSize * 2);
            undoValue = Arrays.copyOf(undoValue, undoSize * 2);
        }
        undoSlot[undoSize] = slot;
        undoValue[undoSize] = previous;
        undoSize++;
    }

    private void unwind(final int to, final int[] slots) {
        while (undoSize > to) {
            undoSize--;
            slots[undoSlot[undoSize]] = undoValue[undoSize];
        }
    }
}
