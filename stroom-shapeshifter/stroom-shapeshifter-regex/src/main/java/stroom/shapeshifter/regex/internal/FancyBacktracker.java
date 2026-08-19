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

import stroom.shapeshifter.regex.MatchLimitException;

import java.util.Arrays;

/**
 * Unbounded backtracking, for the constructs that are not regular: backreferences, lookaround,
 * atomic groups, and {@code \G}. The architecture is fancy-regex's — a backtracker layered over
 * an RE2-style core, taking only the patterns whose syntax asks for it — hence the name.
 *
 * <h2>Why the {@link Backtracker}'s bound cannot apply</h2>
 * The bounded engine abandons any second arrival at an (instruction, position) pair, because the
 * rest of the program and the rest of the input are the same both times. With a backreference
 * they are not: what {@code \1} matches depends on what group 1 captured on the way, so two
 * arrivals at the same pair can genuinely differ, and no key of tractable size closes that —
 * backreference matching is NP-complete. This engine therefore explores without a visited set,
 * and contains the worst case with a step budget instead: a pathological pattern-input pair
 * raises {@link MatchLimitException} after a bounded amount of work rather than hanging a
 * pipeline thread. Termination without the budget is still guaranteed — every loop either
 * consumes a byte or is routed out by the empty-iteration guard — but termination alone can be
 * exponentially far away, which is what the budget is for.
 *
 * <h2>Nested matching</h2>
 * Lookaround and atomic groups run their bodies as sub-programs, each with its own engine
 * instance sharing this one's step budget, {@code hitEnd} flag and capture slots. An atomic
 * group is precisely a nested match whose choice points are discarded: the child returns its
 * preferred match and the parent keeps only that. A lookbehind runs its sub-program at each
 * candidate start — bounded by the body's byte-length range, which is why an unbounded
 * lookbehind is refused at compile time — and requires the nested match to end exactly at the
 * cursor.
 * <p>
 * Capture writes from a nested match must survive it (a capture inside a lookahead is a capture)
 * yet still be restored when the parent backtracks past it, so before every nested call the
 * parent logs the whole slot array to its undo log. Where the outcome means nothing may persist
 * — the nested match failed, or a negative lookaround — the parent unwinds to that point at
 * once.
 *
 * <h2>Streaming</h2>
 * The same conservative contract as the bounded engine: {@link PlanRunner#NEED_MORE} whenever
 * any explored path — including one inside a lookahead — reached the edge of a window that can
 * still grow. A lookbehind is the exception: its sub-match ends at the cursor by definition, so
 * bytes arriving at the window's right edge cannot change it, and it does not record edge
 * contact.
 */
public final class FancyBacktracker {

    /**
     * Instructions dispatched per search before {@link MatchLimitException}. fancy-regex ships
     * the same idea at the same order of magnitude. At the ~50 ns per step this engine measures,
     * the budget caps a pathological search somewhere around ten milliseconds.
     */
    private static final long STEP_BUDGET = 1_000_000;

    /** State one search shares across the root engine and every nested one. */
    private static final class Context {

        private long steps;
        private boolean hitEnd;

        /** Where this search started, which is what {@code \G} asserts against. */
        private int searchStart;
    }

    private final Nfa nfa;
    private final byte[] firstBytes;
    private final Context context;
    private final FancyBacktracker[] children;

    /** Iteration-start positions for the empty-iteration guard, indexed by mark id. */
    private final int[] markPos;

    // The choice points still to try, as an explicit stack — same shape as the bounded engine.
    private int[] stackPc = new int[64];
    private int[] stackPos = new int[64];
    private int[] stackUndo = new int[64];
    private int stackSize;

    // Writes to undo when a choice point is resumed. Indices below the slot count are capture
    // slots; the rest are mark positions, offset by the slot count.
    private int[] undoSlot = new int[64];
    private int[] undoValue = new int[64];
    private int undoSize;

    public FancyBacktracker(final Nfa nfa) {
        this(nfa, new Context());
    }

