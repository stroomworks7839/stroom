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

/**
 * A Thompson NFA program over bytes — the tier 1 representation, used for patterns that are
 * ambiguous and so cannot compile to a scan plan.
 * <p>
 * Executed by {@link PikeVm} as a simulation rather than a backtracking search, which is what
 * gives the linear-time guarantee and removes the {@code StackOverflowError} failure mode that
 * a recursive backtracker has. It is also why the design can support streaming later: the whole
 * execution state is the thread list, so it can be suspended at a chunk boundary.
 */
public final class Nfa {

    public static final int BYTE_RANGE = 0; // a = lo, b = hi; continues at next[pc] on success
    public static final int SPLIT = 1;      // a = preferred target, b = alternative target
    public static final int JUMP = 2;       // a = target
    public static final int SAVE = 3;       // a = slot; falls through
    public static final int ASSERT = 4;     // a = Hir.Kind ordinal; falls through
    public static final int MATCH = 5;
    /** a = class table index — consumes one byte present in a 256-entry table. */
    public static final int BYTE_CLASS = 6;  // a = table index; continues at next[pc] on success

    /**
     * Marks the start of a repetition iteration; {@code a} is the mark's id. Consumes nothing and
     * has no run-time effect at all — it exists so that {@link Closures} can see, while walking an
     * epsilon path, whether the iteration it is in began at this same input position.
     */
    public static final int MARK = 7;

    /**
     * The loop-back edge of a repetition whose body can match empty. Falls through to the next
     * instruction when the iteration made progress, and jumps to {@code b} — the loop exit — when
     * it did not, which is how an empty iteration ends the loop instead of repeating for ever.
     */
    public static final int PROGRESS = 8;

    /**
     * A whole dispatch in one instruction: {@code a} indexes a 256-entry table of successors, or
     * -1 where the byte matches nothing.
     * <p>
     * This is what a character class costs when its branches are disjoint, which is nearly always.
     * The alternative — one branch per byte range — puts every branch in the epsilon closure, so a
     * Unicode {@code \w} added 31 threads at every input position when at most one of them could
     * survive the next byte. Measurement put the simulation's cost on that width rather than on
     * program size, so collapsing it is worth more than any amount of shrinking.
     */
    public static final int BYTE_DISPATCH = 9;

    /**
     * Match again whatever group {@code a} captured; {@code b} carries the case-comparison bits
     * ({@link #BACKREF_FOLD} and {@link #BACKREF_UNICODE}). Run by the unbounded backtracker
     * only: whether it matches depends on capture state, which is precisely what a simulation's
     * (instruction, position) state cannot carry, and what breaks the bounded backtracker's
     * visited-set argument.
     */
    public static final int BACKREF = 10;

    /**
     * Lookaround: run sub-program {@code a} as a nested match at (or, looking behind, ending at)
     * the current position, consuming nothing. {@code b} carries {@link #LOOK_NEGATED} and
     * {@link #LOOK_BEHIND}.
     */
    public static final int LOOK = 11;

    /**
     * Atomic group: run sub-program {@code a} as a nested match at the current position, keep
     * what it consumed and captured, and never revisit its choices.
     */
    public static final int ATOMIC = 12;

    /** {@link #BACKREF} b-bits: compare case-folded, and fold across Unicode rather than ASCII. */
    public static final int BACKREF_FOLD = 1;
    public static final int BACKREF_UNICODE = 2;

    /** {@link #LOOK} b-bits. */
    public static final int LOOK_NEGATED = 1;
    public static final int LOOK_BEHIND = 2;

    /**
     * An unbounded repetition of a byte-safe class in one instruction: {@code a} indexes the
     * byte table, {@code b} is 1 for lazy. Greedy execution scans the whole run in a tight
     * loop and keeps one O(1) backoff frame, instead of a choice point per byte — the same
     * structural trick as the JDK's {@code Curly} node, and worth the same kind of factor.
     * <p>
     * Only emitted into fancy programs, which no other engine runs: the Pike VM's closures
     * cannot see through it. A class is byte-safe when it is ASCII-only or contains every
     * non-ASCII code point (as {@code .} and negated ASCII classes do), so the run can be
     * measured in bytes while backoff steps whole characters and spans never split one.
     */
    public static final int CLASS_STAR = 13;

    final int[] op;
    final int[] a;
    final int[] b;

