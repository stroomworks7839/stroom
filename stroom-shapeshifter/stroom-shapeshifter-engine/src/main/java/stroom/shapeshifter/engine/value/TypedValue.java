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
 * <p>Bytes are the default and the common case: a capture is a slice of the input, in the
 * encoding it was read under, never transcoded until a consumer asks for text (design 25, D43).
 * Most captures never need that — they are written straight back out. The numeric variants
 * exist so that binary match steps, which have already done the work of decoding an integer,
 * do not have to render it to a string and parse it again at the other end. Formatting is
 * deferred to the output boundary in every case.
 *
 * <p><b>Each variant answers for itself</b> (E43): the conversions are declared here and
 * answered by the variant, rather than switched over in one place, which is the shape the model
 * took at D47 and the shape Stroom's own {@code Val} family has. Where an answer is the same
 * for every variant it is a {@code default} here, and where it is the same for both byte
 * variants it is a {@code default} on {@link Bytes} — sharing an implementation is not the same
 * as enumerating the variants, and no switch over the hierarchy remains.
 *
 * <p>That shape is what lets {@link Bytes} split by encoding: {@link Utf8Bytes} needs neither a
 * tag nor a memo, because its UTF-8 form is its own array and its encoding is its class, and
 * that is every fixture in the corpus and very nearly every real feed.
 */
public sealed interface TypedValue {

    // -----------------------------------------------------------------------------------
    // What a value can be asked
    // -----------------------------------------------------------------------------------

    /** True if this value has no content. Empty captures are treated as absent by references. */
    boolean isEmpty();

    /**
     * The value as bytes: captured bytes as they are, in their own encoding.
     *
     * <p>Numbers render as ASCII, which is safe in every encoding the engine supports.
     */
    byte[] asBytes();

    /** The value as text, decoding bytes by their encoding. */
    String asString();

    /** The value as a number, parsing bytes if that is what it holds. */
    java.lang.Double asNumber();

    /**
     * The value as a whole number, or null when it is not one (design/17 §3.1).
     *
     * <p>A {@code Double} with a fraction is <b>absent, not truncated</b> — silent truncation is
     * how a total of 9.99 becomes 9. An author who wants a whole number says which one:
     * {@code round}, {@code floor} or {@code ceiling}.
     */
    Long asInteger();

    /**
     * The value as a boolean, or null when it is not one (design/17 §3.1).
     *
     * <p>Text follows XPath's <i>constructor</i> rule — {@code true}/{@code 1} and
     * {@code false}/{@code 0}, anything else absent — not its effective-boolean-value rule
     * (non-emptiness, under which the string {@code "false"} would be true). The engine's
     * only consumer of this cast is an explicit {@code as: "boolean"} read, and a cast is
     * what {@code as} says; non-emptiness has no call site here at all.
     */
    Boolean asBoolean();

    /**
     * The value as UTF-8 bytes: captured bytes decoded by their encoding, once; the rest ASCII,
     * which is already UTF-8.
     */
    default byte[] asUtf8() {
        return asBytes();
    }

    /**
     * The value as bytes in an encoding — what a sink that declares one receives (design 25 §4).
     * The UTF-8-compatible class is one encoding for this purpose, and {@code isUtf8Compatible}
     * tests UTF-8 first, which is the compare every write to a UTF-8 sink pays. Anything else is
     * transcoded through text, where a character the target cannot express becomes {@code ?}
     * ({@link Encoding#encode}).
     */
    default byte[] bytes(final Encoding target) {
        return target.isUtf8Compatible() ? asUtf8() : target.encode(asString());
    }

    // -----------------------------------------------------------------------------------
    // Bytes: what a capture holds
    // -----------------------------------------------------------------------------------

    /**
     * Bytes as they were read, and the encoding they are in (design 25, D43). Nothing is
     * transcoded when a value is captured, stored, bound or passed; a consumer that needs text
     * asks for {@link #asUtf8()}. Two values are equal when their text is.
     *
     * <p>Two variants, because the common one needs less (E43): a UTF-8-compatible feed's bytes
     * are already their own UTF-8 form, so {@link Utf8Bytes} holds the array alone.
     */
    sealed interface Bytes extends TypedValue permits Utf8Bytes, EncodedBytes {

