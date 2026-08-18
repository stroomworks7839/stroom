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

import java.util.ArrayList;
import java.util.List;

/**
 * Epsilon closures resolved once, at compile time.
 * <p>
 * A Thompson NFA is mostly instructions that consume no input — {@code SPLIT}, {@code JUMP},
 * {@code SAVE}, {@code ASSERT}. Simulating it naively means walking those chains again at every
 * input position to work out which byte-consuming instructions are actually reachable, which
 * profiling showed to be where the time goes: about 100 ns per input byte, re-deriving the same
 * answer each time.
 * <p>
 * The chains are static, so the answer is too. For each instruction this precomputes the ordered
 * list of byte-consuming instructions (and {@code MATCH}) reachable without consuming input,
 * along with the captures to record and the assertions to check on the way. At run time adding a
 * thread becomes a loop over a small array instead of a recursive walk.
 *
 * <h2>What cannot be precomputed</h2>
 * Assertions depend on where in the input the closure is being taken, so they are carried along
 * each path and evaluated at run time. A target may therefore appear more than once, reached by
 * paths with different assertions: the run time takes the first whose assertions hold, which is
 * the highest-priority one that can be taken. Only an unasserted path closes a target off.
 * <p>
 * Every instruction on an epsilon path sits at the same input position, so evaluating them all at
 * the current position is exactly right — as is recording every {@code SAVE} on the path at that
 * position.
 *
 * <h2>Which iteration a path is in</h2>
 * The one thing besides the assertions that a path carries is the set of repetitions whose
 * current iteration began at this same position, which {@code MARK} records and {@code PROGRESS}
 * reads. That is enough to decide, at compile time, whether an iteration consumed anything —
 * exactly one byte is consumed between one closure and the next — so the empty-iteration guard
 * costs nothing at run time.
 *
 * <h2>Ordering</h2>
 * The walk is depth-first in preference order and the first arrival at an instruction wins, so
 * the resulting list preserves the thread priority that gives leftmost-first semantics.
 */
public final class Closures {

    /** Marks a program may use, bounded by the bits in the mask the walk carries. */
    public static final int MAX_MARKS = 63;

    /** For each instruction, the reachable byte-consuming instructions in preference order. */
    private final int[][] targets;

    /** Capture slots to record, per instruction and per target. */
    private final int[][][] saves;

    /** Assertion kinds that must hold, per instruction and per target. */
    private final int[][][] asserts;

    private static final int[] NONE = new int[0];

    Closures(final Nfa nfa) {
        final int size = nfa.size();
        targets = new int[size][];
        saves = new int[size][][];
        asserts = new int[size][][];

        final OnPath onPath = new OnPath(size);
        for (int pc = 0; pc < size; pc++) {
            final List<int[]> found = new ArrayList<>();
            final boolean[] reached = new boolean[size];
            walk(nfa, pc, new ArrayList<>(), new ArrayList<>(), onPath, 0L, reached, found);

            final int count = found.size();
            targets[pc] = new int[count];
            saves[pc] = new int[count][];
            asserts[pc] = new int[count][];
            for (int i = 0; i < count; i++) {
                final int[] entry = found.get(i);
                targets[pc][i] = entry[0];
                final int saveCount = entry[1];
                final int[] pathSaves = new int[saveCount];
                System.arraycopy(entry, 3, pathSaves, 0, saveCount);
                final int[] pathAsserts = new int[entry[2]];
                System.arraycopy(entry, 3 + saveCount, pathAsserts, 0, entry[2]);
                saves[pc][i] = saveCount == 0
                        ? NONE
                        : pathSaves;
                asserts[pc][i] = entry[2] == 0
                        ? NONE
                        : pathAsserts;
            }
        }
    }

    /**
     * Depth-first in preference order, collecting the saves and assertions seen on the way.
     * {@code onPath} prevents an epsilon cycle — {@code (a*)*} and friends — from looping, and
     * {@code reached} keeps only the first, highest-priority arrival at each target.
     */
    private static void walk(final Nfa nfa,
                             final int pc,
                             final List<Integer> pathSaves,
                             final List<Integer> pathAsserts,
                             final OnPath onPath,
                             final long marks,
                             final boolean[] reached,
                             final List<int[]> found) {
        // The state a path is in is the instruction *and* which iterations began at this same
        // position — not the instruction alone. A path legitimately reaches the same instruction
        // twice, once before entering an iteration and once inside it, and treating that as a
        // cycle loses the second visit along with everything downstream of it.
        if (!onPath.push(pc, marks)) {
            return;
        }
        step(nfa, pc, pathSaves, pathAsserts, onPath, marks, reached, found);
        onPath.pop(pc);
    }

