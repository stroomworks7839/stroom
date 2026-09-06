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

import stroom.shapeshifter.engine.config.ConfigException;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The date pair (design/17 §9), tested directly — the phase plan's list: the nanosecond
 * round trip, offset inertness, the format-date zone precedence, the nearest-year rule at
 * both year boundaries, and the yearless refusal.
 */
class DatesTest {

    private static TypedValue.Instant parse(final String pattern,
                                            final String timezone,
                                            final String input) {
        final Dates.Parser parser = Dates.compileParser(pattern, timezone, false, "parse-date");
        return (TypedValue.Instant) Dates.parse(parser, input, null);
    }

    private static TypedValue.Instant parseWithReference(final String pattern,
                                                         final String timezone,
                                                         final String input,
                                                         final TypedValue.Instant reference) {
        final Dates.Parser parser = Dates.compileParser(pattern, timezone, true, "parse-date");
        return (TypedValue.Instant) Dates.parse(parser, input, reference);
    }

    // -----------------------------------------------------------------------------------
    // Precision and offsets
    // -----------------------------------------------------------------------------------

    @Test
    void nanosecondsSurviveTheRoundTrip() {
        final TypedValue.Instant parsed = parse("iso", null, "2026-08-25T09:00:00.123456789Z");
        assertThat(parsed.nano()).isEqualTo(123_456_789);
        // parse → format never casts, so nothing truncates.
        final Dates.Formatter iso = Dates.compileFormatter("iso", null, "format-date");
        assertThat(Dates.format(iso, parsed).asString())
                .isEqualTo("2026-08-25T09:00:00.123456789Z");
    }

    @Test
    void twoOffsetsOfOneMomentAreEqualAndSubtractToZero() {
        final TypedValue.Instant paris = parse("iso", null, "2026-08-25T10:00:00+01:00");
        final TypedValue.Instant utc = parse("iso", null, "2026-08-25T09:00:00Z");
        assertThat(Comparisons.compare(paris, utc)).isZero();
        // Date arithmetic through the millis cast: a duration is a subtraction away.
        assertThat(Transforms.subtract(List.of(paris, utc)))
                .isEqualTo(new TypedValue.Int(0));
    }

    @Test
    void parsedOffsetWinsAndIsCarried() {
        final TypedValue.Instant parsed =
                parse("uuuu-MM-dd HH:mm:ss XXX", "Asia/Tokyo", "2026-08-25 10:00:00 +01:00");
        assertThat(parsed.offsetSeconds()).isEqualTo(3600);
        // The instruction's zone supplied nothing; the parsed offset placed the value.
        assertThat(parsed.epochSecond()).isEqualTo(parse("iso", null, "2026-08-25T09:00:00Z").epochSecond());
    }

    @Test
    void zoneSuppliedPlacementClaimsNoOffset() {
        final TypedValue.Instant parsed =
                parse("uuuu-MM-dd HH:mm:ss", "Europe/Paris", "2026-08-25 10:00:00");
        // Placed in Paris summer time (+02:00), but no offset was parsed, so none is claimed.
        assertThat(parsed.offsetSeconds()).isNull();
        assertThat(parsed.epochSecond()).isEqualTo(parse("iso", null, "2026-08-25T08:00:00Z").epochSecond());
    }

    // -----------------------------------------------------------------------------------
    // format-date zone precedence: instruction zone > carried offset > UTC
    // -----------------------------------------------------------------------------------

    @Test
    void formatDatePrecedenceIsInstructionThenCarriedThenUtc() {
        final TypedValue.Instant carried = parse("iso", null, "2026-08-25T10:00:00+01:00");
        final TypedValue.Instant bare = parse("epoch-seconds", null,
                Long.toString(carried.epochSecond()));

        final Dates.Formatter instruction =
                Dates.compileFormatter("uuuu-MM-dd'T'HH:mm:ssXXX", "Asia/Tokyo", "format-date");
        final Dates.Formatter deferring =
                Dates.compileFormatter("uuuu-MM-dd'T'HH:mm:ssXXX", null, "format-date");

        // The instruction's zone beats the carried offset.
        assertThat(Dates.format(instruction, carried).asString())
                .isEqualTo("2026-08-25T18:00:00+09:00");
        // No instruction zone: the carried offset renders — the round trip keeps its spelling.
        assertThat(Dates.format(deferring, carried).asString())
                .isEqualTo("2026-08-25T10:00:00+01:00");
        // No instruction zone and nothing carried: UTC.
        assertThat(Dates.format(deferring, bare).asString())
                .isEqualTo("2026-08-25T09:00:00Z");
    }

