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
 * Word characters, the boundary test built on them, and — since the boundary test is its
 * heaviest case — the one shared evaluation of the zero-width assertions
 * ({@link #assertionHolds}) that every engine delegates to.
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

        /** Built by {@link UnicodeClasses}' predicate sweep, which coalesces runs into ranges. */
        private static final CodePointSet SET = UnicodeClasses.build(Unicode::isWordCodePoint);

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

    /**
     * Word membership for ASCII bytes, on which the Unicode and ASCII definitions agree —
     * {@code [a-zA-Z0-9_]} both ways. Indexed by byte value below 0x80.
     */
    private static final boolean[] ASCII_WORD = new boolean[0x80];

    static {
        for (int b = 0; b < 0x80; b++) {
            ASCII_WORD[b] = (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                            || (b >= '0' && b <= '9') || b == '_';
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
                                     final boolean unicode,
                                     final ByteForm form) {
        // The ASCII fast path. A backtracker evaluates \b at every position it backs off
        // through, and over log data both neighbours are almost always ASCII — where the two
        // word definitions agree and a table lookup answers what two UTF-8 decodes and two
        // binary searches over the Unicode set would. Measured under FANCY_LOOKAHEAD, whose
        // lookahead body re-evaluates \b per backed-off byte.
        final int before = cursor > regionFrom
                ? data[cursor - 1] & 0xFF
                : -1;
        final int at = cursor < to
                ? data[cursor] & 0xFF
                : -1;
        if (before < 0x80 && at < 0x80) {
            return (before >= 0 && ASCII_WORD[before]) != (at >= 0 && ASCII_WORD[at]);
        }
        return atBoundarySlow(data, regionFrom, to, cursor, unicode, form);
    }

    private static boolean atBoundarySlow(final byte[] data,
                                          final int regionFrom,
                                          final int to,
                                          final int cursor,
                                          final boolean unicode,
                                          final ByteForm form) {
        final CodePointSet words = unicode
                ? Unicode.SET
                : ASCII;
        final int before = characterBefore(data, regionFrom, cursor, form);
        final int after = form.decode(data, cursor, to);
        final boolean wordBefore = before >= 0 && words.contains(before);
        final boolean wordAfter = after >= 0 && words.contains(after);
        return wordBefore != wordAfter;
    }

    /** The code point ending at {@code cursor}, or -1 at the region start or on malformed bytes. */
    private static int characterBefore(final byte[] data, final int regionFrom, final int cursor,
                                       final ByteForm form) {
        if (cursor <= regionFrom) {
            return -1;
        }
        if (form.singleByte()) {
            return form.decode(data, cursor - 1, cursor);
        }
        int start = cursor - 1;
        // Continuation bytes are 10xxxxxx, so stepping back over them finds the lead byte. A
        // well-formed sequence has at most three, which bounds the walk on malformed input —
        // a run of stray continuation bytes must read as non-word, not send this to the
        // region start.
        while (start > regionFrom && cursor - start < 4 && Utf8.isContinuation(data[start])) {
            start--;
        }
        if (start + Utf8.sequenceLength(data[start] & 0xFF) != cursor) {
            return -1; // no character ends exactly at the cursor: malformed input
        }
        return form.decode(data, start, cursor);
    }

    /**
     * Whether {@code kind} holds at {@code pos}. Shared by every engine — an assertion depends
     * only on the data and the position, so one implementation serves them all — except
     * {@code \G}, which depends on the search and is evaluated by the engines that support it
     * before delegating here.
     */
    static boolean assertionHolds(final Hir.Kind kind,
                                  final byte[] data,
                                  final int regionFrom,
                                  final int to,
                                  final int pos,
                                  final ByteForm form) {
        return switch (kind) {
            case START_INPUT -> pos == regionFrom;
            case START_LINE -> pos == regionFrom || data[pos - 1] == '\n';
            case END_INPUT -> pos == to;
            case END_LINE -> pos == to || data[pos] == '\n';
            case WORD_BOUNDARY -> atBoundary(data, regionFrom, to, pos, true, form);
            case NOT_WORD_BOUNDARY -> !atBoundary(data, regionFrom, to, pos, true, form);
            case WORD_BOUNDARY_ASCII -> atBoundary(data, regionFrom, to, pos, false, form);
            case NOT_WORD_BOUNDARY_ASCII -> !atBoundary(data, regionFrom, to, pos, false, form);
            // Depends on the search, not only the data; the engines that support it evaluate
            // it before delegating here, and no other engine ever receives it.
            case PREVIOUS_MATCH_END -> throw new IllegalStateException(
                    "\\G reached an engine that cannot evaluate it");
        };
    }
}
