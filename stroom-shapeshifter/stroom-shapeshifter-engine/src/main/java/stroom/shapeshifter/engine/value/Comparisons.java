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

import stroom.shapeshifter.engine.config.Cast;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

/**
 * The comparison spine (design/17 §8): one answer to "which is bigger" shared by the
 * conditions and, when the sequence work lands, by {@code sort}, {@code min} and {@code max}.
 *
 * <p>The rule is strict. Values of the same kind compare natively; {@code Int} against
 * {@code Real} compares numerically — promotion within the one numeric kind, not coercion;
 * and values of different kinds <b>do not compare</b>. Nothing here guesses what bytes mean:
 * the explicit {@link Cast} is the only way a value changes kind, and a cast that fails
 * yields absence, which also does not compare. False in a condition and last in an ordering
 * are the same statement — this value did not participate.
 *
 * <p>Two {@code Bytes} compare <b>on their bytes, unsigned</b>, with no decode. This is not
 * an approximation: UTF-8 is injective, so byte equality is string equality, and UTF-8's
 * unsigned lexicographic byte order is exactly code-point order. The hottest comparison
 * site in the engine — a guard, run per dispatch attempt — allocates nothing.
 */
public final class Comparisons {

    private Comparisons() {
    }

    /**
     * Apply an explicit cast, or pass the value through when there is none.
     *
     * @return the cast value, or null when the value is absent or the cast fails
     */
    public static TypedValue cast(final TypedValue value, final Cast as) {
        if (value == null || as == null) {
            return value;
        }
        return switch (as) {
            case STRING -> value instanceof TypedValue.Bytes ? value : TypedValue.of(value.asString());
            case NUMBER -> {
                // The whole reading first, so a long-range integer is not rounded through a
                // double on its way into a comparison.
                final Long whole = value.asInteger();
                if (whole != null) {
                    yield new TypedValue.Int(whole);
                }
                final Double number = value.asNumber();
                yield number == null ? null : new TypedValue.Real(number);
            }
            case BOOLEAN -> {
                final Boolean truth = value.asBoolean();
                yield truth == null ? null : new TypedValue.Bool(truth);
            }
            case DATE -> switch (value) {
                case TypedValue.Instant instant -> instant;
                // The ISO reading, offset or Z required — anything less goes through
                // parse-date, which has a pattern and a zone. A number has no date reading
                // here either: a unit must be named, which is parse-date's epoch-millis.
                case TypedValue.Bytes bytes -> {
                    try {
                        final OffsetDateTime parsed = OffsetDateTime.parse(value.asString().trim());
                        yield new TypedValue.Instant(parsed.toEpochSecond(), parsed.getNano(),
                                parsed.getOffset().getTotalSeconds());
                    } catch (final DateTimeParseException e) {
                        yield null;
                    }
                }
                default -> null;
            };
        };
    }

    /**
     * Compare two values under the strict rule.
     *
     * @return negative, zero or positive as usual — or null when the two do not compare:
     *         either side absent, or the kinds differ
     */
    public static Integer compare(final TypedValue left, final TypedValue right) {
        if (left == null || right == null) {
            return null;
        }
        if (left instanceof TypedValue.Bytes a && right instanceof TypedValue.Bytes b) {
            return Arrays.compareUnsigned(a.value(), b.value());
        }
        if (isNumeric(left) && isNumeric(right)) {
            if (left instanceof TypedValue.Int a && right instanceof TypedValue.Int b) {
                return Long.compare(a.value(), b.value());
            }
            return Double.compare(left.asNumber(), right.asNumber());
        }
        if (left instanceof TypedValue.Bool a && right instanceof TypedValue.Bool b) {
            return Boolean.compare(a.value(), b.value());
        }
        if (left instanceof TypedValue.Instant a && right instanceof TypedValue.Instant b) {
            // The timeline, and nothing else: the carried offset is inert (design/17 §3).
            final int seconds = Long.compare(a.epochSecond(), b.epochSecond());
            return seconds != 0 ? seconds : Integer.compare(a.nano(), b.nano());
        }
        return null;
    }

    private static boolean isNumeric(final TypedValue value) {
        return value instanceof TypedValue.Int || value instanceof TypedValue.Real;
    }
}