    private FancyBacktracker(final Nfa nfa, final Context context) {
        this.nfa = nfa;
        this.firstBytes = nfa.firstBytes();
        this.context = context;
        this.children = new FancyBacktracker[nfa.subs.length];
        for (int i = 0; i < nfa.subs.length; i++) {
            children[i] = new FancyBacktracker(nfa.subs[i], context);
        }
        int marks = 0;
        for (int pc = 0; pc < nfa.size(); pc++) {
            if (nfa.op[pc] == Nfa.MARK || nfa.op[pc] == Nfa.PROGRESS) {
                marks = Math.max(marks, nfa.a[pc] + 1);
            }
        }
        this.markPos = new int[marks];
    }

    /**
     * Searches for a match, with the same contract as {@link PikeVm#search}.
     *
     * @return the match end offset, or {@link PlanRunner#NO_MATCH}, or {@link PlanRunner#NEED_MORE}.
     * @throws MatchLimitException if the search exhausts its step budget.
     */
    public int search(final byte[] data,
                      final int regionFrom,
                      final int start,
                      final int to,
                      final boolean anchored,
                      final boolean complete,
                      final int[] slots) {
        context.steps = STEP_BUDGET;
        context.hitEnd = false;
        context.searchStart = start;

        for (int at = start; at <= to; at++) {
            if (at < to && Utf8.isContinuation(data[at])) {
                continue; // a match may not begin inside a character
            }
            if (firstBytes != null && (at == to || firstBytes[data[at] & 0xFF] == 0)) {
                if (at == to) {
                    context.hitEnd = true;
                }
                if (anchored) {
                    break;
                }
                continue;
            }
            Arrays.fill(slots, -1);
            final int end = attempt(data, regionFrom, at, to, -1, true, slots);
            if (end >= 0) {
                // Conservative like the bounded engine: a match on a window that can still grow
                // is only final if nothing explored reached the edge and the match itself stops
                // short of it.
                return !complete && (context.hitEnd || end == to)
                        ? PlanRunner.NEED_MORE
                        : end;
            }
            if (anchored) {
                break;
            }
        }
        return !complete && context.hitEnd
                ? PlanRunner.NEED_MORE
                : PlanRunner.NO_MATCH;
    }