    /**
     * Where a byte-consuming instruction continues, which is {@code pc + 1} everywhere except
     * inside a compiled character class. Classes are compiled as a trie over byte ranges with
     * shared tails, and sharing a tail means several instructions continue to the same place —
     * which fall-through cannot express. Every other instruction leaves this at {@code pc + 1}.
     */
    final int[] next;
    final byte[][] classes;

    /** Successor tables for {@link #BYTE_DISPATCH}, 256 entries each. */
    final int[][] dispatch;

    /** Sub-programs for {@link #LOOK} and {@link #ATOMIC}, sharing this program's slot space. */
    final Nfa[] subs;

    /**
     * For a lookbehind sub-program, the fewest and most bytes it can span — which bounds the
     * candidate start positions for a match ending at the cursor. Parallel to {@link #subs};
     * zero for anything else.
     */
    final int[] subMin;
    final int[] subMax;

    final int slotCount;
    final int groupCount;
    final boolean multiline;

    /** The fewest bytes any match spans; no attempt can succeed with fewer remaining. */
    final int minLength;

    /**
     * Whether this program contains an instruction only the unbounded backtracker can run.
     * Settled here, from the instructions, so the engine choice and the program can never
     * disagree about it.
     */
    private final boolean fancy;

    /** Built once with the program, since it is a pure function of it. */
    private final Closures closures;

    /** No start-position constraint: a match could begin anywhere. */
    static final int ANCHOR_NONE = 0;
    /** Every match begins at a line start — the region start, or just after a newline. */
    static final int ANCHOR_LINE = 1;
    /** Every match begins at the region start. */
    static final int ANCHOR_INPUT = 2;

    /**
     * The strongest start-position constraint every entry path agrees on. The same idea as
     * {@link #firstBytes}, for assertions instead of bytes: a pattern anchored with {@code ^}
     * can only match at a line start, so a search that attempts everywhere pays a full
     * attempt's setup per input byte to discover what one byte compare already knew. The JDK
     * skips to line starts for such patterns, and this is what lets these engines do the same.
     */
    private final int startAnchor;

    /**
     * Bytes a match can begin with, or null if it can match empty. Lets an unanchored search skip
     * seeding a thread at a position where nothing could possibly start — which is most positions
     * for a typical anchored pattern, and was otherwise the bulk of the work per input byte.
     */
    private final byte[] firstBytes;

    Nfa(final int[] op,
        final int[] a,
        final int[] b,
        final int[] next,
        final byte[][] classes,
        final int[][] dispatch,
        final int slotCount,
        final int groupCount,
        final boolean multiline) {
        this(op, a, b, next, classes, dispatch, new Nfa[0], new int[0], new int[0],
                slotCount, groupCount, multiline, 0);
    }

    Nfa(final int[] op,
        final int[] a,
        final int[] b,
        final int[] next,
        final byte[][] classes,
        final int[][] dispatch,
        final Nfa[] subs,
        final int[] subMin,
        final int[] subMax,
        final int slotCount,
        final int groupCount,
        final boolean multiline,
        final int minLength) {
        this.minLength = minLength;
        this.op = op;
        this.a = a;
        this.b = b;
        this.next = next;
        this.classes = classes;
        this.dispatch = dispatch;
        this.subs = subs;
        this.subMin = subMin;
        this.subMax = subMax;
        this.slotCount = slotCount;
        this.groupCount = groupCount;
        this.multiline = multiline;
        boolean needsFancy = subs.length > 0;
        for (int pc = 0; pc < op.length && !needsFancy; pc++) {
            needsFancy = op[pc] == BACKREF
                         || (op[pc] == ASSERT && a[pc] == Hir.Kind.PREVIOUS_MATCH_END.ordinal());
        }
        this.fancy = needsFancy;
        this.closures = new Closures(this);
        this.firstBytes = computeFirstBytes();
        this.startAnchor = computeStartAnchor();
    }

    private int computeStartAnchor() {
        int anchor = ANCHOR_INPUT;
        final int[] targets = closures.targets(0);
        for (int i = 0; i < targets.length && anchor != ANCHOR_NONE; i++) {
            int strength = ANCHOR_NONE;
            for (final int kind : closures.asserts(0, i)) {
                if (kind == Hir.Kind.START_INPUT.ordinal()) {
                    strength = ANCHOR_INPUT;
                    break;
                }
                if (kind == Hir.Kind.START_LINE.ordinal()) {
                    strength = ANCHOR_LINE;
                }
            }
            anchor = Math.min(anchor, strength);
        }
        return anchor;
    }

