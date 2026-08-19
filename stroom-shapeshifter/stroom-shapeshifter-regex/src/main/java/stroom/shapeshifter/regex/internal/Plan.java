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
 * A compiled tier 0 scan plan: straight-line byte operations with just enough control flow for
 * alternation and repetition, and no automaton.
 * <p>
 * Held as flat parallel arrays rather than an array of op objects. Benchmarking showed that
 * shape 5–10% faster than a sealed-record model dispatched by pattern-matching switch, and
 * class membership as a 256-entry byte table 22% faster than a 256-bit set — see
 * {@code design/03-baseline-results.md}.
 */
public final class Plan {

    // Opcodes. Byte-consuming ops fail the match if the input does not match.
    public static final int MATCH_BYTE = 0;       // a = byte value
    public static final int MATCH_LITERAL = 1;    // a = literal index
    public static final int MATCH_CLASS = 2;      // a = class index
    public static final int SCAN_WHILE_BYTE = 3;  // a = byte value, b = min, c = max
    public static final int SCAN_UNTIL_BYTE = 4;  // a = byte value to stop before, b = min, c = max
    public static final int SCAN_WHILE_CLASS = 5; // a = class index, b = min, c = max
    public static final int SAVE = 6;             // a = slot
    public static final int BRANCH = 7;           // a = branch table index — peeks, consumes nothing
    public static final int JUMP = 8;             // a = target pc
    public static final int ASSERT = 9;           // a = Hir.Kind ordinal
    public static final int ACCEPT = 10;
    public static final int MATCH_CHAR = 11;      // a = char class index — consumes a whole character
    public static final int SCAN_WHILE_CHAR = 12; // a = char class index, b = min, c = max (characters)

    /** Target index within a branch table holding the destination when no byte matches. */
    public static final int BRANCH_DEFAULT = 256;

    /** A branch target meaning "no continuation is possible" — the match fails. */
    public static final int NO_TARGET = -1;

    final int[] op;
    final int[] a;
    final int[] b;
    final int[] c;
    final byte[][] literals;
    final byte[][] classes;
    final CharClass[] charClasses;
    final int[][] branchTables;
    final int slotCount;
    final int groupCount;
    final boolean multiline;

    /**
     * Bytes any match can begin with, as a 256-entry table, or null when the pattern can match
     * empty. Used to skip hopeless start offsets during an unanchored search.
     */
    final byte[] firstBytes;

    /** The fewest bytes any match spans; no attempt can succeed with fewer remaining. */
    final int minLength;

    Plan(final int[] op,
         final int[] a,
         final int[] b,
         final int[] c,
         final byte[][] literals,
         final byte[][] classes,
         final CharClass[] charClasses,
         final int[][] branchTables,
         final int slotCount,
         final int groupCount,
         final boolean multiline,
         final byte[] firstBytes,
         final int minLength) {
        this.op = op;
        this.a = a;
        this.b = b;
        this.c = c;
        this.literals = literals;
        this.classes = classes;
        this.charClasses = charClasses;
        this.branchTables = branchTables;
        this.slotCount = slotCount;
        this.groupCount = groupCount;
        this.multiline = multiline;
        this.firstBytes = firstBytes;
        this.minLength = minLength;
    }

    public int groupCount() {
        return groupCount;
    }

    /** The fewest bytes any match spans; no attempt can succeed with fewer remaining. */
    public int minLength() {
        return minLength;
    }

    /**
     * Bytes any match can begin with, as a 256-entry table, or null if the pattern can match
     * empty and so could begin anywhere.
     */
    public byte[] firstBytes() {
        return firstBytes;
    }

    public int slotCount() {
        return slotCount;
    }

    public int size() {
        return op.length;
    }

