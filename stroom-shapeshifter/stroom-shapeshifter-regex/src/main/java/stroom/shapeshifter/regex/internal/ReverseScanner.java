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
 * Runs a {@link Reverse} program backwards over the input to find where a match ending at
 * the region end begins — a Pike-style simulation ({@link PikeVm}'s discipline: all threads
 * advance together, each program state visited at most once per position, so the walk is
 * {@code O(input × program)}) that consumes bytes right to left, seeded exactly once at the
 * region end. For a tail like {@code ([^\\]+)$} it touches only the match's own bytes and
 * stops at the first backslash, where the forward search would seed a candidate at every
 * position in the region.
 *
 * <p>Priority does not exist here: this is pure reachability. Every position where the
 * program's MATCH state is live is the start of some match ending at the region end, and
 * the smallest such position that does not split a character is the leftmost start — the
 * one leftmost-first semantics wants. The forward anchored attempt at that start then
 * produces the authoritative match and captures. A miss is trusted: reachability over a
 * cleanly-reversible program is exact, and the differential suite is the warrant.
 *
 * <p>Not thread safe: one instance per {@code ByteMatcher}, like every engine.
 */
public final class ReverseScanner {

    private final Nfa nfa;
    private final Closures closures;

    private ThreadList current;
    private ThreadList next;

    public ReverseScanner(final Nfa nfa) {
        this.nfa = nfa;
        this.closures = nfa.closures();
        this.current = new ThreadList(nfa.size());
        this.next = new ThreadList(nfa.size());
    }

    /**
     * The smallest offset in {@code [from, to]} at which a match ending at {@code to}
     * begins, or -1 for none. {@code contextEnd} bounds the character-boundary probe the
     * same way it does for the forward start gates.
     */
    public int findStart(final byte[] data,
                         final int regionFrom,
                         final int from,
                         final int to,
                         final int contextEnd) {
        current.clear();
        next.clear();
        addThread(current, 0, data, regionFrom, to, to);

        int best = -1;
        for (int pos = to; ; pos--) {
            if (current.matchLive
                && !nfa.form.splitsCharacter(data, pos, contextEnd)) {
                best = pos; // positions only decrease, so the last recorded is the smallest
            }
            if (pos <= from || current.size == 0) {
                break;
            }

            next.clear();
            final int value = data[pos - 1] & 0xFF;
            for (int i = 0; i < current.size; i++) {
                final int pc = current.pcs[i];
                switch (nfa.op[pc]) {
                    case Nfa.BYTE_RANGE -> {
                        if (value >= nfa.a[pc] && value <= nfa.b[pc]) {
                            addThread(next, nfa.next[pc], data, regionFrom, to, pos - 1);
                        }
                    }
                    case Nfa.BYTE_CLASS -> {
                        if (nfa.classes[nfa.a[pc]][value] != 0) {
                            addThread(next, nfa.next[pc], data, regionFrom, to, pos - 1);
                        }
                    }
                    case Nfa.BYTE_DISPATCH -> {
                        final int successor = nfa.dispatch[nfa.a[pc]][value];
                        if (successor >= 0) {
                            addThread(next, successor, data, regionFrom, to, pos - 1);
                        }
                    }
                    default -> {
                        // MATCH consumes nothing and epsilon states are resolved by the
                        // closures, so nothing else can appear in a list.
                    }
                }
            }

            final ThreadList swap = current;
            current = next;
            next = swap;
        }
        return best;
    }

    /** {@link PikeVm}'s addThread, without the capture rows the finder does not keep. The
     * assertion kinds are the original pattern's — their predicates are positional and the
     * reversed walk visits the same positions, so they are evaluated unchanged. */
    private void addThread(final ThreadList list,
                           final int pc,
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
            list.add(target);
            if (nfa.op[target] == Nfa.MATCH) {
                // Sticky per position, cleared with the list: the per-position "is a start
                // live here" test is one field read instead of a scan of the list — the
                // audit priced the scan at O(threads) per input byte of the backward walk.
                list.matchLive = true;
            }
        }
    }

    /** {@link PikeVm}'s thread list, without slots — the finder carries no captures. */
    private static final class ThreadList {

        private final int[] pcs;
        private final int[] seenAt;
        private int generation;
        private int size;
        private boolean matchLive;

        ThreadList(final int programSize) {
            this.pcs = new int[programSize];
            this.seenAt = new int[programSize];
            Arrays.fill(seenAt, -1);
        }

        void clear() {
            size = 0;
            matchLive = false;
            if (++generation == Integer.MIN_VALUE) {
                // The same wrap guard as the Pike VM's, for the same disease.
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

        void add(final int pc) {
            pcs[size++] = pc;
        }
    }
}