    @Test
    void reservedNamesBypassTheFormatter() {
        final TypedValue.Instant value = parse("iso", null, "2026-08-25T09:00:00.5Z");
        assertThat(Dates.format(Dates.compileFormatter("epoch-millis", null, "format-date"), value)
                .asString()).isEqualTo("1787648400500");
        assertThat(Dates.format(Dates.compileFormatter("epoch-seconds", null, "format-date"), value)
                .asString()).isEqualTo("1787648400");
    }

    // -----------------------------------------------------------------------------------
    // The year that is not there
    // -----------------------------------------------------------------------------------

    @Test
    void nearestYearAtTheDecemberBoundary() {
        // A syslog line stamped Dec 31, read with a reference in early January: the nearest
        // year is the one before the reference's.
        final TypedValue.Instant reference = parse("iso", null, "2027-01-02T00:00:00Z");
        final TypedValue.Instant parsed = parseWithReference(
                "MMM d HH:mm:ss", "UTC", "Dec 31 23:59:00", reference);
        assertThat(parsed.asString()).isEqualTo("2026-12-31T23:59:00Z");
    }

    @Test
    void nearestYearAtTheJanuaryBoundary() {
        // The mirror: a Jan 1 stamp read with a late-December reference lands a year ahead.
        final TypedValue.Instant reference = parse("iso", null, "2026-12-30T00:00:00Z");
        final TypedValue.Instant parsed = parseWithReference(
                "MMM d HH:mm:ss", "UTC", "Jan 1 00:05:00", reference);
        assertThat(parsed.asString()).isEqualTo("2027-01-01T00:05:00Z");
    }

    @Test
    void leapDayCandidatesThatDoNotExistAreSkipped() {
        // Feb 29 with a reference in early 2029: 2028 is the only nearby leap year.
        final TypedValue.Instant reference = parse("iso", null, "2029-01-15T00:00:00Z");
        final TypedValue.Instant parsed = parseWithReference(
                "MMM d HH:mm:ss", "UTC", "Feb 29 12:00:00", reference);
        assertThat(parsed.asString()).isEqualTo("2028-02-29T12:00:00Z");
    }

    @Test
    void yearlessPatternWithoutAReferenceIsRefusedByName() {
        assertThatThrownBy(() -> Dates.compileParser("MMM d HH:mm:ss", "UTC", false, "parse-date"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("parse-date")
                .hasMessageContaining("reference");
    }

    @Test
    void anAbsentReferenceAtRunTimeMeansAbsenceNotAGuess() {
        assertThat(parseWithReference("MMM d HH:mm:ss", "UTC", "Aug 25 11:06:41", null)).isNull();
    }

    // -----------------------------------------------------------------------------------
    // The messy-input rule
    // -----------------------------------------------------------------------------------

    @Test
    void unparseableInputIsAbsentNotAFailure() {
        assertThat(parse("iso", null, "not a date")).isNull();
        assertThat(parse("epoch-millis", null, "soon")).isNull();
        final Dates.Parser pattern = Dates.compileParser("uuuu-MM-dd", "UTC", false, "parse-date");
        assertThat(Dates.parse(pattern, "25/08/2026", null)).isNull();
    }

    @Test
    void badPatternOrZoneIsRefusedAtCompileTime() {
        assertThatThrownBy(() -> Dates.compileParser("uuuu-MM-QQQQQQ", "UTC", false, "parse-date"))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> Dates.compileParser("iso", "Mars/Olympus", false, "parse-date"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Mars/Olympus");
    }
}
