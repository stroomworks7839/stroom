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

import stroom.shapeshifter.regex.Encoding;
import stroom.shapeshifter.regex.MatchLimitException;
import stroom.shapeshifter.regex.PatternCompileException;
import stroom.shapeshifter.regex.PatternCompileException.Reason;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * The tree-walking engine: {@code java.util.regex}'s architecture over this dialect and byte
 * input.
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
 * Built as an experiment ({@code Engine.TREE}, D30), promoted on the evidence: since D31 the
 * compiler's primary for every fancy pattern, since D32 the first try for every ambiguous
 * search — with the flat engines as fallback where its one structural limit, recursion depth,
 * gives out ({@code Bailout}), and the simulation preserving the linear-time promise beneath
 * it. Same dialect, same byte offsets, same step budget
 * ({@link MatchLimitException}) as the flat unbounded backtracker.
 */
public final class NodeTree {

    private static final long STEP_BUDGET = 1_000_000;

    /**
     * Loop iterations a single attempt may stack before the engine gives up structurally.
     * Every iteration of a stateful loop adds the body's frames to the call stack — the price
     * of recursion-as-undo-log — so a long record under {@code (?:ab)+} would eventually meet
     * {@link StackOverflowError}, the JDK engine's own famous failure mode. This engine
     * declines the fight instead: past the limit it raises {@link Bailout}, and the caller
     * falls back to a flat engine that answers the same question in linear space.
     */
    private static final int LOOP_DEPTH_LIMIT = 1024;

    /**
     * The tree engine giving up structurally — too deep for the call stack, as opposed to
     * {@link MatchLimitException}'s "pathological however you execute it". Callers treat it
     * as "use another engine", never as an answer.
     */
    public static final class Bailout extends RuntimeException {

        Bailout() {
            super(null, null, false, false); // no message, no stack trace: it is a signal
        }
    }

    /** A compiled pattern: the entry node plus what the search loop needs around it. */
    public record Compiled(Node root,
                           int groupCount,
                           int slotCount,
                           int localCount,
                           byte[] firstBytes,
                           int startAnchor,
                           int minLength,
                           int nodeCount,
                           ByteForm form) {

    }

    /** Search state threaded through every {@code match()} call. */
    static final class Ctx {

        byte[] data;
        int regionFrom;
        int to;
        int[] slots;
        int[] groupStart;
        int[] locals;
        int loopDepth;
        long steps;
        int searchStart;
        int requireEnd;
        int end;
        ByteForm form;

        /**
         * A depth-indexed pool of slot snapshots for lookaround and atomic groups, which break
         * the recursion spine and so cannot rely on unwinding to restore captures. Pooled and
         * depth-indexed because the same node can be live twice — a lookaround inside a loop —
         * and because a clone per entry was an allocation on the hottest path.
         */
        private int[][] snapshots = new int[4][];
        private int depth;

        int[] saveSlots() {
            if (depth == snapshots.length) {
                snapshots = Arrays.copyOf(snapshots, depth * 2);
            }
            if (snapshots[depth] == null || snapshots[depth].length < slots.length) {
                snapshots[depth] = new int[slots.length];
            }
            final int[] saved = snapshots[depth++];
            System.arraycopy(slots, 0, saved, 0, slots.length);
            return saved;
        }

        void restoreSlots(final int[] saved) {
            System.arraycopy(saved, 0, slots, 0, slots.length);
        }

        void releaseSlots() {
            depth--;
        }

        void budget() {
            if (--steps < 0) {
                throw new MatchLimitException(
                        "the search took more than " + STEP_BUDGET + " steps, which only a "
                        + "pathological combination of pattern and input does; the pattern "
                        + "backtracks catastrophically and needs restructuring");
            }
        }
    }

    public abstract static class Node {

        Node next;

        abstract boolean match(Ctx ctx, int pos);

        /**
         * The single byte this node must consume first, or -1 where that is not one known
         * byte. Answered only to let a lazy run skip positions its continuation cannot
         * start at: a filter on <i>where</i> to try, never a statement about what a try
         * does. Anything unsure answers -1 and the filter switches off.
         */
        int leadingByte() {
            return -1;
        }
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
            ctx.form = compiled.form();
            ctx.groupStart = new int[compiled.groupCount() + 1];
            ctx.locals = new int[compiled.localCount()];
        }

