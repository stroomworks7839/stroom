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
 * Unbounded backtracking over the flat program, for the constructs that are not regular:
 * backreferences, lookaround, atomic groups, and {@code \G}. The architecture is fancy-regex's
 * — a backtracker layered over an RE2-style core — hence the name. Since D31 the tree engine is
 * the fancy tier's primary and this is its structural fallback: an explicit stack cannot run
 * out of call-stack depth, so what bails out of the tree finishes here. It also stays pinnable,
 * as a differential witness from a second implementation family.
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
 * instance sharing this one's step budget and capture slots. An atomic
 * group is precisely a nested match whose choice points are discarded: the child returns its
 * preferred match and the parent keeps only that. A lookbehind runs its sub-program at each
 * candidate start — bounded by the body's byte-length range, which is why an unbounded
 * lookbehind is refused at compile time — and requires the nested match to end exactly at the
 * cursor.
 * <p>
 * Capture writes from a nested match must survive it (a capture inside a lookahead is a capture)
 * yet still be restored when the parent backtracks past it — so before a nested call whose
 * sub-program can write a capture at all, the parent logs the slot array to its undo log. Most
 * lookaround and atomic bodies capture nothing, and for those the journalling is skipped
 * entirely; where the outcome means nothing may persist — the nested match failed, or a
 * negative lookaround — the parent unwinds to the logged point at once.
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

        /** Where this search started, which is what {@code \G} asserts against. */
        private int searchStart;
    }

    private final Nfa nfa;
    private final byte[] firstBytes;
    private final int startAnchor;
    private final Context context;
    private final FancyBacktracker[] children;

    /**
     * Whether each sub-program contains a SAVE at all. Most lookaround and atomic bodies
     * capture nothing, and journalling the slot array around a nested match that cannot write
     * it was measured as pure per-entry overhead.
     */
    private final boolean[] childWritesCaptures;

    /** Iteration-start positions for the empty-iteration guard, indexed by mark id. */
    private final int[] markPos;

    // The choice points still to try, as an explicit stack — same shape as the bounded engine.
    // A CLASS_STAR backoff frame is marked by a complemented pc (always negative), with the
    // run's floor in stackAux; an ordinary frame leaves stackAux unused.
    private int[] stackPc = new int[64];
    private int[] stackPos = new int[64];
    private int[] stackUndo = new int[64];
    private int[] stackAux = new int[64];
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
        this.startAnchor = nfa.startAnchor();
        this.context = context;
        this.children = new FancyBacktracker[nfa.subs.length];
        this.childWritesCaptures = new boolean[nfa.subs.length];
        for (int i = 0; i < nfa.subs.length; i++) {
            children[i] = new FancyBacktracker(nfa.subs[i], context);
            for (int pc = 0; pc < nfa.subs[i].size(); pc++) {
                if (nfa.subs[i].op[pc] == Nfa.SAVE) {
                    childWritesCaptures[i] = true;
                    break;
                }
            }
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
     * @return the match end offset, or {@link PlanRunner#NO_MATCH}.
     * @throws MatchLimitException if the search exhausts its step budget.
     */
    public int search(final byte[] data,
                      final int regionFrom,
                      final int start,
                      final int to,
                      final boolean anchored,
                      final int[] slots) {
        context.steps = STEP_BUDGET;
        context.searchStart = start;

        int lastStart = to - nfa.minLength;
        // An input-anchored pattern cannot start past the region start, so the walk ends
        // there. Decided once, out here, so the line-anchored walk pays nothing for it.
        if (startAnchor == Nfa.ANCHOR_INPUT) {
            lastStart = Math.min(lastStart, regionFrom);
        }
        for (int at = start; at <= lastStart; at++) {
            // The anchor gate first, because it is the cheapest test and, for the patterns it
            // applies to, the most selective: a line-anchored pattern over record data skips
            // from one newline to the next instead of attempting at every byte.
            if (at < to && at > regionFrom && startAnchor != Nfa.ANCHOR_NONE
                && (startAnchor == Nfa.ANCHOR_INPUT || data[at - 1] != '\n')) {
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
            final int end = attempt(data, regionFrom, at, to, -1, slots);
            if (end >= 0) {
                return end;
            }
            if (anchored) {
                break;
            }
        }
        return PlanRunner.NO_MATCH;
    }

    /**
     * One start position, explored depth first in preference order.
     *
     * @param requireEnd if non-negative, only a path ending exactly there may match — how a
     *                   lookbehind pins its sub-match to the cursor.
     * @return the match end offset, or -1.
     */
    private int attempt(final byte[] data,
                        final int regionFrom,
                        final int start,
                        final int to,
                        final int requireEnd,
                        final int[] slots) {
        stackSize = 0;
        undoSize = 0;
        int pc = 0;
        int pos = start;

        // Local copies of the program arrays: the interpreter loop reads them at every
        // dispatch, and a local lets the JIT keep them in registers where an instance field
        // of another object might be reloaded.
        final int[] op = nfa.op;
        final int[] a = nfa.a;
        final int[] b = nfa.b;
        final int[] next = nfa.next;
        final byte[][] classes = nfa.classes;
        final int[][] dispatch = nfa.dispatch;

        for (;;) {
            // The budget is charged where work is provably done — bytes consumed, choice
            // points pushed or resumed, sub-matches entered — rather than at every dispatch:
            // every loop in a program either consumes or pushes, so the count still bounds
            // the work, and the shared field stays off the hottest path.
            switch (op[pc]) {
                case Nfa.BYTE_RANGE -> {
                    if (pos < to && (data[pos] & 0xFF) >= a[pc] && (data[pos] & 0xFF) <= b[pc]) {
                        pc = next[pc];
                        pos++;
                        continue;
                    }
                }
                case Nfa.BYTE_CLASS -> {
                    if (pos < to && classes[a[pc]][data[pos] & 0xFF] != 0) {
                        pc = next[pc];
                        pos++;
                        continue;
                    }
                }
                case Nfa.BYTE_DISPATCH -> {
                    if (pos < to) {
                        final int successor = dispatch[a[pc]][data[pos] & 0xFF];
                        if (successor >= 0) {
                            pc = successor;
                            pos++;
                            continue;
                        }
                    }
                }
                case Nfa.SPLIT -> {
                    push(b[pc], pos);
                    pc = a[pc];
                    continue;
                }
                case Nfa.JUMP -> {
                    pc = a[pc];
                    continue;
                }
                case Nfa.SAVE -> {
                    undo(a[pc], slots[a[pc]]);
                    slots[a[pc]] = pos;
                    pc++;
                    continue;
                }
                case Nfa.MARK -> {
                    undo(slots.length + a[pc], markPos[a[pc]]);
                    markPos[a[pc]] = pos;
                    pc++;
                    continue;
                }
                case Nfa.PROGRESS -> {
                    // An iteration that consumed nothing leaves the loop instead of repeating;
                    // taking the exit rather than dying is what lets the empty match win.
                    pc = pos > markPos[a[pc]]
                            ? pc + 1
                            : b[pc];
                    continue;
                }
                case Nfa.ASSERT -> {
                    final Hir.Kind kind = Hir.Kind.VALUES[a[pc]];
                    final boolean holds = kind == Hir.Kind.PREVIOUS_MATCH_END
                            ? pos == context.searchStart
                            : Words.assertionHolds(kind, data, regionFrom, to, pos, nfa.form);
                    if (holds) {
                        pc++;
                        continue;
                    }
                }
                case Nfa.BACKREF -> {
                    final int advanced = matchBackref(data, pos, to, pc, slots);
                    if (advanced >= 0) {
                        pos += advanced;
                        pc = next[pc];
                        continue;
                    }
                }
                case Nfa.LOOK -> {
                    final boolean negated = (b[pc] & Nfa.LOOK_NEGATED) != 0;
                    final boolean behind = (b[pc] & Nfa.LOOK_BEHIND) != 0;
                    final int mark = undoSize;
                    if (childWritesCaptures[a[pc]]) {
                        logSlots(slots);
                    }
                    final boolean matched = behind
                            ? matchBehind(data, regionFrom, pos, to, a[pc], slots)
                            : children[a[pc]]
                                      .attempt(data, regionFrom, pos, to, -1, slots)
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
                case Nfa.CLASS_STAR -> {
                    final byte[] table = classes[a[pc]];
                    if (b[pc] == 0) {
                        // Greedy: measure the whole run in one tight loop, keep one backoff
                        // frame, and try the continuation from the far end first.
                        int end = pos;
                        while (end < to && table[data[end] & 0xFF] != 0) {
                            end++;
                        }
                        context.steps -= end - pos;
                        if (end > pos) {
                            pushStar(pc, end, pos);
                        }
                        pos = end;
                    } else if (pos < to && table[data[pos] & 0xFF] != 0) {
                        // Lazy: prefer the continuation here; the frame extends on resume.
                        pushStar(pc, pos, pos);
                    }
                    pc++;
                    continue;
                }
                case Nfa.ATOMIC -> {
                    final int mark = undoSize;
                    if (childWritesCaptures[a[pc]]) {
                        logSlots(slots);
                    }
                    final int end = children[a[pc]]
                            .attempt(data, regionFrom, pos, to, -1, slots);
                    if (end >= 0) {
                        // The child's preferred match, kept; its alternatives died with its
                        // stack, which is the whole meaning of an atomic group.
                        pos = end;
                        pc = next[pc];
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
                default -> throw new IllegalStateException("unexpected instruction " + op[pc]);
            }

            // This path is done. Resume the most recent choice point, undoing what it wrote.
            // A CLASS_STAR frame may itself be exhausted, so resuming is a loop.
            resume:
            for (;;) {
                if (stackSize == 0) {
                    unwind(0, slots); // leave shared state as found: this may be a sub-match
                    return -1;
                }
                stackSize--;
                if (--context.steps < 0) {
                    throw limitExceeded();
                }
                unwind(stackUndo[stackSize], slots);
                final int frame = stackPc[stackSize];
                if (frame >= 0) {
                    pc = frame;
                    pos = stackPos[stackSize];
                    break;
                }
                final int starPc = ~frame;
                final byte[] table = classes[a[starPc]];
                final int floor = stackAux[stackSize];
                if (b[starPc] == 0) {
                    // Greedy backoff: one whole character shorter, continuation next.
                    int cur = stackPos[stackSize] - 1;
                    while (cur > floor && nfa.form.continuation(data[cur])) {
                        cur--;
                    }
                    if (cur > floor) {
                        pushStar(starPc, cur, floor);
                    }
                    pc = starPc + 1;
                    pos = cur;
                    break;
                }
                // Lazy extension: one whole character longer, if the class allows it.
                final int cur = stackPos[stackSize];
                if (cur >= to) {
                    continue resume;
                }
                if (table[data[cur] & 0xFF] == 0) {
                    continue resume; // the run cannot grow; this frame is spent
                }
                int grown = cur + 1;
                while (grown < to && nfa.form.continuation(data[grown])) {
                    grown++;
                }
                // Refuse only a character truncated by the region edge; on malformed input,
                // byte granularity matches what the greedy scan does. A whole character ends
                // before the region does, ends on a non-continuation byte, or spans exactly
                // its lead byte's announced length.
                final boolean truncatedByEdge = grown == to
                        && nfa.form.continuation(data[grown - 1])
                        && !nfa.form.singleByte()
                        && grown - cur != Utf8.sequenceLength(data[cur] & 0xFF);
                if (truncatedByEdge) {
                    continue resume;
                }
                context.steps--;
                pushStar(starPc, grown, floor);
                pc = starPc + 1;
                pos = grown;
                break;
            }
        }
    }

    /**
     * A lookbehind: the sub-program must match ending exactly at {@code cursor}, so every start
     * in the window the body's length bounds allow is tried, nearest first. Preference between
     * starts is not observable — only captures could see it, and the JDK agrees with
     * nearest-first on those.
     *
     * <p>The cursor pins where the body must end, and nothing more: the body keeps the region's
     * own {@code to}, so an assertion or a nested lookahead inside it reads the input past the
     * cursor exactly as it would outside. Handing it {@code cursor} as its view instead made
     * {@code (?<=a(?=bc))bc} fail and {@code (?<=a$)b} match, neither of which is what the
     * construct means.
     */
    private boolean matchBehind(final byte[] data,
                                final int regionFrom,
                                final int cursor,
                                final int to,
                                final int sub,
                                final int[] slots) {
        final int min = nfa.subMin[sub];
        final int max = nfa.subMax[sub];
        final int lowest = Math.max(regionFrom, max == Analysis.UNBOUNDED_LENGTH
                ? regionFrom
                : cursor - max);
        for (int at = cursor - min; at >= lowest; at--) {
            if (at < cursor && nfa.form.continuation(data[at])) {
                // A sub-match may not begin inside a character either. No regionFrom
                // exemption: the search gate has none, and a region that opens mid-character
                // is no better a place to start a lookbehind body than to start a match.
                // Deliberately not Utf8.splitsCharacter: at < cursor keeps the probe inside
                // consumed input, so the beyond-region clause can never apply here.
                continue;
            }
            if (children[sub].attempt(data, regionFrom, at, to, cursor, slots) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches the bytes group {@code a[pc]} captured against the input at {@code pos}, via the
     * shared {@link Backrefs} comparison.
     *
     * @return how many input bytes were consumed, or -1 for no match. A reference to a group
     * that did not participate fails, as in the JDK and fancy-regex.
     */
    private int matchBackref(final byte[] data,
                             final int pos,
                             final int to,
                             final int pc,
                             final int[] slots) {
        final int group = nfa.a[pc];
        final int from = slots[2 * group];
        final int until = slots[2 * group + 1];
        if (from < 0 || until < 0) {
            return -1;
        }
        final int consumed = Backrefs.compare(nfa.form, data, pos, to, from, until,
                (nfa.b[pc] & Nfa.BACKREF_FOLD) != 0,
                (nfa.b[pc] & Nfa.BACKREF_UNICODE) != 0);
        if (consumed == Backrefs.TRUNCATED) {
            return -1;
        }
        if (consumed >= 0) {
            context.steps -= consumed;
        }
        return consumed;
    }

    /** The step budget ran out; one message, wherever the budget happened to be charged. */
    private static MatchLimitException limitExceeded() {
        return new MatchLimitException(
                "the search took more than " + STEP_BUDGET + " steps, which only a "
                + "pathological combination of pattern and input does; the pattern "
                + "backtracks catastrophically and needs restructuring");
    }

    /** Logs every capture slot, so a nested match's writes can be undone as one unit. */
    private void logSlots(final int[] slots) {
        for (int i = 0; i < slots.length; i++) {
            undo(i, slots[i]);
        }
    }

    private void push(final int pc, final int pos) {
        if (--context.steps < 0) {
            throw limitExceeded();
        }
        if (stackSize == stackPc.length) {
            stackPc = Arrays.copyOf(stackPc, stackSize * 2);
            stackPos = Arrays.copyOf(stackPos, stackSize * 2);
            stackUndo = Arrays.copyOf(stackUndo, stackSize * 2);
            stackAux = Arrays.copyOf(stackAux, stackSize * 2);
        }
        stackPc[stackSize] = pc;
        stackPos[stackSize] = pos;
        stackUndo[stackSize] = undoSize;
        stackSize++;
    }

    /** A {@link Nfa#CLASS_STAR} frame: the run's current extent, and its floor in aux. */
    private void pushStar(final int pc, final int extent, final int floor) {
        push(~pc, extent);
        stackAux[stackSize - 1] = floor;
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
