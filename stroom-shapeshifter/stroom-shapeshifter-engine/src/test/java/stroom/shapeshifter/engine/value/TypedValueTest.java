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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The casting table of design/17 §3.1, one assertion per cell — the single source for every
 * conversion in the engine, pinned so a cell cannot drift without a test saying which one.
 *
 * <p>Every cast is total and never throws; its failure value is absent, spelt null here
 * (§2's rule). The {@code Instant} row arrives with phase 4 and joins this test then.
 */
class TypedValueTest {

    // -----------------------------------------------------------------------------------
    // Bytes — the untyped row: text earns a type only by parsing as one
    // -----------------------------------------------------------------------------------

    @Test
    void bytesToString() {
        assertThat(TypedValue.of("héllo").asString()).isEqualTo("héllo");
    }

    @Test
    void bytesToNumber() {
        assertThat(TypedValue.of(" 42.5 ").asNumber()).isEqualTo(42.5);
        assertThat(TypedValue.of("n/a").asNumber()).isNull();
    }

    @Test
    void bytesToInteger() {
        assertThat(TypedValue.of(" 42 ").asInteger()).isEqualTo(42L);
        // Written with a point it is not integral text, whatever its value.
        assertThat(TypedValue.of("42.0").asInteger()).isNull();
        assertThat(TypedValue.of("n/a").asInteger()).isNull();
    }

    @Test
    void bytesToBooleanIsTheLexicalCastNotNonEmptiness() {
        assertThat(TypedValue.of("true").asBoolean()).isTrue();
        assertThat(TypedValue.of("1").asBoolean()).isTrue();
        // The cell the review corrected: under the non-emptiness rule this would be true.
        assertThat(TypedValue.of("false").asBoolean()).isFalse();
        assertThat(TypedValue.of("0").asBoolean()).isFalse();
        assertThat(TypedValue.of("yes").asBoolean()).isNull();
        assertThat(TypedValue.of("").asBoolean()).isNull();
    }

    // -----------------------------------------------------------------------------------
    // Int
    // -----------------------------------------------------------------------------------

    @Test
    void intCasts() {
        final TypedValue value = new TypedValue.Integer(-7);
        assertThat(value.asString()).isEqualTo("-7");
        assertThat(value.asNumber()).isEqualTo(-7.0);
        assertThat(value.asInteger()).isEqualTo(-7L);
        assertThat(value.asBoolean()).isTrue();
        assertThat(new TypedValue.Integer(0).asBoolean()).isFalse();
    }

    // -----------------------------------------------------------------------------------
    // Real
    // -----------------------------------------------------------------------------------

    @Test
    void realToStringDropsWholeNumberPoint() {
        // The Rust-style rendering the engine already had, kept by ruling (§16.8).
        assertThat(new TypedValue.Double(5.0).asString()).isEqualTo("5");
        assertThat(new TypedValue.Double(5.5).asString()).isEqualTo("5.5");
    }

    @Test
    void realToNumber() {
        assertThat(new TypedValue.Double(2.5).asNumber()).isEqualTo(2.5);
    }

    @Test
    void realToIntegerRefusesToTruncate() {
        assertThat(new TypedValue.Double(9.0).asInteger()).isEqualTo(9L);
        // Absent, not 9 — silent truncation is how 9.99 becomes 9 (§3.1).
        assertThat(new TypedValue.Double(9.99).asInteger()).isNull();
        assertThat(new TypedValue.Double(Double.POSITIVE_INFINITY).asInteger()).isNull();
        // Integral by rint but too wide for a long: the cast would saturate to the wrong number.
        assertThat(new TypedValue.Double(0x1p63).asInteger()).isNull();
    }

    @Test
    void realToBoolean() {
        assertThat(new TypedValue.Double(0.5).asBoolean()).isTrue();
        assertThat(new TypedValue.Double(0.0).asBoolean()).isFalse();
    }

    // -----------------------------------------------------------------------------------
    // Bool
    // -----------------------------------------------------------------------------------

    @Test
    void boolCasts() {
        final TypedValue value = new TypedValue.Bool(true);
        assertThat(value.asString()).isEqualTo("true");
        assertThat(value.asNumber()).isEqualTo(1.0);
        assertThat(value.asInteger()).isEqualTo(1L);
        assertThat(value.asBoolean()).isTrue();
        assertThat(new TypedValue.Bool(false).asNumber()).isEqualTo(0.0);
        assertThat(new TypedValue.Bool(false).asInteger()).isEqualTo(0L);
        assertThat(new TypedValue.Bool(false).asBoolean()).isFalse();
    }

    // -----------------------------------------------------------------------------------
    // Instant — the phase 4 row
    // -----------------------------------------------------------------------------------

    @Test
    void instantToStringIsIsoInTheCarriedOffset() {
        // 2026-08-25T10:00:00.5+01:00 — seconds always present, trailing zero nanos trimmed.
        final TypedValue value = new TypedValue.Instant(1787648400L, 500_000_000, 3600);
        assertThat(value.asString()).isEqualTo("2026-08-25T10:00:00.5+01:00");
        // No carried offset renders Z, and zero nanos render nothing.
        assertThat(new TypedValue.Instant(1787648400L, 0, null).asString())
                .isEqualTo("2026-08-25T09:00:00Z");
    }

    @Test
    void instantToNumberIsEpochMillisDocumentedLossy() {
        final TypedValue value = new TypedValue.Instant(10L, 123_456_789, null);
        assertThat(value.asNumber()).isEqualTo(10123.0);
        assertThat(value.asInteger()).isEqualTo(10123L);
    }

    @Test
    void instantToBooleanIsAbsent() {
        assertThat(new TypedValue.Instant(0L, 0, null).asBoolean()).isNull();
    }

    @Test
    void anInstantTooWideForExactMillisIsAbsentNotAThrow() {
        // Reachable from config and input: epoch-seconds parses any long. Every cast is
        // total and never throws (§2) — found by the phase 4 audit as an ArithmeticException
        // escaping asInteger mid-record.
        final TypedValue extreme = new TypedValue.Instant(Long.MAX_VALUE, 0, null);
        assertThat(extreme.asInteger()).isNull();
        // The double reading survives: approximation is what a double is for.
        assertThat(extreme.asNumber()).isEqualTo((double) Long.MAX_VALUE * 1000.0);
    }

    @Test
    void negativeYearsRenderTheirSignOutsideTheWidth() {
        // %04d would render year -44 as "-044" — the sign eating the field width.
        final long seconds = java.time.OffsetDateTime.parse("-0044-03-15T00:00:00Z").toEpochSecond();
        assertThat(new TypedValue.Instant(seconds, 0, null).asString())
                .isEqualTo("-0044-03-15T00:00:00Z");
    }

    // -----------------------------------------------------------------------------------
    // The boundary conventions the casts rest on
    // -----------------------------------------------------------------------------------

    @Test
    void emptyBytesAreAbsent() {
        assertThat(TypedValue.of("").isEmpty()).isTrue();
        assertThat(new TypedValue.Integer(0).isEmpty()).isFalse();
    }

    @Test
    void numbersRenderAsAscii() {
        // asBytes is the encoding-independent form: numbers are ASCII whatever the input was.
        assertThat(new TypedValue.Integer(42).asBytes()).isEqualTo("42".getBytes());
        assertThat(TypedValue.of("é").asBytes()).isEqualTo("é".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------------------
    // Design 25: a value knows its encoding, and nothing is transcoded until someone asks
    // -----------------------------------------------------------------------------------

    @Test
    void bytesKeepWhatTheyReadAndDecodeOnceWhenAsked() {
        final byte[] read = {(byte) 0xE9, (byte) 0x93};
        final TypedValue.Bytes value = (TypedValue.Bytes) TypedValue.of(read, Encoding.WINDOWS_1252);
        // Nothing transcoded at capture: the bytes are the array that was read.
        assertThat(value.asBytes()).isSameAs(read);
        assertThat(value.encoding()).isEqualTo(Encoding.WINDOWS_1252);
        // Text is asked for, decoded by the tag, and computed once.
        assertThat(value.asString()).isEqualTo("é“");
        assertThat(value.asUtf8()).isSameAs(value.asUtf8());
        assertThat(value.asUtf8())
                .isEqualTo("é“".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // A UTF-8-compatible feed has nothing to compute and nothing to carry: the form is the
        // array itself, and the value is the one-field variant (E43).
        final byte[] utf8 = "é".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (final Encoding compatible : List.of(Encoding.UTF_8, Encoding.AUTO, Encoding.ASCII)) {
            assertThat(TypedValue.of(utf8, compatible))
                    .as("%s", compatible)
                    .isInstanceOfSatisfying(TypedValue.Utf8Bytes.class, bytes -> {
                        assertThat(bytes.asUtf8()).isSameAs(utf8);
                        // The three collapse: the tag is the transcoding class, not the label
                        // the author wrote, and nothing in the engine reads it (E43).
                        assertThat(bytes.encoding()).isEqualTo(Encoding.UTF_8);
                    });
        }
        assertThat(TypedValue.utf8(utf8)).isInstanceOf(TypedValue.Utf8Bytes.class);
        assertThat(TypedValue.of(read, Encoding.WINDOWS_1252))
                .isInstanceOfSatisfying(TypedValue.EncodedBytes.class,
                        bytes -> assertThat(bytes.encoding()).isEqualTo(Encoding.WINDOWS_1252));
    }

    @Test
    void bytesAreEqualAcrossTheTwoVariants() {
        // Every pairing, both ways round, now that equality spans two classes (E43).
        final byte[] utf8 = "é".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final TypedValue oneWay = TypedValue.utf8(utf8);
        final TypedValue sameWay = TypedValue.of("é");
        final TypedValue otherWay = TypedValue.of(new byte[]{(byte) 0xE9}, Encoding.LATIN_1);
        final TypedValue alsoOther = TypedValue.of(new byte[]{(byte) 0xE9}, Encoding.WINDOWS_1252);

        assertThat(oneWay).isEqualTo(oneWay).isEqualTo(sameWay).isEqualTo(otherWay);
        assertThat(sameWay).isEqualTo(oneWay);
        assertThat(otherWay).isEqualTo(oneWay).isEqualTo(alsoOther);
        assertThat(alsoOther).isEqualTo(otherWay);
        assertThat(oneWay.hashCode()).isEqualTo(sameWay.hashCode()).isEqualTo(otherWay.hashCode());

        // Same variant, same tag, same bytes: the short circuit that decodes nothing.
        final TypedValue raw = TypedValue.of(new byte[]{(byte) 0x93}, Encoding.RAW);
        assertThat(raw).isEqualTo(TypedValue.of(new byte[]{(byte) 0x93}, Encoding.RAW));

        // Text equality does not cross kinds, as it did not before.
        assertThat(TypedValue.of("42")).isNotEqualTo(new TypedValue.Integer(42));
        assertThat(new TypedValue.Integer(42)).isNotEqualTo(TypedValue.of("42"));
    }

    @Test
    void bytesAreEqualWhenTheirTextIs() {
        final TypedValue latin = TypedValue.of(new byte[]{(byte) 0xE9}, Encoding.LATIN_1);
        final TypedValue utf8 = TypedValue.of("é");
        assertThat(latin).isEqualTo(utf8);
        assertThat(latin.hashCode()).isEqualTo(utf8.hashCode());
        assertThat(Comparisons.compare(latin, utf8)).isZero();
        // Under raw a byte is the code point of the same number, so 0x93 reads as U+0093.
        assertThat(TypedValue.of(new byte[]{(byte) 0x93}, Encoding.RAW)).isEqualTo(TypedValue.of("\u0093"));
        assertThat(TypedValue.of(new byte[]{(byte) 0x93}, Encoding.RAW))
                .isNotEqualTo(TypedValue.of(new byte[]{(byte) 0x93}, Encoding.WINDOWS_1252));
    }

    @Test
    void bytesInAnEncodingAreTranscodedOnlyWhereTheTagsDiffer() {
        final byte[] read = {(byte) 0xE9};
        final TypedValue latin = TypedValue.of(read, Encoding.LATIN_1);
        // Into the UTF-8 class: the memo. Into its own encoding: the array itself.
        assertThat(latin.bytes(Encoding.UTF_8))
                .isEqualTo("é".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(latin.bytes(Encoding.LATIN_1)).isSameAs(read);
        // Into another single-byte encoding: through text; a character it cannot express is '?'.
        assertThat(latin.bytes(Encoding.RAW)).containsExactly(0xE9);
        assertThat(TypedValue.of("é€").bytes(Encoding.LATIN_1)).containsExactly(0xE9, '?');
        // A number renders as text in the target's encoding, not as ASCII regardless.
        assertThat(new TypedValue.Integer(42).bytes(Encoding.UTF_16LE))
                .isEqualTo("42".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        assertThat(new TypedValue.Integer(42).bytes(Encoding.ASCII)).isEqualTo("42".getBytes());
    }
}
