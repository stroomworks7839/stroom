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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One encoding's byte facts, in the questions the rest of the module asks — design 19 phase 3,
 * the object the phase-1 seam parameters were placed to carry.
 *
 * <p>Compile time: the parser lowers literals through {@link #encode} and bakes each class's
 * {@link #leadBytes} and {@link #lengthBounds} into the HIR, and the compilers turn sets into
 * byte machines through {@link #sequences}. Match time: the engines' character-boundary gates
 * ask {@link #splitsCharacter} and {@link #continuation}, and the two genuinely textual
 * readers — backreference comparison, the word boundary — ask {@link #decode}. Nothing else
 * may know which encoding it serves; that is 01 §4.0's "one boundary", held by construction.
 */
public sealed interface ByteForm {

    /** The compiled form of {@link Encoding#UTF_8}. */
    ByteForm UTF8 = new Utf8Form();

    static ByteForm of(final Encoding encoding) {
        return switch (encoding) {
            case Encoding.Utf8 ignored -> UTF8;
            case Encoding.Table table -> new TableForm(table);
        };
    }

    /** The byte sequence encoding {@code codePoint}, or null where this encoding has none. */
    byte[] encode(int codePoint);

    /** Byte-range alternatives recognising exactly the valid encodings of members. */
    int[][] sequences(CodePointSet set);

    /** The bytes that can begin a member's encoding. */
    BitSet leadBytes(CodePointSet set);

    /** {min, max} encoded length over the encodable members; {1, 1} for an empty lowering. */
    int[] lengthBounds(CodePointSet set);

    /** The code point at {@code pos}, or -1 for none: malformed, unmapped, or past the limit. */
    int decode(byte[] data, int pos, int limit);

    /** How many bytes {@link #decode}'s answer occupied. */
    int encodedLength(int codePoint);

    /** Whether {@code at} falls inside a character and so cannot begin a match. */
    boolean splitsCharacter(byte[] data, int at, int contextEnd);

    /** Whether {@code b} continues a character rather than beginning one. */
    boolean continuation(byte b);

    /** One byte is one character: offsets are character positions, counts are byte counts. */
    boolean singleByte();

    /** UTF-8, by delegation to {@link Utf8} — the behaviour every pattern had before phase 3. */
    record Utf8Form() implements ByteForm {

        @Override
        public byte[] encode(final int codePoint) {
            return Utf8.encode(codePoint);
        }

        @Override
        public int[][] sequences(final CodePointSet set) {
            return Utf8.sequences(set);
        }

        @Override
        public BitSet leadBytes(final CodePointSet set) {
            return Utf8.leadBytes(set);
        }

        @Override
        public int[] lengthBounds(final CodePointSet set) {
            int min = 4;
            int max = 1;
            for (final int[] sequence : Utf8.sequences(set)) {
                min = Math.min(min, sequence.length / 2);
                max = Math.max(max, sequence.length / 2);
            }
            return new int[]{Math.min(min, max), max};
        }

        @Override
        public int decode(final byte[] data, final int pos, final int limit) {
            return pos >= limit
                    ? -1
                    : Utf8.decode(data, pos, limit);
        }

        @Override
        public int encodedLength(final int codePoint) {
            return Utf8.encodedLength(codePoint);
        }

        @Override
        public boolean splitsCharacter(final byte[] data, final int at, final int contextEnd) {
            return Utf8.splitsCharacter(data, at, contextEnd);
        }

        @Override
        public boolean continuation(final byte b) {
            return Utf8.isContinuation(b);
        }

        @Override
        public boolean singleByte() {
            return false;
        }

        @Override
        public String toString() {
            return "UTF_8";
        }
    }

    /**
     * A single-byte table: one byte, one character, no byte structure to guard. The strict
     * reading of an unmapped byte falls out of the mapping itself — it decodes to nothing, so
     * no class contains it and no literal produces it (D38).
     */
    final class TableForm implements ByteForm {

        private final Encoding.Table table;
        private final Map<Integer, Byte> inverse;

        TableForm(final Encoding.Table table) {
            this.table = table;
            this.inverse = new HashMap<>();
            for (int b = 0; b < 256; b++) {
                final int codePoint = table.codePointOf(b);
                if (codePoint >= 0) {
                    inverse.put(codePoint, (byte) b);
                }
            }
        }

        @Override
        public byte[] encode(final int codePoint) {
            final Byte encoded = inverse.get(codePoint);
            return encoded == null
                    ? null
                    : new byte[]{encoded};
        }

        /** Member bytes, compressed to ranges: every alternative is one {lo, hi} pair. */
        @Override
        public int[][] sequences(final CodePointSet set) {
            final List<int[]> out = new ArrayList<>();
            int from = -1;
            for (int b = 0; b <= 256; b++) {
                final boolean member = b < 256
                                       && table.codePointOf(b) >= 0
                                       && set.contains(table.codePointOf(b));
                if (member && from < 0) {
                    from = b;
                } else if (!member && from >= 0) {
                    out.add(new int[]{from, b - 1});
                    from = -1;
                }
            }
            return out.toArray(new int[0][]);
        }

        @Override
        public BitSet leadBytes(final CodePointSet set) {
            final BitSet bits = new BitSet(256);
            for (final int[] range : sequences(set)) {
                bits.set(range[0], range[1] + 1);
            }
            return bits;
        }

        @Override
        public int[] lengthBounds(final CodePointSet set) {
            return new int[]{1, 1};
        }

        @Override
        public int decode(final byte[] data, final int pos, final int limit) {
            return pos >= limit
                    ? -1
                    : table.codePointOf(data[pos] & 0xFF);
        }

        @Override
        public int encodedLength(final int codePoint) {
            return 1;
        }

        @Override
        public boolean splitsCharacter(final byte[] data, final int at, final int contextEnd) {
            return false;
        }

        @Override
        public boolean continuation(final byte b) {
            return false;
        }

        @Override
        public boolean singleByte() {
            return true;
        }

        @Override
        public String toString() {
            return table.name();
        }
    }
}
