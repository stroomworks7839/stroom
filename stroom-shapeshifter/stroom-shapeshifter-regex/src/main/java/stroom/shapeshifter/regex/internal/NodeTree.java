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
import stroom.shapeshifter.regex.PatternCompileException;
import stroom.shapeshifter.regex.PatternCompileException.Reason;

import java.util.Arrays;
import java.util.List;

/**
 * The experimental tree-walking engine: {@code java.util.regex}'s architecture over this
 * dialect and byte input.
 * <p>
 * The HIR is not lowered to a flat program. Each construct compiles to a node object with its
 * own {@code match()}, wired to its continuation, and matching is a chain of virtual calls —
 * so the JIT can inline a pattern's concrete chain and fold its constants, which is the
 * per-pattern specialisation 05-engine-benchmarks.md §10.2 describes and the flat-program
 * engines structurally cannot have. Recursion is the undo log: a node that fails returns
 * through the frames that wrote capture state, restoring as it goes, so there is no journal.
 * The exceptions are lookaround and atomic groups, which break the single recursion spine and
 * save the slot array around their sub-match instead.
 * <p>
 * Never selected by the compiler ({@code Engine.TREE}); reached only through
 * {@code compileForcing}, for differential testing and for measuring the architecture
 * question. Same dialect, same byte offsets, same streaming conservatism, same step budget
 * ({@link MatchLimitException}) as the unbounded backtracker.
 */
public final class NodeTree {

    private static final long STEP_BUDGET = 1_000_000;

    /** A compiled pattern: the entry node plus what the search loop needs around it. */
    public record Compiled(Node root,
                           int groupCount,
                           int slotCount,
                           int localCount,
                           byte[] firstBytes,
                           int startAnchor,
                           int nodeCount) {

    }

    /** Search state threaded through every {@code match()} call. */
    static final class Ctx {

        byte[] data;
        int regionFrom;
        int to;
        int[] slots;
        int[] groupStart;
        int[] locals;
        boolean hitEnd;
        boolean recordEdge;
        long steps;
        int searchStart;
        int requireEnd;
        int end;

        void budget() {
            if (--steps < 0) {
                throw new MatchLimitException(
                        "the search took more than " + STEP_BUDGET + " steps, which only a "
                        + "pathological combination of pattern and input does; the pattern "
                        + "backtracks catastrophically and needs restructuring");
            }
        }

        void edge() {
            if (recordEdge) {
                hitEnd = true;
            }
        }
    }

    public abstract static class Node {

        Node next;

        abstract boolean match(Ctx ctx, int pos);
    }

    private NodeTree() {
    }

    // -----------------------------------------------------------------------------------
    // The search loop — same contract as every other engine
    // -----------------------------------------------------------------------------------

    /** A matcher-owned machine, holding the reusable per-search state. */
    public static final class Machine {

        private final Compiled compiled;
        private final Ctx ctx = new Ctx();

        public Machine(final Compiled compiled) {
            this.compiled = compiled;
            ctx.groupStart = new int[compiled.groupCount() + 1];
            ctx.locals = new int[compiled.localCount()];
        }

        /**
         * @return the match end offset, or {@link PlanRunner#NO_MATCH}, or
         * {@link PlanRunner#NEED_MORE}.
         */
        public int search(final byte[] data,
                          final int regionFrom,
                          final int start,
                          final int to,
                          final boolean anchored,
                          final boolean complete,
                          final int[] slots) {
            ctx.data = data;
            ctx.regionFrom = regionFrom;
            ctx.to = to;
            ctx.slots = slots;
            ctx.hitEnd = false;
            ctx.recordEdge = true;
            ctx.steps = STEP_BUDGET;
            ctx.searchStart = start;
            ctx.requireEnd = -1;

            final byte[] firstBytes = compiled.firstBytes();
            final int anchor = compiled.startAnchor();

            for (int at = start; at <= to; at++) {
                if (at < to && at > regionFrom && anchor != 0
                    && (anchor == 2 || data[at - 1] != '\n')) {
                    if (anchored) {
                        break;
                    }
                    continue;
                }
                if (at < to && Utf8.isContinuation(data[at])) {
                    continue;
                }
                if (firstBytes != null && (at == to || firstBytes[data[at] & 0xFF] == 0)) {
                    if (at == to) {
                        ctx.hitEnd = true;
                    }
                    if (anchored) {
                        break;
                    }
                    continue;
                }
                Arrays.fill(slots, -1);
                if (compiled.root().match(ctx, at)) {
                    slots[0] = at;
                    slots[1] = ctx.end;
                    return !complete && (ctx.hitEnd || ctx.end == to)
                            ? PlanRunner.NEED_MORE
                            : ctx.end;
                }
                if (anchored) {
                    break;
                }
            }
            return !complete && ctx.hitEnd
                    ? PlanRunner.NEED_MORE
                    : PlanRunner.NO_MATCH;
        }
    }

