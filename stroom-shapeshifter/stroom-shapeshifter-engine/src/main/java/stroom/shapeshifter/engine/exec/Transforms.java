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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The function library: the operations a configuration can apply to a value.
 *
 * <p>They are pure, which is why they live here rather than in the executor. Every one takes the
 * resolved inputs and returns the result, or null to mean "produced nothing" — and producing
 * nothing is different from producing an empty value, because the caller writes one and skips
 * the other.
 *
 * <p>Inputs and results are {@link TypedValue} end to end (design/17 §3.2): the string
 * functions cast to text at the top and wrap their result — identical behaviour, one
 * indirection — so that a typed value survives the pipeline until a function genuinely needs
 * characters.
 */
public final class Transforms {

    private Transforms() {
    }

    /** The first input as text, or null when there is none — the string functions' way in. */
    private static String first(final List<TypedValue> inputs) {
        return inputs.isEmpty() ? null : inputs.getFirst().asString();
    }

    /** Substitute, one search string at a time, in order. XSLT's {@code translate()}. */
    public static TypedValue translate(final List<TypedValue> inputs, final List<String> from, final List<String> to) {
        if (inputs.isEmpty()) {
            return TypedValue.of("");
        }
        String result = inputs.getFirst().asString();
        for (int i = 0; i < from.size(); i++) {
            result = result.replace(from.get(i), i < to.size() ? to.get(i) : "");
        }
        return TypedValue.of(result);
    }

    /**
     * Join what is there, skipping what is not.
     *
     * <p>Skipping empties rather than joining them is what stops a missing middle field turning
     * {@code a, b, c} into {@code a, , c}.
     */
    public static TypedValue stringJoin(final List<TypedValue> inputs, final String separator) {
        final List<String> present = inputs.stream()
                .map(TypedValue::asString)
                .filter(input -> !input.isEmpty())
                .toList();
        return present.isEmpty()
                ? null
                : TypedValue.of(String.join(separator == null ? "" : separator, present));
    }

    /** Replace a literal. */
    public static TypedValue replaceLiteral(final List<TypedValue> inputs,
                                            final String pattern,
                                            final String replacement) {
        final String input = first(inputs);
        return input == null ? null : TypedValue.of(input.replace(pattern, replacement));
    }

    /** Lower-case, in the root locale so that the result does not depend on where it ran. */
    public static TypedValue lowerCase(final List<TypedValue> inputs) {
        final String input = first(inputs);
        return input == null ? null : TypedValue.of(input.toLowerCase(Locale.ROOT));
    }

    /** Upper-case, in the root locale. */
    public static TypedValue upperCase(final List<TypedValue> inputs) {
        final String input = first(inputs);
        return input == null ? null : TypedValue.of(input.toUpperCase(Locale.ROOT));
    }

    /** Collapse each run of whitespace to one space, and drop it at the ends. */
    public static TypedValue normalizeSpace(final List<TypedValue> inputs) {
        final String input = first(inputs);
        return input == null ? null : TypedValue.of(String.join(" ", input.trim().split("\\s+")));
    }

    /** Strip whitespace from both ends. */
    public static TypedValue trim(final List<TypedValue> inputs) {
        final String input = first(inputs);
        return input == null ? null : TypedValue.of(input.trim());
    }

    /**
     * Take part of a value, counted in characters rather than bytes.
     *
     * <p>Characters, because a configuration saying "the first eight" means eight of what a
     * person would count, and a multi-byte character would otherwise be cut in half.
     */
    public static TypedValue substring(final List<TypedValue> inputs, final int start, final Integer length) {
        final String input = first(inputs);
        if (input == null) {
            return null;
        }
        final int[] codePoints = input.codePoints().toArray();
        final int begin = Math.min(Math.max(0, start), codePoints.length);
        final int end = length == null
                ? codePoints.length
                : Math.min(begin + Math.max(0, length), codePoints.length);
        return TypedValue.of(new String(codePoints, begin, end - begin));
    }

    /**
     * Split on a delimiter, into the pieces themselves.
     *
     * <p>The joined form below is what a {@code tokenize} writes straight to output, and was
     * all it could ever do before sequences existed: a sequence pretending to be a string,
     * because there was nowhere to put a sequence (design/17 §6).
     */
    public static List<TypedValue> split(final TypedValue input, final String delimiter) {
        return java.util.Arrays.stream(input.asString().split(Pattern.quote(delimiter), -1))
                .map(TypedValue::of)
                .toList();
    }