        /**
         * @return the match end offset, or {@link PlanRunner#NO_MATCH}.
         */
        public int search(final byte[] data,
                          final int regionFrom,
                          final int start,
                          final int to,
                          final boolean anchored,
                          final int[] slots) {
            ctx.data = data;
            ctx.regionFrom = regionFrom;
            ctx.to = to;
            ctx.slots = slots;
            ctx.steps = STEP_BUDGET;
            ctx.searchStart = start;
            ctx.requireEnd = -1;

            final byte[] firstBytes = compiled.firstBytes();
            final int anchor = compiled.startAnchor();
            int lastStart = to - compiled.minLength();
            // An input-anchored pattern cannot start past the region start, so the walk ends
            // there. Decided once, out here, so the line-anchored walk pays nothing for it.
            if (anchor == Nfa.ANCHOR_INPUT) {
                lastStart = Math.min(lastStart, regionFrom);
            }

            for (int at = start; at <= lastStart; at++) {
                if (cannotStartAt(data, regionFrom, to, at, anchor, firstBytes)) {
                    // Anchored searches stop at the first position no match can begin at.
                    if (anchored) {
                        break;
                    }
                    continue;
                }
                Arrays.fill(slots, -1);
                ctx.loopDepth = 0;
                final boolean matchedHere;
                try {
                    matchedHere = compiled.root().match(ctx, at);
                } catch (final StackOverflowError e) {
                    // The stack is already unwound by the time this is catchable, and the
                    // engine holds no state a failed attempt needs. The loop-depth limit
                    // should fire long before this; the catch is the backstop for shapes the
                    // counter does not model, in the D7 containment tradition.
                    throw new Bailout();
                }
                if (matchedHere) {
                    slots[0] = at;
                    slots[1] = ctx.end;
                    return ctx.end;
                }
                if (anchored) {
                    break;
                }
            }
            return PlanRunner.NO_MATCH;
        }