    private static void step(final Nfa nfa,
                             final int pc,
                             final List<Integer> pathSaves,
                             final List<Integer> pathAsserts,
                             final OnPath onPath,
                             final long marks,
                             final boolean[] reached,
                             final List<int[]> found) {
        switch (nfa.op[pc]) {
            case Nfa.JUMP -> walk(nfa, nfa.a[pc], pathSaves, pathAsserts, onPath, marks,
                    reached, found);
            case Nfa.SPLIT -> {
                walk(nfa, nfa.a[pc], pathSaves, pathAsserts, onPath, marks, reached, found);
                walk(nfa, nfa.b[pc], pathSaves, pathAsserts, onPath, marks, reached, found);
            }
            case Nfa.SAVE -> {
                pathSaves.add(nfa.a[pc]);
                walk(nfa, pc + 1, pathSaves, pathAsserts, onPath, marks, reached, found);
                pathSaves.removeLast();
            }
            case Nfa.ASSERT -> {
                pathAsserts.add(nfa.a[pc]);
                walk(nfa, pc + 1, pathSaves, pathAsserts, onPath, marks, reached, found);
                pathAsserts.removeLast();
            }
            case Nfa.MARK -> {
                // Records that the iteration this path is inside began here, at this same input
                // position. Nothing is written anywhere at run time — see below.
                walk(nfa, pc + 1, pathSaves, pathAsserts, onPath, marks | (1L << nfa.a[pc]),
                        reached, found);
            }
            case Nfa.PROGRESS -> {
                // Whether the iteration made progress is decided *here*, at compile time, and
                // needs no run-time state: exactly one byte is consumed between one closure and
                // the next, so the iteration consumed nothing precisely when its MARK lies on
                // this same epsilon path. If it does, the only way on is the loop exit.
                walk(nfa, (marks & (1L << nfa.a[pc])) != 0
                                ? nfa.b[pc]
                                : pc + 1,
                        pathSaves, pathAsserts, onPath, marks, reached, found);
            }
            default -> {
                // A byte-consuming instruction or MATCH: the end of an epsilon path.
                if (reached[pc]) {
                    return;
                }
                // Only a path that will certainly be taken closes a target off. An asserted path
                // may fail at run time, and if it had claimed the target, a later unasserted
                // arrival would have been dropped and the thread never added at all: (?:\b|)a
                // against "ba" found nothing, because the \b path shadowed the empty one.
                if (pathAsserts.isEmpty()) {
                    reached[pc] = true;
                }
                final int[] entry = new int[3 + pathSaves.size() + pathAsserts.size()];
                entry[0] = pc;
                entry[1] = pathSaves.size();
                entry[2] = pathAsserts.size();
                for (int i = 0; i < pathSaves.size(); i++) {
                    entry[3 + i] = pathSaves.get(i);
                }
                for (int i = 0; i < pathAsserts.size(); i++) {
                    entry[3 + pathSaves.size() + i] = pathAsserts.get(i);
                }
                found.add(entry);
            }
        }
    }

    int[] targets(final int pc) {
        return targets[pc];
    }

    int[] saves(final int pc, final int index) {
        return saves[pc][index];
    }

    int[] asserts(final int pc, final int index) {
        return asserts[pc][index];
    }

    /**
     * The instructions on the current path, each with the mark sets it was reached under.
     * <p>
     * A per-instruction stack rather than a hash set of (instruction, marks) pairs: a program
     * with no empty-bodied repetition has no marks at all, so the mark set is always zero and
     * this costs one array read and one comparison — which is what the plain visited flag it
     * replaced cost. Building the closures is a per-pattern cost, but it is quadratic in the
     * program size, so a hash lookup here was worth about three times the compile time.
     */
    private static final class OnPath {

        private final long[][] marks;
        private final int[] depth;

        OnPath(final int size) {
            marks = new long[size][];
            depth = new int[size];
        }

        /** False if this instruction is already on the path under the same mark set. */
        boolean push(final int pc, final long mask) {
            long[] entries = marks[pc];
            final int used = depth[pc];
            for (int i = 0; i < used; i++) {
                if (entries[i] == mask) {
                    return false;
                }
            }
            if (entries == null) {
                entries = new long[4];
                marks[pc] = entries;
            } else if (used == entries.length) {
                final long[] grown = new long[used * 2];
                System.arraycopy(entries, 0, grown, 0, used);
                marks[pc] = grown;
                entries = grown;
            }
            entries[used] = mask;
            depth[pc] = used + 1;
            return true;
        }

        void pop(final int pc) {
            depth[pc]--;
        }
    }
}
