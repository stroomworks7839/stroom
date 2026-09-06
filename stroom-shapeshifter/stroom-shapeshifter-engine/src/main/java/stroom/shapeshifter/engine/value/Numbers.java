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

package stroom.shapeshifter.engine.value;

/**
 * Reading numbers out of text <b>without throwing</b> — E26's mechanism, in one place.
 *
 * <p>The JDK's parsers answer "that is not a number" by constructing an exception and filling
 * in a stack trace, which measured at 3450 ns against 42 ns for text that does parse. On data
 * where a value being absent or malformed is ordinary — which is all log data — that is the
 * common path, not the exceptional one.
 *
 * <p>This lived as a private helper inside {@link TypedValue} when E26 was first fixed, which
 * is exactly why the fix was incomplete: three sibling sites went on paying the same cost a
 * function away, because the cheap answer was not reachable from them. One home, four
 * callers.
 *
 * <p><b>Nothing here changes what parses.</b> Both methods accept and reject precisely what
 * {@code Long.valueOf} and {@code Double.valueOf} accept and reject, which
 * {@code TypedValueParseEquivalenceTest} asserts by differential comparison rather than by example.
 */
final class Numbers {

    private Numbers() {
    }

    /**
     * A whole number, or null — {@code Long.valueOf}'s own algorithm, accumulating negatively
     * so that {@link Long#MIN_VALUE} is representable, and reading digits through
     * {@link Character#digit} rather than an ASCII range check, because Java reads non-ASCII
     * digits here and a performance fix must not quietly stop.
     */
    static Long whole(final String text) {
        if (text.isEmpty()) {
            return null;
        }
        final char first = text.charAt(0);
        final boolean negative = first == '-';
        int i = (negative || first == '+') ? 1 : 0;
        if (i == text.length()) {
            return null;
        }
        final long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        final long limitBeforeMultiply = limit / 10;
        long result = 0;
        for (; i < text.length(); i++) {
            final int digit = Character.digit(text.charAt(i), 10);
            if (digit < 0 || result < limitBeforeMultiply) {
                return null;
            }
            result *= 10;
            if (result < limit + digit) {
                return null;
            }
            result -= digit;
        }
        return negative ? result : -result;
    }

    /**
     * A number, or null. The parser still runs — floating point is not worth hand-rolling —
     * but only for text that could possibly be one: every value {@code Double.valueOf}
     * accepts begins with a digit, a sign, a point, {@code N} ({@code NaN}) or {@code I}
     * ({@code Infinity}), so the gate refuses only what would have thrown.
     *
     * <p>Deliberately conservative rather than clever: {@code "12abc"} still reaches the
     * parser and still costs an exception, because a gate that tried to decide the whole
     * grammar would eventually disagree with it, and a fix that narrows what parses is a
     * behaviour change wearing a fix's clothes.
     */
    static Double real(final String text) {
        if (text.isEmpty()) {
            return null;
        }
        final char first = text.charAt(0);
        final boolean possible = (first >= '0' && first <= '9')
                                 || first == '-' || first == '+' || first == '.'
                                 || first == 'N' || first == 'I'
                                 || Character.isDigit(first);
        if (!possible) {
            return null;
        }
        try {
            return Double.valueOf(text);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** A whole number that fits an {@code int}, or -1 — for group indexes and the like. */
    static int index(final String text) {
        final Long value = whole(text);
        return value == null || value < 0 || value > Integer.MAX_VALUE ? -1 : (int) (long) value;
    }
}