        /**
         * Whether no match can begin at {@code at}: not an anchor position, inside a character,
         * or on a byte the pattern cannot start with. The three tests lived in {@link #search}'s
         * loop until 2026-09-03, when the encoding plan's form gate took that method from 321 to
         * 330 bytecodes — over C2's {@code FreqInlineSize} of 325 — and it stopped inlining into
         * {@code ByteMatcher.runLinear}, costing every tree search 6–11% (design 06 §1). Splitting
         * the predicate out keeps both halves under the threshold; the JIT inlines this one back.
         * <p>A first-byte table exists only for a non-nullable pattern, so minLength >= 1 caps the
         * caller's loop at to - 1: at == to is unreachable and the read is in bounds (the D37
         * audit's proof; the dropped test was the deleted edge iteration's).
         */
        private boolean cannotStartAt(final byte[] data,
                                      final int regionFrom,
                                      final int to,
                                      final int at,
                                      final int anchor,
                                      final byte[] firstBytes) {
            if (at < to && at > regionFrom && anchor != Nfa.ANCHOR_NONE
                && (anchor == Nfa.ANCHOR_INPUT || data[at - 1] != '\n')) {
                return true;
            }
            if (ctx.form.splitsCharacter(data, at)) {
                return true;
            }
            return firstBytes != null && firstBytes[data[at] & 0xFF] == 0;
        }
    }

    // -----------------------------------------------------------------------------------
    // Compilation: HIR in, node chain out
    // -----------------------------------------------------------------------------------

    /**
     * <p>The {@code encoding} parameter, placed as phase 1's seam, is read since phase 3: it
     * selects the {@link ByteForm} the lowering compiles against (design/19).
     */
    public static Compiled compile(final Hir root, final int groupCount, final String pattern,
                                   final Encoding encoding) {
        final ByteForm form = ByteForm.of(encoding);
        final Compiler compiler = new Compiler(pattern, form);
        final Node accept = compiler.node(new Accept());
        final Node head = compiler.compile(root, accept);

        final BitSet first = Analysis.first(root);
        byte[] firstBytes = null;
        if (!Analysis.nullable(root) && !first.isEmpty()) {
            firstBytes = new byte[256];
            for (int b = first.nextSetBit(0); b >= 0; b = first.nextSetBit(b + 1)) {
                firstBytes[b] = 1;
            }
        }
        return new Compiled(head, groupCount, 2 * (groupCount + 1), compiler.locals,
                firstBytes, Analysis.startAnchor(root), Analysis.byteLength(root)[0],
                compiler.nodes, form);
    }

    private static final class Compiler {

        private final String pattern;
        private final ByteForm form;
        private int locals;
        private int nodes;

        Compiler(final String pattern, final ByteForm form) {
            this.pattern = pattern;
            this.form = form;
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

                case Hir.CharClass charClass -> chain(new OneChar(charClass.set(), charClass.label(), form), next);

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
                            capturesWithin(look.body()), bounds[0], bounds[1]), next);
                }

                case Hir.Atomic atomic -> chain(new Atomic(
                        compile(atomic.body(), node(new Accept())),
                        capturesWithin(atomic.body())), next);
            };
        }

        private Node compileRepeat(final Hir.Repeat repeat, final Node next) {
            Node tail = next;

            if (repeat.isUnbounded()) {
                if (repeat.body() instanceof Hir.CharClass charClass) {
                    // The fast shape: a class atom carries no state, so the whole run scans
                    // in a loop and backs off a character at a time — CLASS_STAR's trick,
                    // available here for every class because a tree walker reads characters.
                    final StarClass star = new StarClass(charClass.set(), charClass.label(), form, repeat.greedy());
                    tail = chain(star, next);
                    // After chain, because that is what links next — and the continuation
                    // itself is already complete, being compiled before its predecessor.
                    star.resolveSkip();
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
                if (repeat.body() instanceof Hir.CharClass charClass) {
                    // A bounded class repeat is StarClass with a ceiling: scan up to the
                    // excess in one loop, back off a character at a time. The nested-optional
                    // spelling below cost a frame per level and measured 9× slower on
                    // \S{1,10} than the flat engines; this is the shape's real fix.
                    tail = chain(new CountedClass(charClass.set(), charClass.label(), form,
                            repeat.max() - repeat.min(), repeat.greedy()), next);
                } else {
                    // Bounded, stateful body: (max - min) nested optionals, by greediness.
                    for (int i = repeat.max() - repeat.min(); i > 0; i--) {
                        final Ques ques = node(new Ques(repeat.greedy()));
                        ques.next = tail;
                        ques.body = compile(repeat.body(), tail);
                        tail = ques;
                    }
                }
            }

            for (int i = 0; i < repeat.min(); i++) {
                tail = compile(repeat.body(), tail);
            }
            return tail;
        }

        /** Whether the body contains a capturing group anywhere. */
        private static boolean capturesWithin(final Hir node) {
            return switch (node) {
                case Hir.Group group -> group.capturing() || capturesWithin(group.body());
                case Hir.Concat concat -> concat.items().stream()
                        .anyMatch(Compiler::capturesWithin);
                case Hir.Alt alt -> alt.branches().stream().anyMatch(Compiler::capturesWithin);
                case Hir.Repeat repeat -> capturesWithin(repeat.body());
                case Hir.Atomic atomic -> capturesWithin(atomic.body());
                case Hir.Look look -> capturesWithin(look.body());
                default -> false;
            };
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
        int leadingByte() {
            return value.length == 0 ? -1 : value[0] & 0xFF;
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
                return false;
            }
            return next.match(ctx, pos + value.length);
        }
    }

    /** One character of a class, decoded rather than compiled to byte machinery. */
    private static final class OneChar extends Node {

        private final byte[] ascii = new byte[0x80];
        private final CodePointSet set;

        /**
         * The compiled class form the scan plan already runs ({@link CharClass}): byte-range
         * alternatives from {@link Utf8#sequences}, indexed by lead byte so wide classes cost
         * what their matching alternative costs, not what their alternative count costs —
         * design 19 phase 2, and the audit's correction of it. A first cut reimplemented the
         * walk here with a linear scan; the shared form existed, already carried the lead
         * index, and its own javadoc named the hazard. One compilation, two engines.
         */
        private final CharClass form;

        /**
         * The one byte every member's encoding begins with, from the compiled form — so a
         * single-character terminator like {@code (.*?)b} skips under any encoding, and a
         * class whose members share one lead byte skips too. The filter walks, so a lead
         * byte is as safe to stop on as an ASCII one.
         */
        @Override
        int leadingByte() {
            return form.loneLeadByte();
        }

        OneChar(final CodePointSet set, final String label, final ByteForm byteForm) {
            this.set = set;
            for (int b = 0; b < 0x80; b++) {
                ascii[b] = set.contains(b)
                        ? (byte) 1
                        : 0;
            }
            this.form = new CharClass(set, label, byteForm);
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            final int advanced = accept(ctx, pos);
            return advanced > 0 && next.match(ctx, pos + advanced);
        }

        /** How many bytes the character at {@code pos} occupies if the class accepts it. */
        int accept(final Ctx ctx, final int pos) {
            if (pos >= ctx.to) {
                return 0;
            }
            final int lead = ctx.data[pos] & 0xFF;
            if (lead < 0x80) {
                // Spelled as a branch on the table byte rather than returned as the byte itself.
                // The two are the same value, and the phase-2 audit simplified this to
                // {@code return ascii[lead]} - which cost the tree 8-12% on every ASCII row
                // (LazyRunBenchmark BATCH_DOTALL -7.7%, CorpusBenchmark shapeshifterTree NETWORK
                // -11.8%, design 06 §1, 2026-09-03). C2 knows this form yields 0 or 1 and folds
                // every caller's {@code end += advanced} and {@code advanced == 0} on that; a raw
                // byte load carries no such range, and the loops around it compile worse.
                return ascii[lead] != 0
                        ? 1
                        : 0;
            }
            final int matched = form.matchAt(ctx.data, pos, ctx.to);
            return matched < 0
                    ? 0
                    : matched;
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

        @Override
        int leadingByte() {
            return next == null ? -1 : next.leadingByte();
        }

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

        @Override
        int leadingByte() {
            return next == null ? -1 : next.leadingByte();
        }

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
            if (++ctx.loopDepth > LOOP_DEPTH_LIMIT) {
                throw new Bailout();
            }
            final int saved = ctx.locals[local];
            ctx.locals[local] = pos;
            final boolean matched = greedy
                    ? body.match(ctx, pos) || next.match(ctx, pos)
                    : next.match(ctx, pos) || body.match(ctx, pos);
            ctx.locals[local] = saved;
            ctx.loopDepth--;
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
            if (++ctx.loopDepth > LOOP_DEPTH_LIMIT) {
                throw new Bailout();
            }
            final int saved = ctx.locals[local];
            ctx.locals[local] = pos;
            final boolean matched = greedy
                    ? body.match(ctx, pos) || restoreAndNext(ctx, pos, saved)
                    : next.match(ctx, pos) || anotherIteration(ctx, pos, saved);
            ctx.locals[local] = saved;
            ctx.loopDepth--;
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

        /** The item's own ASCII table, borrowed rather than built twice. */
        private final byte[] ascii;

        /**
         * The one byte a lazy run's continuation must consume first, or -1 for "do not
         * filter". Resolved once, after linking, by {@link #resolveSkip()}.
         */
        private int skipByte = -1;

        StarClass(final CodePointSet set, final String label, final ByteForm form,
                  final boolean greedy) {
            this.item = new OneChar(set, label, form);
            this.greedy = greedy;
            this.ascii = item.ascii;
        }

        /**
         * Resolve the skip filter. Must be called after {@code next} is linked, which is why
         * it is a separate step rather than constructor work — and it can be, because a
         * continuation is compiled before the node that precedes it.
         */
        void resolveSkip() {
            this.skipByte = greedy || next == null ? -1 : next.leadingByte();
        }

        /**
         * The next position a lazy run need bother trying: the first at or after {@code from}
         * that either carries the continuation's leading byte, or ends the run because the
         * class rejects it, or is the region end.
         *
         * <p>Two things together make this sound, and the second is the one worth stating
         * because it is what a first draft got wrong by guarding against. First, every
         * position skipped is one where {@code next.match} would have returned false without
         * consuming anything, since the continuation must consume {@link #skipByte} first and
         * that byte is not there. Second, this <b>walks</b> — same stepping rule as the loop
         * it filters, {@code accept} for anything non-ASCII — rather than searching for the
         * byte directly. So the positions it can stop at are by construction a subset of the
         * ones the unfiltered loop visits, whatever the bytes are and whether or not they are
         * valid UTF-8. It declines to offer positions; it cannot invent one.
         *
         * <p>An earlier version restricted the filter to ASCII lead bytes, reasoning that a
         * non-ASCII one might coincide with a continuation byte and stop inside a character.
         * A memchr would indeed do that. A walk cannot, and the restriction was removed once
         * a mutation test showed nothing could tell the two apart — which is the right
         * evidence for deleting a guard rather than keeping it because it feels safer.
         */
        private int skipTo(final Ctx ctx, final int from) {
            final byte[] data = ctx.data;
            final int to = ctx.to;
            int at = from;
            while (at < to) {
                final int b = data[at] & 0xFF;
                if (b == skipByte) {
                    return at;
                }
                if (b < 0x80) {
                    if (ascii[b] == 0) {
                        return at; // the class stops here; the caller ends the run
                    }
                    at++;
                } else {
                    final int advanced = item.accept(ctx, at);
                    if (advanced == 0) {
                        return at;
                    }
                    at += advanced;
                }
            }
            return at;
        }

        /**
         * The run's end, scanned byte-at-a-time where the class allows it. The per-character
         * {@code accept} call measured ~4 ns/char against ~1 for a table loop, and it was the
         * whole of TIER1_ALTERNATION's deficit — the alternation itself cost nothing.
         */
        private int scan(final Ctx ctx, final int pos) {
            final byte[] data = ctx.data;
            final int to = ctx.to;
            int end = pos;
            while (end < to) {
                final int b = data[end] & 0xFF;
                if (b < 0x80) {
                    if (ascii[b] == 0) {
                        return end;
                    }
                    end++;
                } else {
                    // One branch for everything non-ASCII since phase 2 byte-compiled the
                    // classes: accept() walks byte ranges, so the allNonAscii decode shortcut
                    // that used to live here — the one whose byte-stepping predecessor
                    // GreedyRunRawBytesTest convicted — no longer had anything to skip.
                    final int advanced = item.accept(ctx, end);
                    if (advanced == 0) {
                        return end;
                    }
                    end += advanced;
                }
            }
            return end;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            if (greedy) {
                final int end = scan(ctx, pos);
                ctx.steps -= end - pos;
                for (int at = end; at >= pos; at--) {
                    if (at < end && ctx.form.continuation(ctx.data[at])) {
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
                if (skipByte >= 0) {
                    // Skip what cannot match. The bytes passed over are still charged to the
                    // budget, so a pathological pattern is refused on the same evidence as
                    // before — the run does less work, not less accounting.
                    final int candidate = skipTo(ctx, at);
                    ctx.steps -= candidate - at;
                    at = candidate;
                }
            }
        }
    }

    /**
     * A bounded repetition of a class — {@code \S{1,10}} past its required minimum — scanned
     * like {@link StarClass} but stopping at the ceiling. One frame however wide the bound.
     */
    private static final class CountedClass extends Node {

        private final OneChar item;
        private final int most;
        private final boolean greedy;

        CountedClass(final CodePointSet set, final String label, final ByteForm form,
                     final int most, final boolean greedy) {
            this.item = new OneChar(set, label, form);
            this.most = most;
            this.greedy = greedy;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            if (greedy) {
                int end = pos;
                int taken = 0;
                while (taken < most) {
                    final int advanced = item.accept(ctx, end);
                    if (advanced == 0) {
                        break;
                    }
                    end += advanced;
                    taken++;
                }
                ctx.steps -= taken;
                for (int at = end; at >= pos; at--) {
                    if (at < end && ctx.form.continuation(ctx.data[at])) {
                        continue;
                    }
                    ctx.budget();
                    if (next.match(ctx, at)) {
                        return true;
                    }
                }
                return false;
            }
            int at = pos;
            int taken = 0;
            for (;;) {
                ctx.budget();
                if (next.match(ctx, at)) {
                    return true;
                }
                if (taken >= most) {
                    return false;
                }
                final int advanced = item.accept(ctx, at);
                if (advanced == 0) {
                    return false;
                }
                at += advanced;
                taken++;
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
            final boolean holds = kind == Hir.Kind.PREVIOUS_MATCH_END
                    ? pos == ctx.searchStart
                    : Words.assertionHolds(kind, ctx.data, ctx.regionFrom, ctx.to, pos,
                            ctx.form);
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
            final int consumed = Backrefs.compare(ctx.form, ctx.data, pos, ctx.to, from, until,
                    fold, unicode);
            if (consumed == Backrefs.TRUNCATED) {
                return false;
            }
            return consumed >= 0 && next.match(ctx, pos + consumed);
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
        private final boolean captures;
        private final int minLength;
        private final int maxLength;

        Look(final Node sub, final boolean behind, final boolean negated,
             final boolean captures, final int minLength, final int maxLength) {
            this.sub = sub;
            this.behind = behind;
            this.negated = negated;
            this.captures = captures;
            this.minLength = minLength;
            this.maxLength = maxLength;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            ctx.budget();
            if (!captures) {
                // The body cannot write a slot, so there is nothing to save or restore.
                final boolean matched = behind
                        ? matchBehind(ctx, pos)
                        : matchAhead(ctx, pos);
                return matched != negated && next.match(ctx, pos);
            }
            final int[] saved = ctx.saveSlots();
            try {
                final boolean matched = behind
                        ? matchBehind(ctx, pos)
                        : matchAhead(ctx, pos);
                if (matched == negated) {
                    ctx.restoreSlots(saved);
                    return false;
                }
                if (negated) {
                    ctx.restoreSlots(saved);
                }
                if (next.match(ctx, pos)) {
                    return true;
                }
                ctx.restoreSlots(saved);
                return false;
            } finally {
                ctx.releaseSlots();
            }
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

        /**
         * A lookbehind pins where its body must <em>end</em>, and nothing else. The view it
         * sees stays the whole region: an assertion or a nested lookahead in the body reads the
         * input past the cursor, exactly as it would outside — {@code (?<=a(?=bc))bc} matches
         * and {@code (?<=a$)b} does not, both of which need bytes the cursor would have hidden.
         * <p>
         * Pinning the view instead of the end used to do both jobs at once, and got the second
         * one wrong in both directions: a nested lookahead could never see its own text, and
         * {@code $} read the cursor as the end of the input and matched there.
         * <p>
         * The body can overrun the cursor while exploring, though never match past it. Starts
         * are tried from {@code cursor - min} back to {@code cursor - max}, and from any start
         * nearer than the farthest a consuming path can overshoot the cursor by up to
         * {@code max - min} before the end-pin fails it.
         */
        private boolean matchBehind(final Ctx ctx, final int pos) {
            final int savedRequire = ctx.requireEnd;
            ctx.requireEnd = pos;
            try {
                final int lowest = Math.max(ctx.regionFrom, pos - maxLength);
                for (int at = pos - minLength; at >= lowest; at--) {
                    if (at < pos && ctx.form.continuation(ctx.data[at])) {
                        // No regionFrom exemption: the search gate has none, and a region that
                        // opens mid-character is no better a place to start a lookbehind body.
                        // Deliberately not Utf8.splitsCharacter: at < pos keeps the probe inside
                        // consumed input, so the beyond-region clause can never apply here.
                        continue;
                    }
                    if (sub.match(ctx, at)) {
                        return true;
                    }
                }
                return false;
            } finally {
                ctx.requireEnd = savedRequire;
            }
        }
    }

    /** An atomic group: the sub-match's first answer is the only answer. */
    private static final class Atomic extends Node {

        private final Node sub;
        private final boolean captures;

        Atomic(final Node sub, final boolean captures) {
            this.sub = sub;
            this.captures = captures;
        }

        @Override
        boolean match(final Ctx ctx, final int pos) {
            ctx.budget();
            if (!captures) {
                final int savedRequire = ctx.requireEnd;
                ctx.requireEnd = -1;
                final boolean subMatched = sub.match(ctx, pos);
                ctx.requireEnd = savedRequire;
                return subMatched && next.match(ctx, ctx.end);
            }
            final int[] saved = ctx.saveSlots();
            try {
                final int savedRequire = ctx.requireEnd;
                ctx.requireEnd = -1; // an atomic group ends where it ends, pinned or not
                final boolean subMatched = sub.match(ctx, pos);
                ctx.requireEnd = savedRequire;
                if (!subMatched) {
                    ctx.restoreSlots(saved);
                    return false;
                }
                final int end = ctx.end;
                if (next.match(ctx, end)) {
                    return true;
                }
                ctx.restoreSlots(saved);
                return false;
            } finally {
                ctx.releaseSlots();
            }
        }
    }
}
