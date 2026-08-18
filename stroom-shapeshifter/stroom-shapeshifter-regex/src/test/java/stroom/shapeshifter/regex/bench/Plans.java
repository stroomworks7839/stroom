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

package stroom.shapeshifter.regex.bench;

/**
 * Two prototype tier 0 plan interpreters, measured against the hand-written scanners in
 * {@link Scanners}.
 * <p>
 * The design claims a deterministic pattern can be compiled to a straight-line scan plan
 * rather than an automaton, and that this is where the performance comes from. The
 * hand-written scanners establish what a specialised implementation achieves; these show what
 * survives once the same operations are driven from a <em>data structure</em> instead of
 * being written out by hand. The gap between them is the interpreter's dispatch overhead, and
 * it decides whether tier 0 can be interpreted at all or has to generate bytecode per plan.
 * <p>
 * Two shapes are measured because they represent the realistic choices:
 * <ul>
 *   <li>{@link #runSealed} — ops as records behind a sealed interface, dispatched with a
 *       pattern-matching switch. The readable, idiomatic Java 25 version.</li>
 *   <li>{@link #runFlat} — ops as parallel {@code int[]} arrays, dispatched with a switch on
 *       an opcode. No per-op objects, no type checks, poor readability.</li>
 * </ul>
 * <p>
 * The plans here are hand-built, since the point is to measure execution rather than
 * compilation. They mirror what the compiler is specified to emit for the two benchmark
 * patterns.
 */
final class Plans {

    // Opcodes for the flat interpreter.
    static final int OP_SCAN_UNTIL_BYTE = 0;
    static final int OP_EXPECT_BYTE = 1;
    static final int OP_SCAN_WHILE_CLASS = 2;
    static final int OP_TAKE_TO_LINE_END = 3;
    static final int OP_SCAN_WHILE_TABLE = 4;

    private Plans() {
    }

    // -----------------------------------------------------------------------------------
    // Sealed op model
    // -----------------------------------------------------------------------------------

    sealed interface Op permits ScanUntilByte, ExpectByte, ScanWhileClass, TakeToLineEnd {

    }

    /** Consume up to, but not including, {@code value}. Captures. */
    record ScanUntilByte(byte value) implements Op {

    }

    /** Consume exactly {@code value} or fail. Does not capture. */
    record ExpectByte(byte value) implements Op {

    }

    /** Consume while the byte is in the 256-bit set. Captures. */
    record ScanWhileClass(long[] bitSet) implements Op {

        boolean contains(final byte b) {
            final int index = b & 0xFF;
            return (bitSet[index >>> 6] & (1L << index)) != 0;
        }
    }

    /** Consume to the next {@code \n} or end of input. Captures. */
    record TakeToLineEnd() implements Op {

    }

    // -----------------------------------------------------------------------------------
    // Plans, mirroring what the compiler is specified to emit
    // -----------------------------------------------------------------------------------

    /** {@code ^([^,\n]*),([^,\n]*),([^,\n]*),([^,\n]*),([^,\n]*)$} */
    static Op[] csvPlan() {
        return new Op[]{
                new ScanUntilByte((byte) ','), new ExpectByte((byte) ','),
                new ScanUntilByte((byte) ','), new ExpectByte((byte) ','),
                new ScanUntilByte((byte) ','), new ExpectByte((byte) ','),
                new ScanUntilByte((byte) ','), new ExpectByte((byte) ','),
                new TakeToLineEnd()};
    }

    /** {@code ^(\S+) (\S+) (\S+) (\S+) (.*)$} */
    static Op[] syslogPlan() {
        final long[] nonSpace = nonWhitespaceClass();
        return new Op[]{
                new ScanWhileClass(nonSpace), new ExpectByte((byte) ' '),
                new ScanWhileClass(nonSpace), new ExpectByte((byte) ' '),
                new ScanWhileClass(nonSpace), new ExpectByte((byte) ' '),
                new ScanWhileClass(nonSpace), new ExpectByte((byte) ' '),
                new TakeToLineEnd()};
    }

    /** The same plans, encoded for the flat interpreter. */
    static int[] csvCode() {
        return new int[]{
                OP_SCAN_UNTIL_BYTE, OP_EXPECT_BYTE,
                OP_SCAN_UNTIL_BYTE, OP_EXPECT_BYTE,
                OP_SCAN_UNTIL_BYTE, OP_EXPECT_BYTE,
                OP_SCAN_UNTIL_BYTE, OP_EXPECT_BYTE,
                OP_TAKE_TO_LINE_END};
    }

    static int[] csvArgs() {
        return new int[]{',', ',', ',', ',', ',', ',', ',', ',', 0};
    }

    static int[] syslogCode() {
        return new int[]{
                OP_SCAN_WHILE_CLASS, OP_EXPECT_BYTE,
                OP_SCAN_WHILE_CLASS, OP_EXPECT_BYTE,
                OP_SCAN_WHILE_CLASS, OP_EXPECT_BYTE,
                OP_SCAN_WHILE_CLASS, OP_EXPECT_BYTE,
                OP_TAKE_TO_LINE_END};
    }

    static int[] syslogArgs() {
        return new int[]{0, ' ', 0, ' ', 0, ' ', 0, ' ', 0};
    }

    static long[][] syslogClasses() {
        return new long[][]{nonWhitespaceClass()};
    }