    int startAnchor() {
        return startAnchor;
    }

    /** Whether only the unbounded backtracker can run this program. */
    public boolean fancy() {
        return fancy;
    }

    private byte[] computeFirstBytes() {
        final byte[] table = new byte[256];
        for (final int target : closures.targets(0)) {
            switch (op[target]) {
                case BYTE_RANGE -> {
                    for (int value = a[target]; value <= b[target] && value < 256; value++) {
                        table[value] = 1;
                    }
                }
                case BYTE_CLASS -> {
                    final byte[] cls = classes[a[target]];
                    for (int value = 0; value < 256; value++) {
                        if (cls[value] != 0) {
                            table[value] = 1;
                        }
                    }
                }
                case BYTE_DISPATCH -> {
                    final int[] successors = dispatch[a[target]];
                    for (int value = 0; value < 256; value++) {
                        if (successors[value] >= 0) {
                            table[value] = 1;
                        }
                    }
                }
                default -> {
                    return null; // MATCH is reachable without consuming, so anything could start
                }
            }
        }
        return table;
    }

    byte[] firstBytes() {
        return firstBytes;
    }

    Closures closures() {
        return closures;
    }

    public int size() {
        return op.length;
    }

    public int groupCount() {
        return groupCount;
    }

    public int slotCount() {
        return slotCount;
    }

    public String explain() {
        final StringBuilder sb = new StringBuilder();
        for (int pc = 0; pc < op.length; pc++) {
            sb.append(String.format("%3d  ", pc));
            switch (op[pc]) {
                case BYTE_RANGE -> sb.append("BYTE_RANGE  ").append(render(a[pc]))
                        .append(a[pc] == b[pc]
                                ? ""
                                : "-" + render(b[pc]));
                case SPLIT -> sb.append("SPLIT       ").append(a[pc]).append(", ").append(b[pc]);
                case JUMP -> sb.append("JUMP        ").append(a[pc]);
                case SAVE -> sb.append("SAVE        slot ").append(a[pc]);
                case ASSERT -> sb.append("ASSERT      ").append(Hir.Kind.VALUES[a[pc]]);
                case BYTE_CLASS -> {
                    int count = 0;
                    for (final byte entry : classes[a[pc]]) {
                        count += entry;
                    }
                    sb.append("BYTE_CLASS  #").append(a[pc]).append(" (").append(count)
                            .append(" bytes)");
                }
                case MARK -> sb.append("MARK        #").append(a[pc]);
                case PROGRESS -> sb.append("PROGRESS    #").append(a[pc])
                        .append(" else ").append(b[pc]);
                case BYTE_DISPATCH -> {
                    int count = 0;
                    for (final int successor : dispatch[a[pc]]) {
                        if (successor >= 0) {
                            count++;
                        }
                    }
                    sb.append("DISPATCH    #").append(a[pc]).append(" (").append(count)
                            .append(" bytes)");
                }
                case BACKREF -> sb.append("BACKREF     group ").append(a[pc])
                        .append((b[pc] & BACKREF_FOLD) != 0
                                ? " (folded)"
                                : "");
                case LOOK -> sb.append("LOOK        sub ").append(a[pc])
                        .append((b[pc] & LOOK_BEHIND) != 0
                                ? ", behind"
                                : ", ahead")
                        .append((b[pc] & LOOK_NEGATED) != 0
                                ? ", negated"
                                : "");
                case ATOMIC -> sb.append("ATOMIC      sub ").append(a[pc]);
                case CLASS_STAR -> sb.append("CLASS_STAR  #").append(a[pc])
                        .append(b[pc] != 0
                                ? " (lazy)"
                                : "");
                case MATCH -> sb.append("MATCH");
                default -> sb.append("?? ").append(op[pc]);
            }
            if ((op[pc] == BYTE_RANGE || op[pc] == BYTE_CLASS) && next[pc] != pc + 1) {
                sb.append(" -> ").append(next[pc]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String render(final int byteValue) {
        return byteValue >= 0x20 && byteValue < 0x7F
                ? "'" + (char) byteValue + "'"
                : String.format("0x%02X", byteValue);
    }
}
