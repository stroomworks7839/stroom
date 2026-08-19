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

import stroom.shapeshifter.regex.PatternCompileException;
import stroom.shapeshifter.regex.PatternCompileException.Reason;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles {@link Hir} into a Thompson NFA program.
 * <p>
 * Ordering encodes preference: the first target of a {@code SPLIT} is tried first, so a greedy
 * repetition points at its body and a lazy one points at its exit. The VM walks threads in that
 * order, which is what produces leftmost-first (Perl) semantics rather than POSIX
 * leftmost-longest.
 * <p>
 * Character classes become alternations of byte-range chains, using the same UTF-8 sequences as
 * the scan plan ({@link Utf8}), so both tiers accept exactly the same characters.
 */
public final class NfaCompiler {

    /** Guards against a pattern expanding into an unreasonable program. */
    private static final int MAX_INSTRUCTIONS = 64 * 1024;

    /** Bound on unrolling a counted repetition. */
    private static final int MAX_UNROLL = 1000;

    private final String pattern;
    private final List<int[]> instructions = new ArrayList<>();
    private final List<byte[]> classes = new ArrayList<>();
    private final List<int[]> dispatchTables = new ArrayList<>();

    /** Sub-programs compiled for lookaround and atomic groups, with lookbehind length bounds. */
    private final List<Nfa> subs = new ArrayList<>();
    private final List<int[]> subBounds = new ArrayList<>();

    /** Slot space shared with sub-programs, so a capture inside a lookahead lands where the
     * matcher expects it. */
    private int groupCount;
    private boolean multiline;

    /** Dispatch tables holding the "continues past the class" sentinel, patched once it is known. */
    private final List<Integer> tablesToPatch = new ArrayList<>();

    /** Ids handed out to the empty-iteration guards; see {@link #emitRepeat}. */
    private int marks;

    private NfaCompiler(final String pattern) {
        this.pattern = pattern;
    }

    public static Nfa compile(final Hir root,
                              final int groupCount,
                              final boolean multiline,
                              final String pattern) {
        final NfaCompiler compiler = new NfaCompiler(pattern);
        compiler.groupCount = groupCount;
        compiler.multiline = multiline;
        compiler.emit(Nfa.SAVE, 0, 0);
        compiler.emitNode(root);
        compiler.emit(Nfa.SAVE, 1, 0);
        compiler.emit(Nfa.MATCH, 0, 0);
        return compiler.build(groupCount, multiline);
    }

    /**
     * Compiles a lookaround or atomic-group body. The program shares the parent's slot space —
     * a capture inside a lookahead is a capture like any other — and has no {@code SAVE 0} /
     * {@code SAVE 1} wrapper, because a sub-match must not disturb the overall match span.
     */
    private Nfa compileSub(final Hir body) {
        final NfaCompiler compiler = new NfaCompiler(pattern);
        compiler.groupCount = groupCount;
        compiler.multiline = multiline;
        compiler.emitNode(body);
        compiler.emit(Nfa.MATCH, 0, 0);
        return compiler.build(groupCount, multiline);
    }

    private Nfa build(final int groups, final boolean multiline) {
        final int count = instructions.size();
        final int[] op = new int[count];
        final int[] a = new int[count];
        final int[] b = new int[count];
        final int[] next = new int[count];
        for (int i = 0; i < count; i++) {
            final int[] instruction = instructions.get(i);
            op[i] = instruction[0];
            a[i] = instruction[1];
            b[i] = instruction[2];
            next[i] = instruction[3] < 0
                    ? i + 1
                    : instruction[3];
        }
        final int[] mins = new int[subs.size()];
        final int[] maxes = new int[subs.size()];
        for (int i = 0; i < subs.size(); i++) {
            mins[i] = subBounds.get(i)[0];
            maxes[i] = subBounds.get(i)[1];
        }
        return new Nfa(op, a, b, next, classes.toArray(new byte[0][]),
                dispatchTables.toArray(new int[0][]),
                subs.toArray(new Nfa[0]), mins, maxes,
                2 * (groups + 1), groups, multiline);
    }