        /** The bytes as read, in {@link #encoding()}. */
        byte[] value();

        /**
         * The transcoding class these bytes are in, not the label the author wrote: the
         * UTF-8-compatible three collapse to {@link Encoding#UTF_8} on {@link Utf8Bytes}
         * (design 25 §2, E43). Nothing in the engine reads it — the tag's work is done by
         * {@link #asUtf8()} and {@link #bytes(Encoding)} — so it is here for an
         * {@code Instrument} and for a reader of a heap dump.
         */
        Encoding encoding();

        @Override
        default boolean isEmpty() {
            return value().length == 0;
        }

        @Override
        default byte[] asBytes() {
            return value();
        }

        @Override
        default String asString() {
            return new String(asUtf8(), StandardCharsets.UTF_8);
        }

        @Override
        default java.lang.Double asNumber() {
            // E26: the parse that answers "no" without throwing (Numbers).
            return Numbers.real(asString().trim());
        }

        @Override
        default Long asInteger() {
            return Numbers.whole(asString().trim());
        }

        @Override
        default Boolean asBoolean() {
            return lexical(asString());
        }
    }

    /**
     * Bytes already in the engine's text form: a UTF-8, ASCII or auto feed's capture, a literal,
     * a composite, a function's result. One field, because its UTF-8 form is itself and its
     * encoding is its class — sixteen bytes against the tagged value's twenty-four, which is
     * what E43 is for.
     *
     * <p>The three UTF-8-compatible encodings collapse to one here, as design 25 §2 says they do
     * for transcoding: a byte above 0x7F on an ASCII-declared feed passes through unchanged
     * either way. What is lost is which of the three the author wrote, and nothing reads it.
     *
     * <p>Having no mutable field, an instance is safe to share across runs, which the values
     * held by compiled literals and {@code Steps.NOTHING} are.
     */
    final class Utf8Bytes implements Bytes {

        private final byte[] value;

        private Utf8Bytes(final byte[] value) {
            this.value = value;
        }

        @Override
        public byte[] value() {
            return value;
        }

        @Override
        public Encoding encoding() {
            return Encoding.UTF_8;
        }

        /** The array itself: one hop on the accessor every read and every write goes through. */
        @Override
        public byte[] asUtf8() {
            return value;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Bytes bytes && Arrays.equals(value, bytes.asUtf8());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return asString();
        }
    }

    /**
     * Bytes in an encoding that is not the engine's text form: a Windows-1252 or Latin-1 feed's
     * capture, a {@code raw} payload. Carries the encoding, and the UTF-8 form once a consumer
     * asks for it — the run is single-threaded, so the memo needs no volatile, and only a value
     * of this variant can be mid-decode.
     *
     * <p>The factory never makes one with a UTF-8-compatible encoding, which is what lets
     * {@link Utf8Bytes} answer for that class alone.
     */
    final class EncodedBytes implements Bytes {

        private final byte[] value;
        private final Encoding encoding;
        private byte[] utf8;

        private EncodedBytes(final byte[] value, final Encoding encoding) {
            this.value = value;
            this.encoding = encoding;
        }

        @Override
        public byte[] value() {
            return value;
        }

        @Override
        public Encoding encoding() {
            return encoding;
        }

        @Override
        public byte[] asUtf8() {
            if (utf8 == null) {
                utf8 = encoding.decode(value).getBytes(StandardCharsets.UTF_8);
            }
            return utf8;
        }