    /** Split on a delimiter, one piece per line. */
    public static TypedValue tokenize(final List<TypedValue> inputs, final String delimiter) {
        final String input = first(inputs);
        return input == null
                ? null
                : TypedValue.of(String.join("\n", input.split(Pattern.quote(delimiter), -1)));
    }

    /**
     * Read as a number, and render it back.
     *
     * <p>Whether it is read as a whole number or a fractional one depends on whether it is
     * written with a point, so {@code 5} stays {@code 5} rather than becoming {@code 5.0}.
     */
    public static TypedValue number(final List<TypedValue> inputs) {
        final String input = first(inputs);
        if (input == null) {
            return null;
        }
        final String trimmed = input.trim();
        // Phase 2 made this the typed cast (design/17 §3.1): the point decides the kind,
        // exactly as the ported reading did, but the result is now a number rather than a
        // rendering of one. No configuration in the corpus uses it, so no golden moved;
        // the visible difference from the ported Double.toString is that a whole Real
        // renders without its trailing .0 — the engine's own format, ruled in §16.8.
        // The parses are Numbers' non-throwing pair (E26): this instruction answered "not a
        // number" by throwing too, one function away from the casts the fix first reached.
        if (trimmed.contains(".")) {
            final Double real = Numbers.real(trimmed);
            return real == null ? null : new TypedValue.Real(real);
        }
        final Long whole = Numbers.whole(trimmed);
        return whole == null ? null : new TypedValue.Int(whole);
    }

    // -----------------------------------------------------------------------------------
    // Arithmetic (design/17 §§5, 11). Whole numbers first: when every input has an integral
    // reading the work is exact long arithmetic, and overflow promotes to a double rather
    // than wrapping — a wrapped result is a plausible wrong number, a promoted one is an
    // approximate right one that says so by its type. Any input with no numeric reading
    // makes the result absent. Arity is the compiled instruction's check, not these
    // functions': they fold what they are given.
    // -----------------------------------------------------------------------------------

    /** Fold {@code +}. */
    public static TypedValue add(final List<TypedValue> inputs) {
        return fold(inputs, Math::addExact, Double::sum);
    }

    /** {@code a - b}. */
    public static TypedValue subtract(final List<TypedValue> inputs) {
        return fold(inputs, Math::subtractExact, (a, b) -> a - b);
    }

    /** Fold {@code *}. */
    public static TypedValue multiply(final List<TypedValue> inputs) {
        return fold(inputs, Math::multiplyExact, (a, b) -> a * b);
    }

    /** {@code a / b} — whole when exact, fractional otherwise; division by zero is absent. */
    public static TypedValue divide(final List<TypedValue> inputs) {
        final long[] longs = integers(inputs);
        if (longs != null) {
            if (longs[1] == 0) {
                return null;
            }
            if (longs[0] == Long.MIN_VALUE && longs[1] == -1) {
                // The one long division with no long answer: Java's / wraps it silently to
                // MIN_VALUE — a negative result for a positive quotient. Promotion, not
                // wrapping (§11), exactly as the exact folds already do via ArithmeticException.
                return new TypedValue.Real(-(double) Long.MIN_VALUE);
            }
            return longs[0] % longs[1] == 0
                    ? new TypedValue.Int(longs[0] / longs[1])
                    : new TypedValue.Real((double) longs[0] / longs[1]);
        }
        final double[] doubles = numbers(inputs);
        if (doubles == null || doubles[1] == 0.0) {
            return null;
        }
        return new TypedValue.Real(doubles[0] / doubles[1]);
    }

    /** {@code a mod b} — the sign follows the dividend (XPath's {@code mod}, Java's {@code %}). */
    public static TypedValue mod(final List<TypedValue> inputs) {
        final long[] longs = integers(inputs);
        if (longs != null) {
            return longs[1] == 0 ? null : new TypedValue.Int(longs[0] % longs[1]);
        }
        final double[] doubles = numbers(inputs);
        if (doubles == null || doubles[1] == 0.0) {
            return null;
        }
        return new TypedValue.Real(doubles[0] % doubles[1]);
    }

    /** Round half-up on ties — XPath's rule, {@code floor(x + 0.5)}: {@code -2.5} rounds to {@code -2}. */
    public static TypedValue round(final List<TypedValue> inputs) {
        return unary(inputs, x -> Math.floor(x + 0.5));
    }

    /** XPath's {@code floor()}. */
    public static TypedValue floor(final List<TypedValue> inputs) {
        return unary(inputs, Math::floor);
    }

    /** XPath's {@code ceiling()}. */
    public static TypedValue ceiling(final List<TypedValue> inputs) {
        return unary(inputs, Math::ceil);
    }