    private void emitNode(final Hir node) {
        switch (node) {
            case Hir.Empty ignored -> {
            }

            case Hir.Bytes bytes -> {
                for (final byte value : bytes.value()) {
                    emit(Nfa.BYTE_RANGE, value & 0xFF, value & 0xFF);
                }
            }

            case Hir.CharClass charClass -> emitClass(charClass);

            case Hir.Assertion assertion -> emit(Nfa.ASSERT, assertion.kind().ordinal(), 0);

            case Hir.Group group -> {
                if (group.capturing()) {
                    emit(Nfa.SAVE, 2 * group.index(), 0);
                    emitNode(group.body());
                    emit(Nfa.SAVE, 2 * group.index() + 1, 0);
                } else {
                    emitNode(group.body());
                }
            }

            case Hir.Concat concat -> concat.items().forEach(this::emitNode);

            case Hir.Alt alt -> emitAlternatives(alt.branches());

            case Hir.Repeat repeat -> emitRepeat(repeat);

            case Hir.Backref backref -> emit(Nfa.BACKREF, backref.index(),
                    (backref.caseInsensitive()
                            ? Nfa.BACKREF_FOLD
                            : 0)
                    | (backref.unicode()
                            ? Nfa.BACKREF_UNICODE
                            : 0));

            case Hir.Look look -> {
                final int[] bounds = Analysis.byteLength(look.body());
                if (look.behind() && bounds[1] == Analysis.UNBOUNDED_LENGTH) {
                    throw new PatternCompileException(Reason.UNSUPPORTED, pattern, -1,
                            "lookbehind has no maximum length, so there is no bound on how far "
                            + "back to try");
                }
                subs.add(compileSub(look.body()));
                subBounds.add(look.behind()
                        ? bounds
                        : new int[]{0, 0});
                emit(Nfa.LOOK, subs.size() - 1,
                        (look.negated()
                                ? Nfa.LOOK_NEGATED
                                : 0)
                        | (look.behind()
                                ? Nfa.LOOK_BEHIND
                                : 0));
            }

            case Hir.Atomic atomic -> {
                subs.add(compileSub(atomic.body()));
                subBounds.add(new int[]{0, 0});
                emit(Nfa.ATOMIC, subs.size() - 1, 0);
            }
        }
    }

    /**
     * A class becomes one table-driven instruction for its single-byte members, plus a <b>trie
     * over byte ranges with shared tails</b> for the multi-byte ones.
     *
     * <h2>Why not the obvious encoding</h2>
     * The obvious encoding is an alternation over every UTF-8 byte-range sequence the class
     * expands to. It is also catastrophic, and was: a Unicode {@code \w} is some seven hundred
     * code point ranges, and {@code (\w+):\s*(\S+)} compiled to <b>11,007 instructions against 9
     * for its ASCII equivalent, and ran 1,237× slower</b>. A simulation's cost per input byte is
     * proportional to the number of live threads, and a flat alternation makes every sequence a
     * live thread at every position.
     * <p>
     * Sharing the tails alone does not fix it, because the {@code SPLIT} dispatch is itself one
     * instruction per sequence. What is needed is sharing at both ends:
     * <ul>
     *   <li><b>Prefixes</b> — sequences beginning with the same byte range become one transition
     *       into a shared subtree, so the dispatch is over <em>distinct lead ranges</em> (of which
     *       there are a handful) rather than over sequences (of which there are hundreds).</li>
     *   <li><b>Suffixes</b> — subtrees that compile to the same thing are emitted once. Nearly
     *       every multi-byte sequence ends in {@code [80-BF]} runs, so this collapses the bulk of
     *       what remains.</li>
     * </ul>
     * Single-byte members stay a 256-entry table, which is one instruction for the whole of ASCII
     * and the case that matters most in practice.
     */
    private void emitClass(final Hir.CharClass charClass) {
        final int[][] sequences = Utf8.sequences(charClass.set());
        if (sequences.length == 0) {
            // An empty class can never match; a range no byte satisfies expresses that.
            emit(Nfa.BYTE_RANGE, 0x100, 0x100);
            return;
        }

        boolean multiByte = false;
        for (final int[] sequence : sequences) {
            multiByte |= sequence.length > 2;
        }
        if (!multiByte) {
            // Every member is one byte, so the class is one table test and needs no trie. Kept
            // as a byte table rather than a dispatch table: a quarter of the memory, and every
            // successor would be the same instruction anyway.
            final byte[] table = new byte[256];
            for (final int[] sequence : sequences) {
                for (int b = sequence[0]; b <= sequence[1]; b++) {
                    table[b] = 1;
                }
            }
            classes.add(table);
            emit(Nfa.BYTE_CLASS, classes.size() - 1, 0);
            return;
        }

        final Trie root = new Trie();
        for (final int[] sequence : sequences) {
            root.insert(sequence, 0);
        }

        // The trie is emitted children-first, so its instructions cannot sit at the class's entry
        // point — control would fall into whichever subtree happened to be emitted first. This
        // jump reserves the entry and steps over the trie to its root.
        final List<Integer> toExit = new ArrayList<>();
        final int entry = emit(Nfa.JUMP, 0, 0);
        instructions.get(entry)[1] = compileTrie(root, toExit, new HashMap<>());

        final int exit = nextPc();
        for (final int pc : toExit) {
            if (instructions.get(pc)[3] == EXIT) {
                instructions.get(pc)[3] = exit;
            }
        }
        for (final int table : tablesToPatch) {
            final int[] successors = dispatchTables.get(table);
            for (int value = 0; value < successors.length; value++) {
                if (successors[value] == EXIT) {
                    successors[value] = exit;
                }
            }
        }
        tablesToPatch.clear();
    }

