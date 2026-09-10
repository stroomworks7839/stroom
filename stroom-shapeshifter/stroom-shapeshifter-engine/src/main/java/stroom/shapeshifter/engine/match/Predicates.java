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

import stroom.shapeshifter.engine.config.Predicate;

/**
 * What a character class means, said once.
 *
 * <p>Two callers need this answer and they are in different packages: the compiler, building the
 * byte table a {@code take-while} answers from, and the run, classifying a decoded codepoint the
 * table could not settle. Having both go through one statement is what stops a table and a
 * predicate drifting apart — the table <em>is</em> {@link #matches} evaluated 256 times instead
 * of once per byte read.
 *
 * <p>It is here, in the matching primitives, for that reason: {@link #table} used to sit on the
 * step interpreter, so the compiler reached into the runner to call it. That was invisible while
 * both lived in this package and would have been a package cycle the moment they did not
 * (2026-09-10).
 */
public final class Predicates {

    private Predicates() {
    }

    /**
     * Predicates classify decoded codepoints; the encoding story lives at {@link #decode}.
     */
    public static boolean matches(final Predicate predicate, final int codepoint) {
        return switch (predicate) {
            case final Predicate.Alphabetic ignored -> Character.isLetter(codepoint);
            case final Predicate.Alphanumeric ignored -> Character.isLetterOrDigit(codepoint);
            case final Predicate.Numeric ignored -> Character.isDigit(codepoint);
            case final Predicate.Whitespace ignored -> Character.isWhitespace(codepoint);
            case final Predicate.NonWhitespace ignored -> !Character.isWhitespace(codepoint);
            case final Predicate.Any ignored -> true;
            case final Predicate.Custom custom -> codepoint <= Character.MAX_VALUE
                                                  && inSet(custom.charSet(), (char) codepoint);
        };
    }

    /**
     * The byte table a {@code take-while} answers from, or null when no byte stands alone under
     * this reading and every character has to be decoded.
     *
     * <p>The compiler's entry into the predicate rules, so that what a table says and what
     * {@link #matches} says cannot drift apart: there is one statement of what a character class
     * means, and the table is that statement evaluated 256 times instead of once per byte read.
     * For UTF-8 only the ASCII range can be settled this way; a lead byte still decodes.
     */
    public static boolean[] table(final Predicate predicate, final Decoding decoding) {
        if (decoding.kind() == Decoding.Kind.MULTI_BYTE) {
            return null;
        }
        final boolean[] table = new boolean[256];
        final int limit = decoding.tabular()
                ? 256
                : 0x80;
        for (int b = 0; b < limit; b++) {
            table[b] = matches(predicate, decoding.character(b));
        }
        return table;
    }

    private static boolean inSet(final Predicate.CharSet set, final char c) {
        boolean present = set.chars().contains(c);
        if (!present) {
            for (final Predicate.CharSet.Range range : set.ranges()) {
                if (c >= range.from() && c <= range.to()) {
                    present = true;
                    break;
                }
            }
        }
        return set.negated() != present;
    }
}