        /** As the interface, plus the one case it cannot know: bytes already in the target. */
        @Override
        public byte[] bytes(final Encoding target) {
            if (target.isUtf8Compatible()) {
                return asUtf8();
            }
            return target == encoding ? value : target.encode(asString());
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Bytes bytes)) {
                return false;
            }
            // The same bytes in the same encoding decode the same way; no need to find out.
            if (bytes instanceof EncodedBytes encoded
                && encoding == encoded.encoding
                && Arrays.equals(value, encoded.value)) {
                return true;
            }
            return Arrays.equals(asUtf8(), bytes.asUtf8());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(asUtf8());
        }

        @Override
        public String toString() {
            return asString();
        }
    }

    // -----------------------------------------------------------------------------------
    // The kinds a step or a cast produces
    // -----------------------------------------------------------------------------------

    /** A whole number, as XSLT 2.0's {@code xs:integer}; held in a {@code long} (D49). */
    record Integer(long value) implements TypedValue {

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public byte[] asBytes() {
            return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public String asString() {
            return Long.toString(value);
        }

        @Override
        public java.lang.Double asNumber() {
            return (double) value;
        }

        @Override
        public Long asInteger() {
            return value;
        }

        @Override
        public Boolean asBoolean() {
            return value != 0;
        }
    }

    /** A number with a fractional part, as XSLT 2.0's {@code xs:double} (D49). */
    record Double(double value) implements TypedValue {

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public byte[] asBytes() {
            return format(value).getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public String asString() {
            return format(value);
        }

        @Override
        public java.lang.Double asNumber() {
            return value;
        }

        @Override
        public Long asInteger() {
            return value == Math.rint(value)
                   && !java.lang.Double.isInfinite(value)
                   && Math.abs(value) < 0x1p63
                    ? (long) value
                    : null;
        }

        @Override
        public Boolean asBoolean() {
            return value != 0.0;
        }
    }

    /** True or false. */
    record Bool(boolean value) implements TypedValue {

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public byte[] asBytes() {
            return Boolean.toString(value).getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public String asString() {
            return Boolean.toString(value);
        }

        @Override
        public java.lang.Double asNumber() {
            return value ? 1.0 : 0.0;
        }

        @Override
        public Long asInteger() {
            return value ? 1L : 0L;
        }

        @Override
        public Boolean asBoolean() {
            return value;
        }
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

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public byte[] asBytes() {
            return iso(this).getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public String asString() {
            return iso(this);
        }

        @Override
        public java.lang.Double asNumber() {
            // Epoch milliseconds, documented lossy: the escape hatch that keeps date
            // arithmetic ordinary without every numeric site learning about nanoseconds.
            // In doubles, because approximation is a double's whole job — an instant too
            // wide for exact millis still has a numeric reading. The nano division stays
            // integral first: the table says milliseconds truncate, and the two numeric
            // casts must agree wherever both answer.
            return epochSecond * 1000.0 + nano / 1_000_000;
        }

        @Override
        public Long asInteger() {
            // Absent when exact millis do not fit a long — the same refusal as a Double too
            // wide for the cast: unrepresentable is absent, never a throw (§2).
            return millis(this);
        }

        /** A timestamp has no boolean reading; absent beats a meaningless true. */
        @Override
        public Boolean asBoolean() {
            return null;
        }
    }

    // -----------------------------------------------------------------------------------
    // Making one
    // -----------------------------------------------------------------------------------

    /**
     * Wrap bytes, saying what they are in: {@link Utf8Bytes} when that is the engine's text
     * form already, else {@link EncodedBytes}. The only place either is constructed, which is
     * what keeps a UTF-8-compatible tag off {@code EncodedBytes}.
     */
    static TypedValue of(final byte[] value, final Encoding encoding) {
        return encoding.isUtf8Compatible()
                ? new Utf8Bytes(value)
                : new EncodedBytes(value, encoding);
    }

    /** Wrap bytes that are UTF-8 already: a literal, a composite, a function's result. */
    static TypedValue utf8(final byte[] value) {
        return new Utf8Bytes(value);
    }

    /** Wrap text, as UTF-8 bytes. */
    static TypedValue of(final String value) {
        return new Utf8Bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------------------
    // Shared renderings
    // -----------------------------------------------------------------------------------

    /** XPath's constructor rule for text to boolean, shared by both byte variants. */
    private static Boolean lexical(final String text) {
        return switch (text.trim()) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> null;
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