    /** Marks a successor that continues past the class, whose target is not yet known. */
    private static final int EXIT = -2;

    /**
     * Emits a trie node's dispatch and returns its entry point. Children are emitted first, so a
     * node's transitions can name them directly and no jump is needed between levels.
     */
    private int compileTrie(final Trie node,
                            final List<Integer> toExit,
                            final Map<String, Integer> shared) {
        final int[] targets = new int[node.children.size()];
        for (int i = 0; i < node.children.size(); i++) {
            final Trie child = node.children.get(i);
            targets[i] = child.isEmpty()
                    ? EXIT                                // a leaf: continues past the class
                    : compileTrie(child, toExit, shared);
        }

        // Sibling ranges that lead to the same subtree are one range. UTF-8 scatters a class
        // across many lead bytes with identical continuations — every three-byte sequence from
        // E1 to EC continues [80-BF][80-BF] — so merging them collapses the dispatch from one
        // branch per lead byte to one per *distinct continuation*. Overlapping siblings merge
        // too: the alternatives all end at the same place, so which one accepts cannot matter.
        final List<int[]> transitions = new ArrayList<>();
        for (int i = 0; i < targets.length; i++) {
            transitions.add(new int[]{node.ranges.get(i)[0], node.ranges.get(i)[1], targets[i]});
        }
        transitions.sort((x, y) -> Integer.compare(x[0], y[0]));
        final List<int[]> merged = new ArrayList<>();
        for (final int[] transition : transitions) {
            final int[] last = merged.isEmpty()
                    ? null
                    : merged.getLast();
            if (last != null && last[2] == transition[2] && transition[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], transition[1]);
            } else {
                merged.add(transition);
            }
        }

        // Ranges that lead to the same subtree but are *not* adjacent cannot merge into a range,
        // and they are the common case: a class scattered across Unicode reaches the same
        // continuation from lead bytes with gaps between them. They can still merge into a single
        // table-driven branch, which is what actually matters — the simulation's cost per input
        // byte is the number of branches live at once, and measurement put that at 36 for
        // \w where the ASCII equivalent has 1. One branch per distinct continuation is the floor.
        final Map<Integer, List<int[]>> byTarget = new LinkedHashMap<>();
        for (final int[] transition : merged) {
            byTarget.computeIfAbsent(transition[2], ignored -> new ArrayList<>()).add(transition);
        }
        final List<int[]> dispatch = new ArrayList<>();
        for (final Map.Entry<Integer, List<int[]>> group : byTarget.entrySet()) {
            if (group.getValue().size() == 1) {
                dispatch.add(group.getValue().getFirst());
                continue;
            }
            final byte[] table = new byte[256];
            for (final int[] transition : group.getValue()) {
                for (int value = transition[0]; value <= transition[1]; value++) {
                    table[value] = 1;
                }
            }
            classes.add(table);
            // A table branch is marked by a lo of -1, carrying the table index in hi.
            dispatch.add(new int[]{-1, classes.size() - 1, group.getKey()});
        }

