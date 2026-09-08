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

package stroom.shapeshifter.engine.match;

import stroom.shapeshifter.engine.text.Encoding;

import java.nio.charset.Charset;

/**
 * How a template's encoding turns bytes into characters for the step vocabulary, decided once
 * when the match compiles rather than re-selected per byte (design 29 §3.3, D51).
 *
 * <p>The step interpreter asks this of every byte a {@code take-while} or an {@code any-char}
 * looks at, which is the tightest loop it has. What it used to ask was the encoding: two
 * identity tests, then whether the encoding is UTF-8-compatible, then whether it is single-byte,
 * then a switch over some twenty constants, and for a single-byte feed a concurrent map lookup
 * for the table. All of that has one answer per encoding, so it is settled here and the loop
 * reads a field.
 *
 * @param encoding the template's encoding, which is also what every value a step produces is
 *                 tagged with
 * @param kind    which of the four readings applies
 * @param table   for {@link Kind#SINGLE_BYTE}, the character each byte value carries; null
 *                otherwise
 * @param charset for {@link Kind#MULTI_BYTE}, the charset a character is decoded through; null
 *                otherwise
 */
public record Decoding(Encoding encoding, Kind kind, char[] table, Charset charset) {

    /** The four ways a byte becomes a character, one per class of encoding. */
    public enum Kind {

        /**
         * Byte-shaped: a value past 0x7F carries no textual meaning and fails every class but
         * {@code Any}, exactly as the old byte predicates behaved. {@code raw} and {@code ascii}
         * both read this way.
         */
        ASCII_LIKE,

        /** UTF-8, and the encodings that are UTF-8 for this purpose. */
        UTF8,

        /** One byte, one character, through {@link #table}. */
        SINGLE_BYTE,

        /** Anything else — UTF-16, Shift_JIS — a character at a time through {@link #charset}. */
        MULTI_BYTE
    }

    /** The reading an encoding gets, with whatever that reading needs already built. */
    public static Decoding of(final Encoding encoding) {
        if (encoding == Encoding.RAW || encoding == Encoding.ASCII) {
            return new Decoding(encoding, Kind.ASCII_LIKE, null, null);
        }
        if (encoding.isUtf8Compatible()) {
            return new Decoding(encoding, Kind.UTF8, null, null);
        }
        if (encoding.isSingleByte()) {
            final char[] chars = new char[256];
            final byte[] one = new byte[1];
            for (int i = 0; i < 256; i++) {
                one[0] = (byte) i;
                final String text = new String(one, encoding.charset());
                chars[i] = text.isEmpty() ? 0xFFFD : text.charAt(0);
            }
            return new Decoding(encoding, Kind.SINGLE_BYTE, chars, null);
        }
        return new Decoding(encoding, Kind.MULTI_BYTE, null, encoding.charset());
    }

    /**
     * Whether every byte value carries exactly one character under this reading, which is what
     * lets a {@code take-while} answer from a table instead of decoding and then classifying.
     */
    public boolean tabular() {
        return kind == Kind.ASCII_LIKE || kind == Kind.SINGLE_BYTE;
    }

    /** The character a byte carries, for a {@link #tabular()} reading. */
    public char character(final int b) {
        return kind == Kind.SINGLE_BYTE ? table[b] : (char) (b <= 0x7F ? b : 0xFFFD);
    }
}