    /**
     * The assertion this plan opens with, or null. A pattern anchored at the start of the input
     * or of a line can only match at those positions, so an unanchored search need not attempt
     * every offset — which for a typical {@code ^}-anchored pattern is nearly all of them.
     */
    public Hir.Kind leadingAnchor() {
        if (op.length == 0 || op[0] != ASSERT) {
            return null;
        }
        final Hir.Kind kind = Hir.Kind.VALUES[a[0]];
        return kind == Hir.Kind.START_INPUT || kind == Hir.Kind.START_LINE
                ? kind
                : null;
    }

    public boolean multiline() {
        return multiline;
    }

    /** A human-readable listing, for {@code BytePattern.explain()} and for asserting in tests. */
    public String explain() {
        final StringBuilder sb = new StringBuilder();
        for (int pc = 0; pc < op.length; pc++) {
            sb.append(String.format("%3d  ", pc));
            switch (op[pc]) {
                case MATCH_BYTE -> sb.append("MATCH_BYTE      ").append(render(a[pc]));
                case MATCH_LITERAL -> sb.append("MATCH_LITERAL   ")
                        .append(renderLiteral(literals[a[pc]]));
                case MATCH_CLASS -> sb.append("MATCH_CLASS     #").append(a[pc])
                        .append(" (").append(cardinality(classes[a[pc]])).append(" bytes)");
                case SCAN_WHILE_BYTE -> sb.append("SCAN_WHILE_BYTE ").append(render(a[pc]))
                        .append(bounds(pc));
                case SCAN_UNTIL_BYTE -> sb.append("SCAN_UNTIL_BYTE ").append(render(a[pc]))
                        .append(bounds(pc));
                case SCAN_WHILE_CLASS -> sb.append("SCAN_WHILE_CLASS #").append(a[pc])
                        .append(" (").append(cardinality(classes[a[pc]])).append(" bytes)")
                        .append(bounds(pc));
                case SAVE -> sb.append("SAVE            slot ").append(a[pc])
                        .append(" (group ").append(a[pc] / 2)
                        .append(a[pc] % 2 == 0
                                ? " start)"
                                : " end)");
                case BRANCH -> sb.append("BRANCH          ").append(renderBranch(branchTables[a[pc]]));
                case JUMP -> sb.append("JUMP            ").append(a[pc]);
                case ASSERT -> sb.append("ASSERT          ").append(Hir.Kind.VALUES[a[pc]]);
                case MATCH_CHAR -> sb.append("MATCH_CHAR      ").append(charClasses[a[pc]]);
                case SCAN_WHILE_CHAR -> sb.append("SCAN_WHILE_CHAR ").append(charClasses[a[pc]])
                        .append(bounds(pc));
                case ACCEPT -> sb.append("ACCEPT");
                default -> sb.append("?? ").append(op[pc]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String bounds(final int pc) {
        return " {" + b[pc] + ","
               + (c[pc] == Integer.MAX_VALUE
                ? ""
                : c[pc])
               + "}";
    }

    private static String render(final int byteValue) {
        return byteValue >= 0x20 && byteValue < 0x7F
                ? "'" + (char) byteValue + "'"
                : String.format("0x%02X", byteValue);
    }

    private static String renderLiteral(final byte[] literal) {
        final StringBuilder sb = new StringBuilder();
        for (final byte value : literal) {
            sb.append(render(value & 0xFF));
        }
        return sb.toString();
    }

    private static int cardinality(final byte[] table) {
        int count = 0;
        for (final byte entry : table) {
            if (entry != 0) {
                count++;
            }
        }
        return count;
    }

    private static String renderBranch(final int[] table) {
        final StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < 256 && shown < 6; i++) {
            if (table[i] != NO_TARGET) {
                if (shown++ > 0) {
                    sb.append(", ");
                }
                sb.append(render(i)).append("->").append(table[i]);
            }
        }
        if (shown == 6) {
            sb.append(", ...");
        }
        sb.append(" default->").append(table[BRANCH_DEFAULT] == NO_TARGET
                ? "fail"
                : String.valueOf(table[BRANCH_DEFAULT]));
        return sb.toString();
    }
}