    /** XPath's {@code abs()}. */
    public static TypedValue abs(final List<TypedValue> inputs) {
        if (inputs.size() != 1) {
            return null;
        }
        final Long whole = inputs.getFirst().asInteger();
        if (whole != null) {
            // Math.absExact would throw on MIN_VALUE; the promotion rule applies (§11).
            return whole == Long.MIN_VALUE
                    ? new TypedValue.Real(Math.abs((double) whole))
                    : new TypedValue.Int(Math.abs(whole));
        }
        final Double value = inputs.getFirst().asNumber();
        return value == null ? null : new TypedValue.Real(Math.abs(value));
    }

    /** A rounding operation: identity on a whole number, the rule on a fractional one. */
    private static TypedValue unary(final List<TypedValue> inputs,
                                    final java.util.function.DoubleUnaryOperator operation) {
        if (inputs.size() != 1) {
            return null;
        }
        final Long whole = inputs.getFirst().asInteger();
        if (whole != null) {
            return new TypedValue.Int(whole);
        }
        final Double value = inputs.getFirst().asNumber();
        if (value == null) {
            return null;
        }
        final double result = operation.applyAsDouble(value);
        return result == Math.rint(result) && !Double.isInfinite(result) && Math.abs(result) < 0x1p63
                ? new TypedValue.Int((long) result)
                : new TypedValue.Real(result);
    }

    private static TypedValue fold(final List<TypedValue> inputs,
                                   final java.util.function.LongBinaryOperator exact,
                                   final java.util.function.DoubleBinaryOperator approximate) {
        if (inputs.isEmpty()) {
            return null;
        }
        final long[] longs = integers(inputs);
        if (longs != null) {
            try {
                long result = longs[0];
                for (int i = 1; i < longs.length; i++) {
                    result = exact.applyAsLong(result, longs[i]);
                }
                return new TypedValue.Int(result);
            } catch (final ArithmeticException overflow) {
                // Fall through to the approximate fold: promotion, not wrapping (§11).
            }
        }
        final double[] doubles = numbers(inputs);
        if (doubles == null) {
            return null;
        }
        double result = doubles[0];
        for (int i = 1; i < doubles.length; i++) {
            result = approximate.applyAsDouble(result, doubles[i]);
        }
        return new TypedValue.Real(result);
    }

    /** Every input's integral reading, or null if any input lacks one. */
    private static long[] integers(final List<TypedValue> inputs) {
        final long[] values = new long[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            final Long value = inputs.get(i).asInteger();
            if (value == null) {
                return null;
            }
            values[i] = value;
        }
        return values;
    }

    /** Every input's numeric reading, or null if any input lacks one. */
    private static double[] numbers(final List<TypedValue> inputs) {
        final double[] values = new double[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            final Double value = inputs.get(i).asNumber();
            if (value == null) {
                return null;
            }
            values[i] = value;
        }
        return values;
    }

    // -----------------------------------------------------------------------------------
    // The string additions (design/17 §6)
    // -----------------------------------------------------------------------------------

    /** Length in code points, as a whole number. XSLT: {@code string-length()}. */
    public static TypedValue stringLength(final List<TypedValue> inputs) {
        final String input = first(inputs);
        return input == null
                ? null
                : new TypedValue.Int(input.codePointCount(0, input.length()));
    }

    /**
     * The part before the first occurrence of a marker — <b>absent when not found</b>, where
     * XSLT returns the empty string. The written output is identical (both write nothing);
     * the testable value is not, and absent is the engine's word for "no answer".
     */
    public static TypedValue substringBefore(final List<TypedValue> inputs, final String marker) {
        final String input = first(inputs);
        if (input == null) {
            return null;
        }
        final int at = input.indexOf(marker);
        return at < 0 ? null : TypedValue.of(input.substring(0, at));
    }

    /** The part after the first occurrence of a marker; absent when not found, as above. */
    public static TypedValue substringAfter(final List<TypedValue> inputs, final String marker) {
        final String input = first(inputs);
        if (input == null) {
            return null;
        }
        final int at = input.indexOf(marker);
        return at < 0 ? null : TypedValue.of(input.substring(at + marker.length()));
    }

    /** {@code starts-with()} as a value. */
    public static TypedValue startsWith(final List<TypedValue> inputs, final String prefix) {
        final String input = first(inputs);
        return input == null ? null : new TypedValue.Bool(input.startsWith(prefix));
    }

    /** {@code ends-with()} as a value. */
    public static TypedValue endsWith(final List<TypedValue> inputs, final String suffix) {
        final String input = first(inputs);
        return input == null ? null : new TypedValue.Bool(input.endsWith(suffix));
    }

