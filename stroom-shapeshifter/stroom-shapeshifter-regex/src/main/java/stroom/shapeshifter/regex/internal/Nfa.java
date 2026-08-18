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
    final int slotCount;
    final int groupCount;
    final boolean multiline;

    /** Built once with the program, since it is a pure function of it. */
    private final Closures closures;

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
        this.op = op;
        this.a = a;
        this.b = b;
        this.next = next;
        this.classes = classes;
        this.dispatch = dispatch;
        this.slotCount = slotCount;
        this.groupCount = groupCount;
        this.multiline = multiline;
        this.closures = new Closures(this);
        this.firstBytes = computeFirstBytes();
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