    /**
     * The syslog plan with class membership expressed as a 256-entry lookup table rather than
     * a 256-bit set — one array load per byte instead of load, two shifts and a mask.
     */
    static int[] syslogCodeTable() {
        final int[] code = syslogCode();
        for (int i = 0; i < code.length; i++) {
            if (code[i] == OP_SCAN_WHILE_CLASS) {
                code[i] = OP_SCAN_WHILE_TABLE;
            }
        }
        return code;
    }

    static byte[][] syslogTables() {
        return new byte[][]{toTable(nonWhitespaceClass())};
    }

    static byte[][] csvTables() {
        return new byte[][]{};
    }

    private static byte[] toTable(final long[] bitSet) {
        final byte[] table = new byte[256];
        for (int i = 0; i < 256; i++) {
            table[i] = (byte) ((bitSet[i >>> 6] & (1L << i)) != 0
                    ? 1
                    : 0);
        }
        return table;
    }

    static long[][] csvClasses() {
        return new long[][]{};
    }

    /** ASCII {@code \S} — every byte except space, tab, newline, vertical tab, form feed, CR. */
    static long[] nonWhitespaceClass() {
        final long[] bits = new long[4];
        for (int i = 0; i < 256; i++) {
            final boolean whitespace = i == ' ' || i == '\t' || i == '\n' || i == 0x0B || i == '\f' || i == '\r';
            if (!whitespace) {
                bits[i >>> 6] |= 1L << i;
            }
        }
        return bits;
    }

    // -----------------------------------------------------------------------------------
    // Interpreter 1 — sealed records, pattern-matching switch
    // -----------------------------------------------------------------------------------

    static long runSealed(final Op[] plan, final byte[] data) {
        long hash = 1L;
        int pos = 0;
        while (pos < data.length) {
            int cursor = pos;
            for (final Op op : plan) {
                switch (op) {
                    case ScanUntilByte scan -> {
                        final int start = cursor;
                        final byte value = scan.value();
                        while (cursor < data.length && data[cursor] != value) {
                            cursor++;
                        }
                        if (cursor == data.length) {
                            return hash;
                        }
                        hash = hash * 31L + (cursor - start);
                    }
                    case ExpectByte expect -> {
                        if (cursor == data.length || data[cursor] != expect.value()) {
                            return hash;
                        }
                        cursor++;
                    }
                    case ScanWhileClass scan -> {
                        final int start = cursor;
                        while (cursor < data.length && scan.contains(data[cursor])) {
                            cursor++;
                        }
                        hash = hash * 31L + (cursor - start);
                    }
                    case TakeToLineEnd ignored -> {
                        final int start = cursor;
                        while (cursor < data.length && data[cursor] != '\n') {
                            cursor++;
                        }
                        hash = hash * 31L + (cursor - start);
                    }
                }
            }
            if (cursor < data.length && data[cursor] == '\n') {
                cursor++;
            }
            pos = cursor;
        }
        return hash;
    }

    // -----------------------------------------------------------------------------------
    // Interpreter 2 — flat opcode arrays, switch on int
    // -----------------------------------------------------------------------------------

    static long runFlat(final int[] code,
                        final int[] args,
                        final long[][] classes,
                        final byte[] data) {
        return runFlat(code, args, classes, new byte[0][], data);
    }

    static long runFlat(final int[] code,
                        final int[] args,
                        final long[][] classes,
                        final byte[][] tables,
                        final byte[] data) {
        long hash = 1L;
        int pos = 0;
        while (pos < data.length) {
            int cursor = pos;
            for (int pc = 0; pc < code.length; pc++) {
                switch (code[pc]) {
                    case OP_SCAN_UNTIL_BYTE -> {
                        final int start = cursor;
                        final byte value = (byte) args[pc];
                        while (cursor < data.length && data[cursor] != value) {
                            cursor++;
                        }
                        if (cursor == data.length) {
                            return hash;
                        }
                        hash = hash * 31L + (cursor - start);
                    }
                    case OP_EXPECT_BYTE -> {
                        if (cursor == data.length || data[cursor] != (byte) args[pc]) {
                            return hash;
                        }
                        cursor++;
                    }
                    case OP_SCAN_WHILE_CLASS -> {
                        final int start = cursor;
                        final long[] bits = classes[args[pc]];
                        while (cursor < data.length) {
                            final int index = data[cursor] & 0xFF;
                            if ((bits[index >>> 6] & (1L << index)) == 0) {
                                break;
                            }
                            cursor++;
                        }
                        hash = hash * 31L + (cursor - start);
                    }
                    case OP_SCAN_WHILE_TABLE -> {
                        final int start = cursor;
                        final byte[] table = tables[args[pc]];
                        while (cursor < data.length && table[data[cursor] & 0xFF] != 0) {
                            cursor++;
                        }
                        hash = hash * 31L + (cursor - start);
                    }
                    case OP_TAKE_TO_LINE_END -> {
                        final int start = cursor;
                        while (cursor < data.length && data[cursor] != '\n') {
                            cursor++;
                        }
                        hash = hash * 31L + (cursor - start);
                    }
                    default -> throw new IllegalStateException("Unknown opcode " + code[pc]);
                }
            }
            if (cursor < data.length && data[cursor] == '\n') {
                cursor++;
            }
            pos = cursor;
        }
        return hash;
    }
}