    /**
     * One start position, explored depth first in preference order.
     *
     * @param requireEnd if non-negative, only a path ending exactly there may match — how a
     *                   lookbehind pins its sub-match to the cursor.
     * @param recordEdge whether reaching {@code to} is contact with the window edge. False
     *                   inside a lookbehind, whose {@code to} is the cursor rather than the edge.
     * @return the match end offset, or -1.
     */
    private int attempt(final byte[] data,
                        final int regionFrom,
                        final int start,
                        final int to,
                        final int requireEnd,
                        final boolean recordEdge,
                        final int[] slots) {
        stackSize = 0;
        undoSize = 0;
        int pc = 0;
        int pos = start;

        for (;;) {
            if (--context.steps < 0) {
                throw new MatchLimitException(
                        "the search took more than " + STEP_BUDGET + " steps, which only a "
                        + "pathological combination of pattern and input does; the pattern "
                        + "backtracks catastrophically and needs restructuring");
            }
            switch (nfa.op[pc]) {
                case Nfa.BYTE_RANGE -> {
                    if (pos >= to) {
                        edge(recordEdge);
                    } else if ((data[pos] & 0xFF) >= nfa.a[pc] && (data[pos] & 0xFF) <= nfa.b[pc]) {
                        pc = nfa.next[pc];
                        pos++;
                        continue;
                    }
                }
                case Nfa.BYTE_CLASS -> {
                    if (pos >= to) {
                        edge(recordEdge);
                    } else if (nfa.classes[nfa.a[pc]][data[pos] & 0xFF] != 0) {
                        pc = nfa.next[pc];
                        pos++;
                        continue;
                    }
                }
                case Nfa.BYTE_DISPATCH -> {
                    if (pos >= to) {
                        edge(recordEdge);
                    } else {
                        final int successor = nfa.dispatch[nfa.a[pc]][data[pos] & 0xFF];
                        if (successor >= 0) {
                            pc = successor;
                            pos++;
                            continue;
                        }
                    }
                }
                case Nfa.SPLIT -> {
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
                case Nfa.MARK -> {
                    undo(slots.length + nfa.a[pc], markPos[nfa.a[pc]]);
                    markPos[nfa.a[pc]] = pos;
                    pc++;
                    continue;
                }
                case Nfa.PROGRESS -> {
                    // An iteration that consumed nothing leaves the loop instead of repeating;
                    // taking the exit rather than dying is what lets the empty match win.
                    pc = pos > markPos[nfa.a[pc]]
                            ? pc + 1
                            : nfa.b[pc];
                    continue;
                }
                case Nfa.ASSERT -> {
                    if (pos >= to) {
                        edge(recordEdge);
                    }
                    final Hir.Kind kind = Hir.Kind.VALUES[nfa.a[pc]];
                    final boolean holds = kind == Hir.Kind.PREVIOUS_MATCH_END
                            ? pos == context.searchStart
                            : PikeVm.assertionHolds(kind, data, regionFrom, to, pos);
                    if (holds) {
                        pc++;
                        continue;
                    }
                }
                case Nfa.BACKREF -> {
                    final int advanced = matchBackref(data, pos, to, pc, recordEdge, slots);
                    if (advanced >= 0) {
                        pos += advanced;
                        pc = nfa.next[pc];
                        continue;
                    }
                }
                case Nfa.LOOK -> {
                    final boolean negated = (nfa.b[pc] & Nfa.LOOK_NEGATED) != 0;
                    final boolean behind = (nfa.b[pc] & Nfa.LOOK_BEHIND) != 0;
                    final int mark = undoSize;
                    logSlots(slots);
                    final boolean matched = behind
                            ? matchBehind(data, regionFrom, pos, nfa.a[pc], slots)
                            : children[nfa.a[pc]]
                                      .attempt(data, regionFrom, pos, to, -1, recordEdge, slots)
                              >= 0;
                    if (matched != negated) {
                        if (negated) {
                            // Nothing from inside a negative lookaround survives it.
                            unwind(mark, slots);
                        }
                        pc++;
                        continue;
                    }
                    unwind(mark, slots);
                }
                case Nfa.ATOMIC -> {
                    final int mark = undoSize;
                    logSlots(slots);
                    final int end = children[nfa.a[pc]]
                            .attempt(data, regionFrom, pos, to, -1, recordEdge, slots);
                    if (end >= 0) {
                        // The child's preferred match, kept; its alternatives died with its
                        // stack, which is the whole meaning of an atomic group.
                        pos = end;
                        pc = nfa.next[pc];
                        continue;
                    }
                    unwind(mark, slots);
                }
                case Nfa.MATCH -> {
                    // The match span was written by SAVE 0 and SAVE 1 on the way here — and a
                    // sub-program has neither, because a nested match must not disturb it.
                    if (requireEnd < 0 || pos == requireEnd) {
                        return pos;
                    }
                    // A lookbehind must end exactly at the cursor; any other end is a failed
                    // path, not a match.
                }
                default -> throw new IllegalStateException("unexpected instruction " + nfa.op[pc]);
            }

            // This path is done. Resume the most recent choice point, undoing what it wrote.
            if (stackSize == 0) {
                unwind(0, slots); // leave shared state as it was found: this may be a sub-match
                return -1;
            }
            stackSize--;
            unwind(stackUndo[stackSize], slots);
            pc = stackPc[stackSize];
            pos = stackPos[stackSize];
        }
    }

    /**
     * A lookbehind: the sub-program must match ending exactly at {@code cursor}, so every start
     * in the window the body's length bounds allow is tried, nearest first. Preference between
     * starts is not observable — only captures could see it, and the JDK agrees with
     * nearest-first on those.
     */
    private boolean matchBehind(final byte[] data,
                                final int regionFrom,
                                final int cursor,
                                final int sub,
                                final int[] slots) {
        final int min = nfa.subMin[sub];
        final int max = nfa.subMax[sub];
        final int lowest = Math.max(regionFrom, max == Analysis.UNBOUNDED_LENGTH
                ? regionFrom
                : cursor - max);
        for (int at = cursor - min; at >= lowest; at--) {
            if (at > regionFrom && at < cursor && Utf8.isContinuation(data[at])) {
                continue; // a sub-match may not begin inside a character either
            }
            if (children[sub].attempt(data, regionFrom, at, cursor, cursor, false, slots) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches the bytes group {@code a[pc]} captured against the input at {@code pos}.
     *
     * @return how many input bytes were consumed, or -1 for no match. A reference to a group
     * that did not participate fails, as in the JDK and fancy-regex.
     */
    private int matchBackref(final byte[] data,
                             final int pos,
                             final int to,
                             final int pc,
                             final boolean recordEdge,
                             final int[] slots) {
        final int group = nfa.a[pc];
        final int from = slots[2 * group];
        final int until = slots[2 * group + 1];
        if (from < 0 || until < 0) {
            return -1;
        }
        if ((nfa.b[pc] & Nfa.BACKREF_FOLD) == 0) {
            final int length = until - from;
            final int available = to - pos;
            final int compare = Math.min(length, available);
            for (int i = 0; i < compare; i++) {
                if (data[from + i] != data[pos + i]) {
                    return -1;
                }
            }
            if (length > available) {
                edge(recordEdge); // every byte in hand agreed; more input could complete it
                return -1;
            }
            context.steps -= length;
            return length;
        }
        return matchBackrefFolded(data, pos, to, from, until, recordEdge,
                (nfa.b[pc] & Nfa.BACKREF_UNICODE) != 0);
    }

    /**
     * The case-insensitive comparison walks both spans a code point at a time, so the consumed
     * length can differ from the captured length when folding crosses byte-length boundaries.
     * The folding is the JDK's — upper case equal, or lower case equal — which keeps the oracle
     * usable on exactly this corner.
     */
    private int matchBackrefFolded(final byte[] data,
                                   final int pos,
                                   final int to,
                                   final int from,
                                   final int until,
                                   final boolean recordEdge,
                                   final boolean unicode) {
        int captured = from;
        int input = pos;
        while (captured < until) {
            if (input >= to) {
                edge(recordEdge);
                return -1;
            }
            final int wanted = Utf8.decode(data, captured, until);
            if (wanted < 0) {
                return -1; // the captured span is not whole characters; nothing can fold-match it
            }
            final int have = Utf8.decode(data, input, to);
            if (have < 0) {
                edge(recordEdge); // a character truncated by the window edge
                return -1;
            }
            if (wanted != have && !foldedEqual(wanted, have, unicode)) {
                return -1;
            }
            captured += Utf8.encodedLength(wanted);
            input += Utf8.encodedLength(have);
            context.steps--;
        }
        return input - pos;
    }

    private static boolean foldedEqual(final int a, final int b, final boolean unicode) {
        if (!unicode) {
            // (?i-u): ASCII letters fold, nothing else does.
            return (a | 0x20) == (b | 0x20)
                   && (a | 0x20) >= 'a' && (a | 0x20) <= 'z'
                   && a <= 0x7F && b <= 0x7F;
        }
        return Character.toUpperCase(a) == Character.toUpperCase(b)
               || Character.toLowerCase(a) == Character.toLowerCase(b);
    }

    private void edge(final boolean recordEdge) {
        if (recordEdge) {
            context.hitEnd = true;
        }
    }

    /** Logs every capture slot, so a nested match's writes can be undone as one unit. */
    private void logSlots(final int[] slots) {
        for (int i = 0; i < slots.length; i++) {
            undo(i, slots[i]);
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

    private void undo(final int index, final int previous) {
        if (undoSize == undoSlot.length) {
            undoSlot = Arrays.copyOf(undoSlot, undoSize * 2);
            undoValue = Arrays.copyOf(undoValue, undoSize * 2);
        }
        undoSlot[undoSize] = index;
        undoValue[undoSize] = previous;
        undoSize++;
    }

    private void unwind(final int target, final int[] slots) {
        while (undoSize > target) {
            undoSize--;
            final int index = undoSlot[undoSize];
            if (index < slots.length) {
                slots[index] = undoValue[undoSize];
            } else {
                markPos[index - slots.length] = undoValue[undoSize];
            }
        }
    }
}
