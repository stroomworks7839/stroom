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

import java.nio.charset.StandardCharsets;

/**
 * A value captured during matching.
 *
 * <p>Bytes are the default and the common case: a capture is a slice of the input, and turning
 * it into text costs a decode that most captures never need — they are written straight back
 * out. The numeric variants exist so that binary match steps, which have already done the work
 * of decoding an integer, do not have to render it to a string and parse it again at the other
 * end. Formatting is deferred to the output boundary in every case.
 */
public sealed interface TypedValue {

    /** Raw bytes from the input. */
    record Bytes(byte[] value) implements TypedValue {

        @Override
        public boolean equals(final Object other) {
            return other instanceof Bytes bytes && java.util.Arrays.equals(value, bytes.value);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return new String(value, StandardCharsets.UTF_8);
        }
    }

    /** A whole number. */
    record Int(long value) implements TypedValue {

    }

    /** A number with a fractional part. Named to leave {@code java.lang.Float} unshadowed. */
    record Real(double value) implements TypedValue {

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
    record Instant(long epochSecond, int nano, Integer offsetSeconds) implements TypedValue {

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

    /** Wrap bytes. */
    static TypedValue of(final byte[] value) {
        return new Bytes(value);
    }

    /** Wrap text, as UTF-8 bytes — the engine's internal form. */
    static TypedValue of(final String value) {
        return new Bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    /** True if this value has no content. Empty captures are treated as absent by references. */
    default boolean isEmpty() {
        return this instanceof Bytes bytes && bytes.value().length == 0;
    }

    /**
     * The value as bytes, ready to write.
     *
     * <p>Numbers render as ASCII, which is safe in every encoding the engine supports, so this
     * is also the encoding-independent form.
     */
    default byte[] asBytes() {
        return switch (this) {
            case Bytes bytes -> bytes.value();
            case Int value -> Long.toString(value.value()).getBytes(StandardCharsets.US_ASCII);
            case Real value -> format(value.value()).getBytes(StandardCharsets.US_ASCII);
            case Bool value -> Boolean.toString(value.value()).getBytes(StandardCharsets.US_ASCII);
            case Instant value -> iso(value).getBytes(StandardCharsets.US_ASCII);
        };
    }

    /** The value as text, decoding bytes as UTF-8. */
    default String asString() {
        return switch (this) {
            case Bytes bytes -> new String(bytes.value(), StandardCharsets.UTF_8);
            case Int value -> Long.toString(value.value());
            case Real value -> format(value.value());
            case Bool value -> Boolean.toString(value.value());
            case Instant value -> iso(value);
        };
    }

    /** The value as a number, parsing bytes if that is what it holds. */
    default Double asNumber() {
        return switch (this) {
            case Int value -> (double) value.value();
            case Real value -> value.value();
            case Bool value -> value.value() ? 1.0 : 0.0;
            case Bytes bytes -> {
                // E26: the parse that answers "no" without throwing (Numbers).
                yield Numbers.real(new String(bytes.value(), StandardCharsets.UTF_8).trim());
            }
            // Epoch milliseconds, documented lossy: the escape hatch that keeps date
            // arithmetic ordinary without every numeric site learning about nanoseconds.
            // In doubles, because approximation is a double's whole job — an instant too
            // wide for exact millis still has a numeric reading (phase 4 audit). The nano
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
            case Int value -> value.value();
            case Real value -> value.value() == Math.rint(value.value())
                               && !Double.isInfinite(value.value())
                               && Math.abs(value.value()) < 0x1p63
                    ? (long) value.value()
                    : null;
            case Bool value -> value.value() ? 1L : 0L;
            case Bytes bytes -> Numbers.whole(new String(bytes.value(), StandardCharsets.UTF_8).trim());
            // Absent when exact millis do not fit a long — the same refusal as a Real too
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
            case Int value -> value.value() != 0;
            case Real value -> value.value() != 0.0;
            case Bool value -> value.value();
            case Bytes bytes -> switch (new String(bytes.value(), StandardCharsets.UTF_8).trim()) {
                case "true", "1" -> true;
                case "false", "0" -> false;
                default -> null;
            };
            // A timestamp has no boolean reading; absent beats a meaningless true.
            case Instant ignored -> null;
        };
    }

    /**
     * Render a double the way Rust does: whole numbers without a trailing {@code .0} — but only
     * while they fit a long, beyond which the cast saturates and would render the wrong number.
     */
    private static String format(final double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 0x1p63) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
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
        final java.time.ZoneOffset offset = value.offsetSeconds() == null
                ? java.time.ZoneOffset.UTC
                : java.time.ZoneOffset.ofTotalSeconds(value.offsetSeconds());
        final java.time.OffsetDateTime dateTime =
                java.time.OffsetDateTime.ofInstant(value.toJavaInstant(), offset);
        final StringBuilder out = new StringBuilder(35);
        // The sign is written separately: %04d would spend the field width on it and render
        // year -44 as "-044" (phase 4 audit).
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