    // -----------------------------------------------------------------------------------
    // Compilation: HIR in, node chain out
    // -----------------------------------------------------------------------------------

    public static Compiled compile(final Hir root, final int groupCount, final String pattern) {
        final Compiler compiler = new Compiler(pattern);
        final Node accept = compiler.node(new Accept());
        final Node head = compiler.compile(root, accept);

        final java.util.BitSet first = Analysis.first(root);
        byte[] firstBytes = null;
        if (!Analysis.nullable(root) && !first.isEmpty()) {
            firstBytes = new byte[256];
            for (int b = first.nextSetBit(0); b >= 0; b = first.nextSetBit(b + 1)) {
                firstBytes[b] = 1;
            }
        }
        return new Compiled(head, groupCount, 2 * (groupCount + 1), compiler.locals,
                firstBytes, Analysis.startAnchor(root), compiler.nodes);
    }

    private static final class Compiler {

        private final String pattern;
        private int locals;
        private int nodes;

        Compiler(final String pattern) {
            this.pattern = pattern;
        }

        private <T extends Node> T node(final T node) {
            nodes++;
            return node;
        }

        /** Compiles {@code hir} so that success flows into {@code next}. */
        Node compile(final Hir hir, final Node next) {
            return switch (hir) {
                case Hir.Empty ignored -> next;

                case Hir.Bytes bytes -> chain(new ByteSeq(bytes.value()), next);

                case Hir.CharClass charClass -> chain(new OneChar(charClass.set()), next);

                case Hir.Assertion assertion -> chain(new Assert(assertion.kind()), next);

                case Hir.Concat concat -> {
                    Node tail = next;
                    final List<Hir> items = concat.items();
                    for (int i = items.size() - 1; i >= 0; i--) {
                        tail = compile(items.get(i), tail);
                    }
                    yield tail;
                }

                case Hir.Alt alt -> {
                    final Node[] branches = new Node[alt.branches().size()];
                    for (int i = 0; i < branches.length; i++) {
                        branches[i] = compile(alt.branches().get(i), next);
                    }
                    yield node(new Branch(branches));
                }

                case Hir.Group group -> {
                    if (!group.capturing()) {
                        yield compile(group.body(), next);
                    }
                    final GroupTail tail = node(new GroupTail(group.index()));
                    tail.next = next;
                    final Node body = compile(group.body(), tail);
                    final GroupHead head = node(new GroupHead(group.index()));
                    head.next = body;
                    yield head;
                }

                case Hir.Repeat repeat -> compileRepeat(repeat, next);

                case Hir.Backref backref -> chain(new Backref(backref.index(),
                        backref.caseInsensitive(), backref.unicode()), next);

                case Hir.Look look -> {
                    final Node sub = compile(look.body(), node(new Accept()));
                    final int[] bounds = Analysis.byteLength(look.body());
                    if (look.behind() && bounds[1] == Analysis.UNBOUNDED_LENGTH) {
                        throw new PatternCompileException(Reason.UNSUPPORTED, pattern, -1,
                                "lookbehind has no maximum length, so there is no bound on how "
                                + "far back to try");
                    }
                    yield chain(new Look(sub, look.behind(), look.negated(),
                            bounds[0], bounds[1]), next);
                }

                case Hir.Atomic atomic ->
                        chain(new Atomic(compile(atomic.body(), node(new Accept()))), next);
            };
        }

        private Node compileRepeat(final Hir.Repeat repeat, final Node next) {
            Node tail = next;

            if (repeat.isUnbounded()) {
                if (repeat.body() instanceof Hir.CharClass charClass) {
                    // The fast shape: a class atom carries no state, so the whole run scans
                    // in a loop and backs off a character at a time — CLASS_STAR's trick,
                    // available here for every class because a tree walker reads characters.
                    tail = chain(new StarClass(charClass.set(), repeat.greedy()), next);
                } else {
                    // The general loop, JDK-style: the body's tail points back at the loop
                    // node, which counts nothing but guards against empty iterations by
                    // comparing positions through a local.
                    final Loop loop = node(new Loop(locals++, repeat.greedy()));
                    loop.next = next;
                    loop.body = compile(repeat.body(), loop);
                    tail = loop.entry();
                }
            } else if (repeat.max() > repeat.min()) {
                // Bounded: (max - min) nested optionals, preference by greediness.
                for (int i = repeat.max() - repeat.min(); i > 0; i--) {
                    final Ques ques = node(new Ques(repeat.greedy()));
                    ques.next = tail;
                    ques.body = compile(repeat.body(), tail);
                    tail = ques;
                }
            }

            for (int i = 0; i < repeat.min(); i++) {
                tail = compile(repeat.body(), tail);
            }
            return tail;
        }

