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

import stroom.shapeshifter.engine.text.Encoding;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

/**
 * A value captured during matching.
 *
 * <p>Bytes are the default and the common case: a capture is a slice of the input, tagged with
 * the encoding it is in and never transcoded until a consumer asks for text (design 25, D43).
 * Most captures never need that — they are written straight back out. The numeric variants
 * exist so that binary match steps, which have already done the work of decoding an integer,
 * do not have to render it to a string and parse it again at the other end. Formatting is
 * deferred to the output boundary in every case.
 */
public sealed interface TypedValue {

    /**
     * Bytes as they were read, and the encoding they are in (design 25, D43). Nothing is
     * transcoded when a value is captured, stored, bound or passed; a consumer that needs text
     * asks for {@link #utf8()}, which is computed on first use and kept. Two values are equal
     * when their text is: the same tag and the same bytes decide it without decoding.
     */
    final class Bytes implements TypedValue {

        private final byte[] value;
        private final Encoding encoding;
        /**
         * The UTF-8 form: the value itself from construction when the tag is UTF-8-compatible,
         * else filled on first use. The run is single-threaded; the two instances shared across
         * runs ({@code Steps.NOTHING}, compile-time literals) are UTF-8-tagged, so they never
         * fill lazily.
         */
        private byte[] utf8;

        private Bytes(final byte[] value, final Encoding encoding) {
            this.value = value;
            this.encoding = encoding;
            this.utf8 = encoding.isUtf8Compatible() ? value : null;
        }

        /** The bytes as read, in {@link #encoding()}. */
        public byte[] value() {
            return value;
        }

        /** What the bytes are in. */
        public Encoding encoding() {
            return encoding;
        }