        // Two subtrees that dispatch identically are the same subtree, so emit it once. This is
        // what collapses the [80-BF] tails every multi-byte sequence ends in.
        final StringBuilder key = new StringBuilder();
        for (final int[] transition : dispatch) {
            key.append(transition[0]).append(':').append(transition[1]).append('>')
                    .append(transition[2]).append(',');
        }
        final Integer cached = shared.get(key.toString());
        if (cached != null) {
            return cached;
        }

        // One instruction for the whole node when no byte can take two branches. Every branch
        // ends at the same place, so the only thing a table cannot express is a byte with two
        // different continuations — which happens when two code point ranges produce overlapping
        // lead ranges with different tails, and is rare enough to leave on the branch path.
        final int[] successors = new int[256];
        java.util.Arrays.fill(successors, -1);
        boolean disjoint = true;
        for (final int[] transition : dispatch) {
            for (final int value : bytesOf(transition)) {
                if (successors[value] != -1) {
                    disjoint = false;
                    break;
                }
                successors[value] = transition[2];
            }
            if (!disjoint) {
                break;
            }
        }
        if (disjoint) {
            dispatchTables.add(successors);
            tablesToPatch.add(dispatchTables.size() - 1);
            final int pc = emit(Nfa.BYTE_DISPATCH, dispatchTables.size() - 1, 0);
            shared.put(key.toString(), pc);
            return pc;
        }