        private Node chain(final Node head, final Node next) {
            head.next = next;
            return node(head);
        }
    }

    // -----------------------------------------------------------------------------------
    // Nodes
    // -----------------------------------------------------------------------------------

    /** The end of a (sub-)pattern. Honours a lookbehind's pinned end position. */
    private static final class Accept extends Node {

        @Override
        boolean match(final Ctx ctx, final int pos) {
            if (ctx.requireEnd >= 0 && pos != ctx.requireEnd) {
                return false;
            }
            ctx.end = pos;
            return true;
        }
    }

    /** A literal byte sequence. */
    private static final class ByteSeq extends Node {

        private final byte[] value;

        ByteSeq(final byte[] value) {
            this.value = value;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            final int available = ctx.to - pos;
            final int compare = Math.min(value.length, available);
            for (int i = 0; i < compare; i++) {
                if (ctx.data[pos + i] != value[i]) {
                    return false;
                }
            }
            if (value.length > available) {
                ctx.edge(); // every byte in hand agreed; more input could complete it
                return false;
            }
            return next.match(ctx, pos + value.length);
        }
    }

    /** One character of a class, decoded rather than compiled to byte machinery. */
    private static final class OneChar extends Node {

        private final byte[] ascii = new byte[0x80];
        private final CodePointSet set;

        OneChar(final CodePointSet set) {
            this.set = set;
            for (int b = 0; b < 0x80; b++) {
                ascii[b] = set.contains(b)
                        ? (byte) 1
                        : 0;
            }
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            final int advanced = accept(ctx, pos);
            return advanced > 0 && next.match(ctx, pos + advanced);
        }

        /** How many bytes the character at {@code pos} occupies if the class accepts it. */
        int accept(final Ctx ctx, final int pos) {
            if (pos >= ctx.to) {
                ctx.edge();
                return 0;
            }
            final int lead = ctx.data[pos] & 0xFF;
            if (lead < 0x80) {
                return ascii[lead] != 0
                        ? 1
                        : 0;
            }
            final int codePoint = Utf8.decode(ctx.data, pos, ctx.to);
            if (codePoint < 0) {
                if (pos + Utf8.sequenceLength(lead) > ctx.to) {
                    ctx.edge(); // truncated by the window, not malformed
                }
                return 0;
            }
            return set.contains(codePoint)
                    ? Utf8.encodedLength(codePoint)
                    : 0;
        }
    }

    /** Ordered alternation: first branch that matches wins. */
    private static final class Branch extends Node {

        private final Node[] branches;