    /** {@code contains()} as a value. */
    public static TypedValue contains(final List<TypedValue> inputs, final String substring) {
        final String input = first(inputs);
        return input == null ? null : new TypedValue.Bool(input.contains(substring));
    }

    /**
     * Format a number through a compiled picture. The format instance belongs to the compiled
     * instruction and the engine runs one execution at a time (D35), which is what makes the
     * stateful {@code DecimalFormat} safe to reuse.
     */
    public static TypedValue formatNumber(final List<TypedValue> inputs, final java.text.DecimalFormat format) {
        if (inputs.isEmpty()) {
            return null;
        }
        final Long whole = inputs.getFirst().asInteger();
        if (whole != null) {
            return TypedValue.of(format.format((long) whole));
        }
        final Double value = inputs.getFirst().asNumber();
        return value == null ? null : TypedValue.of(format.format((double) value));
    }

    // -----------------------------------------------------------------------------------
    // Regex replacement
    // -----------------------------------------------------------------------------------

    /**
     * Replace every match of a pattern, expanding group references in the replacement.
     *
     * <p>The expansion syntax is the one the dialect's own users will expect — Rust's, not
     * {@code java.util.regex}'s. {@code $1} and {@code ${1}} are groups, {@code $name} and
     * {@code ${name}} are named groups, and {@code $$} is a literal dollar. A reference to a
     * group that did not participate expands to nothing rather than failing, which matters when
     * the pattern has optional parts.
     *
     * <p>An empty match advances by one character rather than looping, which is the difference
     * between replacing every position and never finishing.
     */
    public static String replaceRegex(final BytePattern pattern, final String input, final String replacement) {
        final byte[] data = input.getBytes(StandardCharsets.UTF_8);
        final ByteMatcher matcher = pattern.matcher();
        final StringBuilder result = new StringBuilder(input.length());

        int cursor = 0;
        while (cursor <= data.length) {
            if (!matcher.match(data, cursor, data.length, Anchoring.UNANCHORED)) {
                break;
            }
            final int start = matcher.start();
            final int end = matcher.end();
            result.append(new String(data, cursor, start - cursor, StandardCharsets.UTF_8));
            expand(replacement, matcher, pattern, result);

            if (end == start) {
                // Zero-width match: emit the character it sat before, or stop at the end.
                if (end >= data.length) {
                    cursor = end;
                    break;
                }
                final int next = nextCharacter(data, end);
                result.append(new String(data, end, next - end, StandardCharsets.UTF_8));
                cursor = next;
            } else {
                cursor = end;
            }
        }
        if (cursor < data.length) {
            result.append(new String(data, cursor, data.length - cursor, StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    /** Expand a replacement's {@code $} references against a match. */
    private static void expand(final String replacement,
                               final ByteMatcher matcher,
                               final BytePattern pattern,
                               final StringBuilder out) {
        int i = 0;
        while (i < replacement.length()) {
            final char c = replacement.charAt(i);
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= replacement.length()) {
                out.append('$');
                break;
            }
            if (replacement.charAt(i + 1) == '$') {
                out.append('$');
                i += 2;
                continue;
            }

            final String name;
            if (replacement.charAt(i + 1) == '{') {
                final int close = replacement.indexOf('}', i + 2);
                if (close < 0) {
                    out.append('$');
                    i++;
                    continue;
                }
                name = replacement.substring(i + 2, close);
                i = close + 1;
            } else {
                int end = i + 1;
                while (end < replacement.length() && isNameCharacter(replacement.charAt(end))) {
                    end++;
                }
                if (end == i + 1) {
                    out.append('$');
                    i++;
                    continue;
                }
                name = replacement.substring(i + 1, end);
                i = end;
            }
            out.append(group(matcher, pattern, name));
        }
    }

    private static String group(final ByteMatcher matcher, final BytePattern pattern, final String name) {
        // A numeric reference names its group directly; anything else is a named group.
        // Non-throwing (E26): a named reference like $word used to cost an exception on
        // every expansion, which is per match in a regex replace.
        int index = Numbers.index(name);
        if (index < 0) {
            index = pattern.groupIndex(name);
        }
        if (index < 0 || index > pattern.groupCount() || !matcher.matchedGroup(index)) {
            return "";
        }
        return matcher.groupString(index);
    }

    private static boolean isNameCharacter(final char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Where the character starting at {@code from} ends, in UTF-8. */
    private static int nextCharacter(final byte[] data, final int from) {
        int next = from + 1;
        while (next < data.length && (data[next] & 0xC0) == 0x80) {
            next++;
        }
        return next;
    }
}