        final int entry = nextPc();
        for (int i = 0; i < dispatch.size(); i++) {
            final int[] transition = dispatch.get(i);
            if (i < dispatch.size() - 1) {
                final int split = emit(Nfa.SPLIT, 0, 0);
                instructions.get(split)[1] = nextPc();
                record(toExit, emitTransition(transition));
                instructions.get(split)[2] = nextPc();
            } else {
                record(toExit, emitTransition(transition));
            }
        }
        shared.put(key.toString(), entry);
        return entry;
    }

    /** The byte values a dispatch branch covers, whether it is a range or a table. */
    private int[] bytesOf(final int[] transition) {
        if (transition[0] >= 0) {
            final int[] values = new int[transition[1] - transition[0] + 1];
            for (int i = 0; i < values.length; i++) {
                values[i] = transition[0] + i;
            }
            return values;
        }
        final byte[] table = classes.get(transition[1]);
        int count = 0;
        for (final byte entry : table) {
            count += entry;
        }
        final int[] values = new int[count];
        int at = 0;
        for (int value = 0; value < 256; value++) {
            if (table[value] != 0) {
                values[at++] = value;
            }
        }
        return values;
    }

    /** A dispatch branch: a byte range, or a table where the values are not contiguous. */
    private int emitTransition(final int[] transition) {
        return transition[0] < 0
                ? emitByte(Nfa.BYTE_CLASS, transition[1], 0, transition[2])
                : emitByte(Nfa.BYTE_RANGE, transition[0], transition[1], transition[2]);
    }

    private static void record(final List<Integer> toExit, final int pc) {
        toExit.add(pc);
    }

    /**
     * A node of the byte-range trie: transitions in insertion order, each with the subtree that
     * follows it. Order is preference order, but the alternatives are byte-disjoint, so the
     * class matches the same characters whatever the order.
     */
    private static final class Trie {

        private final List<int[]> ranges = new ArrayList<>();
        private final List<Trie> children = new ArrayList<>();

        boolean isEmpty() {
            return ranges.isEmpty();
        }

        void insert(final int[] sequence, final int at) {
            if (at >= sequence.length) {
                return;
            }
            final int lo = sequence[at];
            final int hi = sequence[at + 1];
            for (int i = 0; i < ranges.size(); i++) {
                final int[] range = ranges.get(i);
                if (range[0] == lo && range[1] == hi) {
                    children.get(i).insert(sequence, at + 2);
                    return;
                }
            }
            ranges.add(new int[]{lo, hi});
            final Trie child = new Trie();
            children.add(child);
            child.insert(sequence, at + 2);
        }
    }

    private void emitAlternatives(final List<Hir> branches) {
        emitBranches(branches.size(), i -> emitNode(branches.get(i)));
    }

    /**
     * Emits {@code count} alternatives in preference order, each ending in a jump past the rest.
     */
    private void emitBranches(final int count, final java.util.function.IntConsumer emitBranch) {
        final List<Integer> exitJumps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i < count - 1) {
                // Prefer this branch; fall back to whatever follows the jump below.
                final int split = emit(Nfa.SPLIT, 0, 0);
                instructions.get(split)[1] = nextPc();
                emitBranch.accept(i);
                exitJumps.add(emit(Nfa.JUMP, 0, 0));
                instructions.get(split)[2] = nextPc();
            } else {
                emitBranch.accept(i);
            }
        }
        final int exit = nextPc();
        exitJumps.forEach(pc -> instructions.get(pc)[1] = exit);
    }

    private void emitRepeat(final Hir.Repeat repeat) {
        final Hir body = repeat.body();
        final int min = repeat.min();
        final int max = repeat.max();

        if (max != Hir.Repeat.UNBOUNDED && max > MAX_UNROLL) {
            throw new PatternCompileException(Reason.UNSUPPORTED, pattern, -1,
                    "repetition bound above " + MAX_UNROLL + " is not supported");
        }

        for (int i = 0; i < min; i++) {
            emitNode(body);
            checkSize();
        }

        if (max == Hir.Repeat.UNBOUNDED) {
            // L: SPLIT(body, exit); body; JUMP L; exit:   — swapped for a lazy repetition, which
            // prefers leaving the loop over entering it.
            //
            // A body that can match empty gets a guard around it as well:
            //
            //     L: SPLIT(body, exit); MARK m; body; PROGRESS m else exit; JUMP L; exit:
            //
            // so that an iteration which consumed nothing leaves the loop rather than repeating.
            // Both java.util.regex and Rust do this, and without it the loop keeps consuming:
            // (?:|a)* against "aaa" matched three bytes where both of them match nothing. The
            // guard has to route the empty iteration to the *exit* rather than merely killing
            // the path, because the priority of that path is what makes the empty match win.
            final boolean guard = Analysis.nullable(body);
            if (guard && marks >= Closures.MAX_MARKS) {
                throw new PatternCompileException(Reason.UNSUPPORTED, pattern, -1,
                        "more than " + Closures.MAX_MARKS
                        + " nested or repeated constructs that can match nothing");
            }
            final int loop = emit(Nfa.SPLIT, 0, 0);
            final int bodyStart = nextPc();
            final int mark = guard
                    ? marks++
                    : -1;
            if (guard) {
                emit(Nfa.MARK, mark, 0);
            }
            emitNode(body);
            final int progress = guard
                    ? emit(Nfa.PROGRESS, mark, 0)
                    : -1;
            emit(Nfa.JUMP, loop, 0);
            final int exit = nextPc();
            if (guard) {
                instructions.get(progress)[2] = exit;
            }
            instructions.get(loop)[1] = repeat.greedy()
                    ? bodyStart
                    : exit;
            instructions.get(loop)[2] = repeat.greedy()
                    ? exit
                    : bodyStart;
        } else {
            final List<Integer> splits = new ArrayList<>();
            for (int i = min; i < max; i++) {
                final int split = emit(Nfa.SPLIT, 0, 0);
                splits.add(split);
                instructions.get(split)[repeat.greedy()
                        ? 1
                        : 2] = nextPc();
                emitNode(body);
                checkSize();
            }
            final int exit = nextPc();
            for (final int split : splits) {
                instructions.get(split)[repeat.greedy()
                        ? 2
                        : 1] = exit;
            }
        }
    }

    private int emit(final int op, final int a, final int b) {
        final int pc = instructions.size();
        // -1 means "fall through", resolved to pc + 1 when the program is built. Only a class
        // trie sets anything else, so every other instruction is unchanged.
        instructions.add(new int[]{op, a, b, -1});
        checkSize();
        return pc;
    }

    /** A byte-consuming instruction that continues somewhere other than the next instruction. */
    private int emitByte(final int op, final int a, final int b, final int successor) {
        final int pc = emit(op, a, b);
        instructions.get(pc)[3] = successor;
        return pc;
    }

    private void checkSize() {
        if (instructions.size() > MAX_INSTRUCTIONS) {
            throw new PatternCompileException(Reason.UNSUPPORTED, pattern, -1,
                    "pattern compiles to more than " + MAX_INSTRUCTIONS + " instructions");
        }
    }

    private int nextPc() {
        return instructions.size();
    }
}
