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
 * Word characters, and the boundary test built on them.
 * <p>
 * A word boundary is defined over <em>characters</em>, so with Unicode word characters the test
 * has to decode either side of the cursor rather than look at single bytes. UTF-8 makes the
 * backward step cheap — continuation bytes are recognisable on sight, so finding the start of the
 * preceding character is a short walk rather than a rescan.
 */
public final class Words {

    /** {@code \w} with the ASCII meaning, used under {@code (?-u)}. */
    private static final CodePointSet ASCII = CodePointSet.of(
            'a', 'z',
            'A', 'Z',
            '0', '9',
            '_', '_');

    /**
     * {@code \w} with the Unicode meaning. Defined to match what
     * {@code java.util.regex} uses under {@code UNICODE_CHARACTER_CLASS}, which keeps the JDK
     * usable as a differential oracle for this engine's default behaviour.
     */
    private static final class Unicode {

        private static final CodePointSet SET = build();

        private static CodePointSet build() {
            final CodePointSet.Builder builder = new CodePointSet.Builder();
            for (int codePoint = 0; codePoint <= CodePointSet.MAX; codePoint++) {
                if (isWordCodePoint(codePoint)) {
                    builder.add(codePoint, codePoint);
                }
            }
            return builder.build();
        }

        private static boolean isWordCodePoint(final int codePoint) {
            // Alphabetic rather than isLetter, because that is what java.util.regex uses under
            // UNICODE_CHARACTER_CLASS and the two differ over the letter numbers.
            if (Character.isAlphabetic(codePoint) || codePoint == '_') {
                return true;
            }
            return switch (Character.getType(codePoint)) {
                case Character.NON_SPACING_MARK,
                     Character.ENCLOSING_MARK,
                     Character.COMBINING_SPACING_MARK,
                     Character.DECIMAL_DIGIT_NUMBER,
                     Character.CONNECTOR_PUNCTUATION -> true;
                default -> codePoint == 0x200C || codePoint == 0x200D; // join controls
            };
        }
    }

    private Words() {
    }

    public static CodePointSet ascii() {
        return ASCII;
    }

    public static CodePointSet unicode() {
        return Unicode.SET;
    }

    /**
     * Whether a word boundary falls at {@code cursor} — that is, whether exactly one of the
     * characters either side of it is a word character. Positions outside the region count as
     * non-word, so the region edges are boundaries next to a word character.
     */
    public static boolean atBoundary(final byte[] data,
                                     final int regionFrom,
                                     final int to,
                                     final int cursor,
                                     final boolean unicode) {
        final CodePointSet words = unicode
                ? Unicode.SET
                : ASCII;
        final int before = characterBefore(data, regionFrom, cursor);
        final int after = characterAt(data, cursor, to);
        final boolean wordBefore = before >= 0 && words.contains(before);
        final boolean wordAfter = after >= 0 && words.contains(after);
        return wordBefore != wordAfter;
    }

    /** The code point ending at {@code cursor}, or -1 at the region start. */
    private static int characterBefore(final byte[] data, final int regionFrom, final int cursor) {
        if (cursor <= regionFrom) {
            return -1;
        }
        int start = cursor - 1;
        // Continuation bytes are 10xxxxxx, so stepping back over them finds the lead byte.
        while (start > regionFrom && (data[start] & 0xC0) == 0x80) {
            start--;
        }
        return characterAt(data, start, cursor);
    }

    /** The code point beginning at {@code at}, or -1 if there is none or it is ill-formed. */
    private static int characterAt(final byte[] data, final int at, final int to) {
        if (at >= to) {
            return -1;
        }
        final int lead = data[at] & 0xFF;
        final int length = Utf8.sequenceLength(lead);
        if (length == 0 || at + length > to) {
            return -1;
        }
        if (length == 1) {
            return lead;
        }
        int codePoint = lead & (0x7F >> length);
        for (int i = 1; i < length; i++) {
            final int continuation = data[at + i] & 0xFF;
            if ((continuation & 0xC0) != 0x80) {
                return -1;
            }
            codePoint = (codePoint << 6) | (continuation & 0x3F);
        }
        return codePoint;
    }
}
