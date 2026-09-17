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


import stroom.shapeshifter.config.Codec;
import stroom.shapeshifter.engine.match.Codecs;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;
import java.util.function.LongBinaryOperator;
import java.util.regex.Pattern;

/**
 * The function library: the operations a configuration can apply to a value.
 *
 * <p>They are pure, which is why they live here rather than in the body interpreter. Every one takes the
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
            return null;
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

    /**
     * Replace a literal, on the value's UTF-8 bytes (design 41): the search is byte-wise, which
     * for well-formed UTF-8 is the same search as character-wise since the encoding is
     * self-synchronising, and a value the pattern does not occur in is returned as itself —
     * no string decoded, nothing re-encoded, nothing allocated. The XML parse row spent forty
     * per cent of its time in three of these per attribute value, almost all on values with
     * nothing to replace. A value that is not bytes takes the string path it always took.
     */
    public static TypedValue replaceLiteral(final List<TypedValue> inputs,
                                            final String pattern,
                                            final String replacement) {
        if (inputs.isEmpty()) {
            return null;
        }
        final TypedValue value = inputs.getFirst();
        if (!(value instanceof TypedValue.Bytes) || pattern.isEmpty()) {
            final String input = value.asString();
            return TypedValue.of(input.replace(pattern, replacement));
        }
        // A slice under a UTF-8-compatible reading is searched where it lies; anything else
        // takes its UTF-8 form, which for a whole value is the array itself.
        final TypedValue.Bytes text = (TypedValue.Bytes) value;
        final boolean inPlace = text.encoding().isUtf8Compatible();
        final byte[] bytes = inPlace ? text.readArray() : text.asUtf8();
        final int start = inPlace ? text.readOffset() : 0;
        final int end = inPlace ? start + text.readLength() : bytes.length;
        final byte[] target = pattern.getBytes(StandardCharsets.UTF_8);
        int at = indexOf(bytes, target, start, end);
        if (at < 0) {
            return value;
        }
        final byte[] with = replacement.getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(
                end - start + Math.max(0, with.length - target.length) * 4);
        int from = start;
        while (at >= 0) {
            out.write(bytes, from, at - from);
            out.write(with, 0, with.length);
            from = at + target.length;
            at = indexOf(bytes, target, from, end);
        }
        out.write(bytes, from, end - from);
        return TypedValue.utf8(out.toByteArray());
    }

    private static int indexOf(final byte[] haystack, final byte[] needle, final int from, final int end) {
        final byte first = needle[0];
        final int last = end - needle.length;
        for (int i = from; i <= last; i++) {
            if (haystack[i] != first) {
                continue;
            }
            int j = 1;
            while (j < needle.length && haystack[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The bytes a value encodes, decoded by a codec — the old {@code Decode} step as a transform
     * (design 38). The input is the bytes as read, not their UTF-8 form: a compressed block in a
     * raw slice has bytes above 0x7F that transcoding would double (design 38 phase 3).
     */
    public static TypedValue decode(final List<TypedValue> inputs, final Codec codec) {
        if (inputs.isEmpty() || !(inputs.getFirst() instanceof final TypedValue.Bytes bytes)) {
            return null;
        }
        if (codec.text()) {
            // A text codec reads characters, so it reads the UTF-8 form — a whole UTF-8 value's
            // own array — and a value it leaves untouched is returned as itself (design 41).
            final byte[] input = bytes.asUtf8();
            final byte[] decoded = Codecs.decode(input, codec);
            return decoded == input ? bytes : decoded == null ? null : TypedValue.utf8(decoded);
        }
        final byte[] array = bytes.readArray();
        final int from = bytes.readOffset();
        final int to = from + bytes.readLength();
        final byte[] input = from == 0 && to == array.length ? array : Arrays.copyOfRange(array, from, to);
        final byte[] decoded = Codecs.decode(input, codec);
        return decoded == null ? null : TypedValue.utf8(decoded);
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
        return Arrays.stream(input.asString().split(Pattern.quote(delimiter), -1))
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
        // The typed cast (design/17 §3.1): the point decides the kind, and the result is a
        // number rather than a rendering of one, so a whole Real renders in the engine's own
        // format, without a trailing .0 (§16.8). The parses are Numbers' non-throwing pair
        // (E26).
        if (trimmed.contains(".")) {
            final Double real = Numbers.real(trimmed);
            return real == null ? null : new TypedValue.Double(real);
        }
        final Long whole = Numbers.whole(trimmed);
        return whole == null ? null : new TypedValue.Integer(whole);
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
                return new TypedValue.Double(-(double) Long.MIN_VALUE);
            }
            return longs[0] % longs[1] == 0
                    ? new TypedValue.Integer(longs[0] / longs[1])
                    : new TypedValue.Double((double) longs[0] / longs[1]);
        }
        final double[] doubles = numbers(inputs);
        if (doubles == null || doubles[1] == 0.0) {
            return null;
        }
        return new TypedValue.Double(doubles[0] / doubles[1]);
    }

    /** {@code a mod b} — the sign follows the dividend (XPath's {@code mod}, Java's {@code %}). */
    public static TypedValue mod(final List<TypedValue> inputs) {
        final long[] longs = integers(inputs);
        if (longs != null) {
            return longs[1] == 0 ? null : new TypedValue.Integer(longs[0] % longs[1]);
        }
        final double[] doubles = numbers(inputs);
        if (doubles == null || doubles[1] == 0.0) {
            return null;
        }
        return new TypedValue.Double(doubles[0] % doubles[1]);
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
                    ? new TypedValue.Double(Math.abs((double) whole))
                    : new TypedValue.Integer(Math.abs(whole));
        }
        final Double value = inputs.getFirst().asNumber();
        return value == null ? null : new TypedValue.Double(Math.abs(value));
    }

    /** A rounding operation: identity on a whole number, the rule on a fractional one. */
    private static TypedValue unary(final List<TypedValue> inputs,
                                    final DoubleUnaryOperator operation) {
        if (inputs.size() != 1) {
            return null;
        }
        final Long whole = inputs.getFirst().asInteger();
        if (whole != null) {
            return new TypedValue.Integer(whole);
        }
        final Double value = inputs.getFirst().asNumber();
        if (value == null) {
            return null;
        }
        final double result = operation.applyAsDouble(value);
        return result == Math.rint(result) && !Double.isInfinite(result) && Math.abs(result) < 0x1p63
                ? new TypedValue.Integer((long) result)
                : new TypedValue.Double(result);
    }

    private static TypedValue fold(final List<TypedValue> inputs,
                                   final LongBinaryOperator exact,
                                   final DoubleBinaryOperator approximate) {
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
                return new TypedValue.Integer(result);
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
        return new TypedValue.Double(result);
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
                : new TypedValue.Integer(input.codePointCount(0, input.length()));
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
    public static TypedValue formatNumber(final List<TypedValue> inputs, final DecimalFormat format) {
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
}