        /** The bytes as UTF-8 — the array itself when the tag is UTF-8-compatible. */
        public byte[] utf8() {
            if (utf8 == null) {
                utf8 = encoding.decode(value).getBytes(StandardCharsets.UTF_8);
            }
            return utf8;
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Bytes bytes)) {
                return false;
            }
            if (encoding == bytes.encoding && Arrays.equals(value, bytes.value)) {
                return true;
            }
            return Arrays.equals(utf8(), bytes.utf8());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(utf8());
        }

        @Override
        public String toString() {
            return new String(utf8(), StandardCharsets.UTF_8);
        }
    }

    /** A whole number, as XSLT 2.0's {@code xs:integer}; held in a {@code long} (D49). */
    record Integer(long value) implements TypedValue {

    }

    /** A number with a fractional part, as XSLT 2.0's {@code xs:double} (D49). */
    record Double(double value) implements TypedValue {

    }

    /** True or false. */
    record Bool(boolean value) implements TypedValue {

    }

    /**
     * A point on the timeline (design/17 §§3, 9): epoch second and nanosecond, plus the
     * offset the value arrived with — carried as formatting provenance and <b>inert in
     * comparison and arithmetic</b>. Two parses of one moment through different offsets are
     * equal, sort together and subtract to zero; the offset's sole job is being
     * {@code format-date}'s default rendering zone. A null offset means none was parsed and
     * none is claimed.
     */
    record Instant(long epochSecond,
                   int nano,
                   java.lang.Integer offsetSeconds) implements TypedValue {

        public Instant {
            if (nano < 0 || nano > 999_999_999) {
                throw new IllegalArgumentException("Nanos out of range: " + nano);
            }
        }

        /** The timeline point, for the {@code java.time} boundary. */
        public java.time.Instant toJavaInstant() {
            return java.time.Instant.ofEpochSecond(epochSecond, nano);
        }
    }

    /** Wrap bytes, saying what they are in. */
    static TypedValue of(final byte[] value, final Encoding encoding) {
        return new Bytes(value, encoding);
    }

    /** Wrap bytes that are UTF-8 already: a literal, a composite, a function's result. */
    static TypedValue utf8(final byte[] value) {
        return new Bytes(value, Encoding.UTF_8);
    }

    /** Wrap text, as UTF-8 bytes. */
    static TypedValue of(final String value) {
        return new Bytes(value.getBytes(StandardCharsets.UTF_8), Encoding.UTF_8);
    }

    /** True if this value has no content. Empty captures are treated as absent by references. */
    default boolean isEmpty() {
        return this instanceof Bytes bytes && bytes.value().length == 0;
    }

    /**
     * The value as bytes: captured bytes as they are, in their own encoding.
     *
     * <p>Numbers render as ASCII, which is safe in every encoding the engine supports.
     */
    default byte[] asBytes() {
        return switch (this) {
            case Bytes bytes -> bytes.value();
            case Integer value -> Long.toString(value.value()).getBytes(StandardCharsets.US_ASCII);
            case Double value -> format(value.value()).getBytes(StandardCharsets.US_ASCII);
            case Bool value -> Boolean.toString(value.value()).getBytes(StandardCharsets.US_ASCII);
            case Instant value -> iso(value).getBytes(StandardCharsets.US_ASCII);
        };
    }

    /** The value as UTF-8 bytes: captured bytes decoded by their tag, once; the rest as ASCII. */
    default byte[] utf8() {
        return this instanceof Bytes bytes ? bytes.utf8() : asBytes();
    }

    /** The value as text, decoding bytes by their tag. */
    default String asString() {
        return switch (this) {
            case Bytes bytes -> new String(bytes.utf8(), StandardCharsets.UTF_8);
            case Integer value -> Long.toString(value.value());
            case Double value -> format(value.value());
            case Bool value -> Boolean.toString(value.value());
            case Instant value -> iso(value);
        };
    }

    /** The value as a number, parsing bytes if that is what it holds. */
    default java.lang.Double asNumber() {
        return switch (this) {
            case Integer value -> (double) value.value();
            case Double value -> value.value();
            case Bool value -> value.value() ? 1.0 : 0.0;
            case Bytes bytes -> {
                // E26: the parse that answers "no" without throwing (Numbers).
                yield Numbers.real(new String(bytes.utf8(), StandardCharsets.UTF_8).trim());
            }
            // Epoch milliseconds, documented lossy: the escape hatch that keeps date
            // arithmetic ordinary without every numeric site learning about nanoseconds.
            // In doubles, because approximation is a double's whole job — an instant too
            // wide for exact millis still has a numeric reading. The nano
            // division stays integral first: the table says milliseconds truncate, and the
            // two numeric casts must agree wherever both answer.
            case Instant value -> value.epochSecond() * 1000.0 + value.nano() / 1_000_000;
        };
    }

    /**
     * The value as a whole number, or null when it is not one (design/17 §3.1).
     *
     * <p>A {@code Real} with a fraction is <b>absent, not truncated</b> — silent truncation is
     * how a total of 9.99 becomes 9. An author who wants a whole number says which one:
     * {@code round}, {@code floor} or {@code ceiling}.
     */
    default Long asInteger() {
        return switch (this) {
            case Integer value -> value.value();
            case Double value -> value.value() == Math.rint(value.value())
                               && !java.lang.Double.isInfinite(value.value())
                               && Math.abs(value.value()) < 0x1p63
                    ? (long) value.value()
                    : null;
            case Bool value -> value.value() ? 1L : 0L;
            case Bytes bytes ->
                    Numbers.whole(new String(bytes.utf8(), StandardCharsets.UTF_8).trim());
            // Absent when exact millis do not fit a long — the same refusal as a Double too
            // wide for the cast: unrepresentable is absent, never a throw (§2).
            case Instant value -> millis(value);
        };
    }

    /**
     * The value as a boolean, or null when it is not one (design/17 §3.1).
     *
     * <p>Text follows XPath's <i>constructor</i> rule — {@code true}/{@code 1} and
     * {@code false}/{@code 0}, anything else absent — not its effective-boolean-value rule
     * (non-emptiness, under which the string {@code "false"} would be true). The engine's
     * only consumer of this cast is an explicit {@code as: "boolean"} read, and a cast is
     * what {@code as} says; non-emptiness has no call site here at all.
     */
    default Boolean asBoolean() {
        return switch (this) {
            case Integer value -> value.value() != 0;
            case Double value -> value.value() != 0.0;
            case Bool value -> value.value();
            case Bytes bytes -> switch (new String(bytes.utf8(), StandardCharsets.UTF_8).trim()) {
                case "true", "1" -> true;
                case "false", "0" -> false;
                default -> null;
            };
            // A timestamp has no boolean reading; absent beats a meaningless true.
            case Instant ignored -> null;
        };
    }

    /**
     * Render a double as the value language does (design/17 §16.8): whole numbers without a
     * trailing {@code .0} — but only
     * while they fit a long, beyond which the cast saturates and would render the wrong number.
     */
    private static String format(final double value) {
        if (value == Math.rint(value) && !java.lang.Double.isInfinite(value)
                && Math.abs(value) < 0x1p63) {
            return Long.toString((long) value);
        }
        return java.lang.Double.toString(value);
    }

    /** Exact epoch milliseconds, truncating nanos, or null when a long cannot hold them. */
    private static Long millis(final Instant value) {
        try {
            return Math.addExact(Math.multiplyExact(value.epochSecond(), 1000L),
                    value.nano() / 1_000_000);
        } catch (final ArithmeticException tooWide) {
            return null;
        }
    }

    /**
     * ISO-8601, in the carried offset else {@code Z}, seconds always present, trailing zero
     * nanos trimmed — a deterministic rendering rather than {@code java.time}'s, which drops
     * {@code :00} seconds entirely.
     */
    private static String iso(final Instant value) {
        final ZoneOffset offset = value.offsetSeconds() == null
                ? ZoneOffset.UTC
                : ZoneOffset.ofTotalSeconds(value.offsetSeconds());
        final OffsetDateTime dateTime = OffsetDateTime.ofInstant(value.toJavaInstant(), offset);
        final StringBuilder out = new StringBuilder(35);
        // The sign is written separately: %04d would spend the field width on it and render
        // year -44 as "-044".
        final int year = dateTime.getYear();
        if (year < 0) {
            out.append('-');
        }
        out.append(String.format("%04d-%02d-%02dT%02d:%02d:%02d",
                Math.abs(year), dateTime.getMonthValue(), dateTime.getDayOfMonth(),
                dateTime.getHour(), dateTime.getMinute(), dateTime.getSecond()));
        if (value.nano() != 0) {
            String fraction = String.format(".%09d", value.nano());
            while (fraction.endsWith("0")) {
                fraction = fraction.substring(0, fraction.length() - 1);
            }
            out.append(fraction);
        }
        out.append(offset.getTotalSeconds() == 0 ? "Z" : offset.getId());
        return out.toString();
    }
}