        Branch(final Node[] branches) {
            this.branches = branches;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            ctx.budget();
            for (final Node branch : branches) {
                if (branch.match(ctx, pos)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Records where a group's current attempt began; recursion restores it on failure. */
    private static final class GroupHead extends Node {

        private final int group;

        GroupHead(final int group) {
            this.group = group;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            final int saved = ctx.groupStart[group];
            ctx.groupStart[group] = pos;
            if (next.match(ctx, pos)) {
                return true;
            }
            ctx.groupStart[group] = saved;
            return false;
        }
    }

    /** Writes the group's span; recursion restores it when the rest fails. */
    private static final class GroupTail extends Node {

        private final int group;

        GroupTail(final int group) {
            this.group = group;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            final int savedStart = ctx.slots[2 * group];
            final int savedEnd = ctx.slots[2 * group + 1];
            ctx.slots[2 * group] = ctx.groupStart[group];
            ctx.slots[2 * group + 1] = pos;
            if (next.match(ctx, pos)) {
                return true;
            }
            ctx.slots[2 * group] = savedStart;
            ctx.slots[2 * group + 1] = savedEnd;
            return false;
        }
    }

    /** A single optional: body-first when greedy, skip-first when lazy. */
    private static final class Ques extends Node {

        private final boolean greedy;
        private Node body;

        Ques(final boolean greedy) {
            this.greedy = greedy;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            ctx.budget();
            if (greedy) {
                return body.match(ctx, pos) || next.match(ctx, pos);
            }
            return next.match(ctx, pos) || body.match(ctx, pos);
        }
    }

    /**
     * An unbounded repetition of a stateful body. The body's tail points back here, so this
     * node is reached at the end of every iteration; a local carries the iteration's start
     * position, which is both the empty-iteration guard and what recursion restores.
     */
    private static final class Loop extends Node {

        private final int local;
        private final boolean greedy;
        private Node body;

        Loop(final int local, final boolean greedy) {
            this.local = local;
            this.greedy = greedy;
        }

        /** Entry from before the loop, as opposed to arrival from the body's tail. */
        Node entry() {
            final Loop loop = this;
            return new Node() {
                @Override
                boolean match(final Ctx ctx, final int pos) {
                    return loop.matchInit(ctx, pos);
                }
            };
        }

        private boolean matchInit(final Ctx ctx, final int pos) {
            ctx.budget();
            final int saved = ctx.locals[local];
            ctx.locals[local] = pos;
            final boolean matched = greedy
                    ? body.match(ctx, pos) || next.match(ctx, pos)
                    : next.match(ctx, pos) || body.match(ctx, pos);
            ctx.locals[local] = saved;
            return matched;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            // One iteration just completed. An empty one ends the loop rather than repeating
            // — the same routing as PROGRESS in the flat programs — and its priority is what
            // lets the empty match win.
            if (pos == ctx.locals[local]) {
                return next.match(ctx, pos);
            }
            ctx.budget();
            final int saved = ctx.locals[local];
            ctx.locals[local] = pos;
            final boolean matched = greedy
                    ? body.match(ctx, pos) || restoreAndNext(ctx, pos, saved)
                    : next.match(ctx, pos) || anotherIteration(ctx, pos, saved);
            ctx.locals[local] = saved;
            return matched;
        }

        private boolean restoreAndNext(final Ctx ctx, final int pos, final int saved) {
            ctx.locals[local] = saved;
            return next.match(ctx, pos);
        }

        private boolean anotherIteration(final Ctx ctx, final int pos, final int saved) {
            ctx.locals[local] = pos;
            final boolean matched = body.match(ctx, pos);
            ctx.locals[local] = saved;
            return matched;
        }
    }

    /**
     * An unbounded repetition of a class: the run scans forward in a loop and backs off a
     * character at a time, one stack frame for the whole repetition. Works for every class —
     * a tree walker reads characters, so byte-safety never comes into it.
     */
    private static final class StarClass extends Node {

        private final OneChar item;
        private final boolean greedy;

        StarClass(final CodePointSet set, final boolean greedy) {
            this.item = new OneChar(set);
            this.greedy = greedy;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            if (greedy) {
                int end = pos;
                for (;;) {
                    final int advanced = item.accept(ctx, end);
                    if (advanced == 0) {
                        break;
                    }
                    end += advanced;
                }
                ctx.steps -= end - pos;
                for (int at = end; at >= pos; at--) {
                    if (at < end && Utf8.isContinuation(ctx.data[at])) {
                        continue; // back off whole characters
                    }
                    ctx.budget();
                    if (next.match(ctx, at)) {
                        return true;
                    }
                }
                return false;
            }
            int at = pos;
            for (;;) {
                ctx.budget();
                if (next.match(ctx, at)) {
                    return true;
                }
                final int advanced = item.accept(ctx, at);
                if (advanced == 0) {
                    return false;
                }
                at += advanced;
            }
        }
    }

    /** A zero-width assertion, evaluated exactly as the flat-program engines evaluate it. */
    private static final class Assert extends Node {

        private final Hir.Kind kind;

        Assert(final Hir.Kind kind) {
            this.kind = kind;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            if (pos >= ctx.to) {
                ctx.edge();
            }
            final boolean holds = kind == Hir.Kind.PREVIOUS_MATCH_END
                    ? pos == ctx.searchStart
                    : PikeVm.assertionHolds(kind, ctx.data, ctx.regionFrom, ctx.to, pos);
            return holds && next.match(ctx, pos);
        }
    }

    /** A backreference, with the same comparison rules as the unbounded backtracker. */
    private static final class Backref extends Node {

        private final int group;
        private final boolean fold;
        private final boolean unicode;

        Backref(final int group, final boolean fold, final boolean unicode) {
            this.group = group;
            this.fold = fold;
            this.unicode = unicode;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            ctx.budget();
            final int from = ctx.slots[2 * group];
            final int until = ctx.slots[2 * group + 1];
            if (from < 0 || until < 0) {
                return false; // a reference to a group that did not participate fails
            }
            if (!fold) {
                final int length = until - from;
                final int available = ctx.to - pos;
                final int compare = Math.min(length, available);
                for (int i = 0; i < compare; i++) {
                    if (ctx.data[from + i] != ctx.data[pos + i]) {
                        return false;
                    }
                }
                if (length > available) {
                    ctx.edge();
                    return false;
                }
                return next.match(ctx, pos + length);
            }
            int captured = from;
            int input = pos;
            while (captured < until) {
                if (input >= ctx.to) {
                    ctx.edge();
                    return false;
                }
                final int wanted = Utf8.decode(ctx.data, captured, until);
                if (wanted < 0) {
                    return false;
                }
                final int have = Utf8.decode(ctx.data, input, ctx.to);
                if (have < 0) {
                    ctx.edge();
                    return false;
                }
                if (wanted != have && !foldedEqual(wanted, have)) {
                    return false;
                }
                captured += Utf8.encodedLength(wanted);
                input += Utf8.encodedLength(have);
            }
            return next.match(ctx, input);
        }

        private boolean foldedEqual(final int a, final int b) {
            if (!unicode) {
                return (a | 0x20) == (b | 0x20)
                       && (a | 0x20) >= 'a' && (a | 0x20) <= 'z'
                       && a <= 0x7F && b <= 0x7F;
            }
            return Character.toUpperCase(a) == Character.toUpperCase(b)
                   || Character.toLowerCase(a) == Character.toLowerCase(b);
        }
    }

    /**
     * Lookaround. The sub-match breaks the recursion spine, so the slot array is saved around
     * it — the one place this engine keeps an explicit undo.
     */
    private static final class Look extends Node {

        private final Node sub;
        private final boolean behind;
        private final boolean negated;
        private final int minLength;
        private final int maxLength;

        Look(final Node sub, final boolean behind, final boolean negated,
             final int minLength, final int maxLength) {
            this.sub = sub;
            this.behind = behind;
            this.negated = negated;
            this.minLength = minLength;
            this.maxLength = maxLength;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            ctx.budget();
            final int[] saved = ctx.slots.clone();
            final boolean matched = behind
                    ? matchBehind(ctx, pos)
                    : matchAhead(ctx, pos);
            if (matched == negated) {
                System.arraycopy(saved, 0, ctx.slots, 0, saved.length);
                return false;
            }
            if (negated) {
                System.arraycopy(saved, 0, ctx.slots, 0, saved.length);
            }
            if (next.match(ctx, pos)) {
                return true;
            }
            System.arraycopy(saved, 0, ctx.slots, 0, saved.length);
            return false;
        }

        /** A lookahead inside a lookbehind body must not inherit the pinned end position. */
        private boolean matchAhead(final Ctx ctx, final int pos) {
            final int savedRequire = ctx.requireEnd;
            ctx.requireEnd = -1;
            try {
                return sub.match(ctx, pos);
            } finally {
                ctx.requireEnd = savedRequire;
            }
        }

        private boolean matchBehind(final Ctx ctx, final int pos) {
            final int savedRequire = ctx.requireEnd;
            final boolean savedEdge = ctx.recordEdge;
            final int savedTo = ctx.to;
            ctx.requireEnd = pos;
            ctx.recordEdge = false;
            ctx.to = pos;
            try {
                final int lowest = Math.max(ctx.regionFrom, pos - maxLength);
                for (int at = pos - minLength; at >= lowest; at--) {
                    if (at > ctx.regionFrom && at < pos && Utf8.isContinuation(ctx.data[at])) {
                        continue;
                    }
                    if (sub.match(ctx, at)) {
                        return true;
                    }
                }
                return false;
            } finally {
                ctx.requireEnd = savedRequire;
                ctx.recordEdge = savedEdge;
                ctx.to = savedTo;
            }
        }
    }

    /** An atomic group: the sub-match's first answer is the only answer. */
    private static final class Atomic extends Node {

        private final Node sub;

        Atomic(final Node sub) {
            this.sub = sub;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            ctx.budget();
            final int[] saved = ctx.slots.clone();
            final int savedRequire = ctx.requireEnd;
            ctx.requireEnd = -1; // an atomic group ends where it ends, pinned context or not
            final boolean subMatched = sub.match(ctx, pos);
            ctx.requireEnd = savedRequire;
            if (!subMatched) {
                System.arraycopy(saved, 0, ctx.slots, 0, saved.length);
                return false;
            }
            final int end = ctx.end;
            if (next.match(ctx, end)) {
                return true;
            }
            System.arraycopy(saved, 0, ctx.slots, 0, saved.length);
            return false;
        }
    }
}
