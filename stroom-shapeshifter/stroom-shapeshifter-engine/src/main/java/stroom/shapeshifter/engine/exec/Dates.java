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

import stroom.shapeshifter.engine.config.ConfigException;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.Locale;

/**
 * The date pair (design/17 §9): {@code parse-date} reads text into an
 * {@link TypedValue.Instant} and {@code format-date} renders one back — the composed shape
 * the 2026-08-21 ruling chose over {@code stroom:format-date}'s conflated signature.
 *
 * <p>Everything expensive happens once, at compile time: the pattern becomes a
 * {@link Parser} or {@link Formatter} holding its {@link DateTimeFormatter}, and the three
 * reserved names — {@code iso}, {@code epoch-millis}, {@code epoch-seconds} — bypass the
 * formatter machinery entirely. A pattern with no year is refused at compile time unless the
 * instruction carries a {@code reference}, whose nearest-year rule is Stroom's own with the
 * input made explicit: data, not a clock.
 */
public final class Dates {

    private Dates() {
    }

    /** The reserved pattern names, and the ordinary compiled kind. */
    public enum Kind {
        PATTERN, ISO, EPOCH_MILLIS, EPOCH_SECONDS
    }

    /** A compiled {@code parse-date}: the work a pattern needs done exactly once. */
    public record Parser(Kind kind, DateTimeFormatter formatter, ZoneId zone, boolean hasYear) {

    }

    /** A compiled {@code format-date}. A null zone means "the instant's own offset, else UTC". */
    public record Formatter(Kind kind, DateTimeFormatter formatter, ZoneId zone) {

    }

    // -----------------------------------------------------------------------------------
    // Compile time
    // -----------------------------------------------------------------------------------

    /**
     * Compile a parse pattern.
     *
     * @param hasReference whether the instruction supplies a reference date — required when
     *                     the pattern yields no year, refused as the alternative to a silent
     *                     1970
     * @param what         the instruction name, for the error
     */
    public static Parser compileParser(final String pattern,
                                       final String timezone,
                                       final boolean hasReference,
                                       final String what) {
        final ZoneId zone = zone(timezone);
        final Kind kind = reserved(pattern);
        if (kind != Kind.PATTERN) {
            return new Parser(kind, null, zone, true);
        }
        final DateTimeFormatter formatter = formatter(pattern, what);
        final boolean hasYear = patternHasYear(pattern);
        if (!hasYear && !hasReference) {
            throw new ConfigException("A " + what + " pattern with no year needs a reference"
                                      + " date to choose one: '" + pattern + "'");
        }
        return new Parser(kind, formatter, zone, hasYear);
    }

    /** Compile a format pattern. A null timezone defers to the value's carried offset. */
    public static Formatter compileFormatter(final String pattern,
                                             final String timezone,
                                             final String what) {
        final ZoneId zone = timezone == null ? null : zone(timezone);
        final Kind kind = reserved(pattern);
        return kind != Kind.PATTERN
                ? new Formatter(kind, null, zone)
                : new Formatter(kind, formatter(pattern, what), zone);
    }

    private static Kind reserved(final String pattern) {
        return switch (pattern) {
            case "iso" -> Kind.ISO;
            case "epoch-millis" -> Kind.EPOCH_MILLIS;
            case "epoch-seconds" -> Kind.EPOCH_SECONDS;
            default -> Kind.PATTERN;
        };
    }

    private static DateTimeFormatter formatter(final String pattern, final String what) {
        try {
            return DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
        } catch (final IllegalArgumentException e) {
            throw new ConfigException("A " + what + " pattern will not compile: '" + pattern
                                      + "' (" + e.getMessage() + ")");
        }
    }

    private static ZoneId zone(final String timezone) {
        if (timezone == null) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timezone);
        } catch (final DateTimeException e) {
            throw new ConfigException("Unknown timezone: " + timezone);
        }
    }

    /** Whether a pattern's unquoted letters include a year field ({@code u}, {@code y}, {@code Y}). */
    static boolean patternHasYear(final String pattern) {
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && (c == 'u' || c == 'y' || c == 'Y')) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------------------
    // Run time
    // -----------------------------------------------------------------------------------

    /**
     * Parse one value. Absent when the text does not match — one bad timestamp in a million
     * records is a missing field, not a failure — and absent when the pattern is yearless
     * and the reference did not resolve: no year is better than a guessed one.
     */
    public static TypedValue parse(final Parser parser,
                                   final String input,
                                   final TypedValue.Instant reference) {
        final String trimmed = input.trim();
        try {
            return switch (parser.kind()) {
                case ISO -> parseIso(trimmed);
                case EPOCH_MILLIS -> {
                    // Non-throwing (E26): a malformed epoch is a missing timestamp, and one
                    // per record is the ordinary case in the data this engine reads.
                    final Long millis = Numbers.whole(trimmed);
                    yield millis == null
                            ? null
                            : new TypedValue.Instant(Math.floorDiv(millis, 1000L),
                                    (int) Math.floorMod(millis, 1000L) * 1_000_000, null);
                }
                case EPOCH_SECONDS -> {
                    final Long seconds = Numbers.whole(trimmed);
                    yield seconds == null ? null : new TypedValue.Instant(seconds, 0, null);
                }
                case PATTERN -> parsePattern(parser, trimmed, reference);
            };
        } catch (final NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    /** The ISO reading — an offset or {@code Z} is required; anything less goes through a pattern. */
    private static TypedValue parseIso(final String input) {
        try {
            final OffsetDateTime parsed = OffsetDateTime.parse(input);
            return new TypedValue.Instant(parsed.toEpochSecond(), parsed.getNano(),
                    parsed.getOffset().getTotalSeconds());
        } catch (final DateTimeParseException e) {
            return null;
        }
    }

    private static TypedValue parsePattern(final Parser parser,
                                           final String input,
                                           final TypedValue.Instant reference) {
        final TemporalAccessor parsed;
        try {
            parsed = parser.formatter().parse(input);
        } catch (final DateTimeParseException e) {
            return null;
        }
        final ZoneOffset parsedOffset = parsed.query(TemporalQueries.offset());

        final int month = parsed.isSupported(ChronoField.MONTH_OF_YEAR)
                ? parsed.get(ChronoField.MONTH_OF_YEAR) : 1;
        final int day = parsed.isSupported(ChronoField.DAY_OF_MONTH)
                ? parsed.get(ChronoField.DAY_OF_MONTH) : 1;
        final int hour = parsed.isSupported(ChronoField.HOUR_OF_DAY)
                ? parsed.get(ChronoField.HOUR_OF_DAY) : 0;
        final int minute = parsed.isSupported(ChronoField.MINUTE_OF_HOUR)
                ? parsed.get(ChronoField.MINUTE_OF_HOUR) : 0;
        final int second = parsed.isSupported(ChronoField.SECOND_OF_MINUTE)
                ? parsed.get(ChronoField.SECOND_OF_MINUTE) : 0;
        final int nano = parsed.isSupported(ChronoField.NANO_OF_SECOND)
                ? parsed.get(ChronoField.NANO_OF_SECOND) : 0;

        if (parser.hasYear()) {
            final int year = parsed.get(ChronoField.YEAR);
            return instant(LocalDateTime.of(year, month, day, hour, minute, second, nano),
                    parsedOffset, parser.zone());
        }
        if (reference == null) {
            // The compile-time gate requires a reference on a yearless pattern; it resolving
            // to nothing at run time earns absence, not a guess.
            return null;
        }
        return nearestYear(month, day, hour, minute, second, nano,
                parsedOffset, parser.zone(), reference);
    }

    /**
     * The year that puts the result nearest the reference — Stroom's rule for the yearless
     * syslog timestamp, with the reference made explicit. A candidate that does not exist
     * (February 29th in a common year) is skipped.
     */
    private static TypedValue nearestYear(final int month, final int day,
                                          final int hour, final int minute, final int second,
                                          final int nano,
                                          final ZoneOffset parsedOffset, final ZoneId zone,
                                          final TypedValue.Instant reference) {
        final java.time.Instant anchor = reference.toJavaInstant();
        final int anchorYear = OffsetDateTime.ofInstant(anchor, ZoneOffset.UTC).getYear();
        TypedValue best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int year = anchorYear - 1; year <= anchorYear + 1; year++) {
            final LocalDateTime candidate;
            try {
                candidate = LocalDateTime.of(year, month, day, hour, minute, second, nano);
            } catch (final DateTimeException invalid) {
                continue;
            }
            final TypedValue.Instant value =
                    (TypedValue.Instant) instant(candidate, parsedOffset, zone);
            final long distance = Math.abs(value.toJavaInstant().getEpochSecond()
                                           - anchor.getEpochSecond());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = value;
            }
        }
        return best;
    }

    /** A local date-time placed on the timeline: the parsed offset wins and is carried. */
    private static TypedValue instant(final LocalDateTime local,
                                      final ZoneOffset parsedOffset,
                                      final ZoneId zone) {
        if (parsedOffset != null) {
            final OffsetDateTime placed = local.atOffset(parsedOffset);
            return new TypedValue.Instant(placed.toEpochSecond(), placed.getNano(),
                    parsedOffset.getTotalSeconds());
        }
        // The instruction's zone supplies the placement; no offset was parsed, none is
        // claimed (design/17 §3).
        final ZonedDateTime placed = local.atZone(zone);
        return new TypedValue.Instant(placed.toEpochSecond(), placed.getNano(), null);
    }

    /**
     * Format one value. The zone precedence is the ruling's: the instruction's zone if
     * given, else the instant's own carried offset, else UTC — so a value round-trips
     * through its original offset unless the author says otherwise.
     */
    public static TypedValue format(final Formatter formatter, final TypedValue.Instant value) {
        return switch (formatter.kind()) {
            case ISO -> TypedValue.of(new TypedValue.Instant(
                    value.epochSecond(), value.nano(), renderOffset(formatter, value)).asString());
            case EPOCH_MILLIS -> {
                final Long millis = value.asInteger();
                // An instant too wide for exact millis has no epoch-millis rendering: absent.
                yield millis == null ? null : TypedValue.of(Long.toString(millis));
            }
            case EPOCH_SECONDS -> TypedValue.of(Long.toString(value.epochSecond()));
            case PATTERN -> {
                final ZoneId zone = formatter.zone() != null
                        ? formatter.zone()
                        : value.offsetSeconds() != null
                                ? ZoneOffset.ofTotalSeconds(value.offsetSeconds())
                                : ZoneOffset.UTC;
                yield TypedValue.of(formatter.formatter().format(
                        ZonedDateTime.ofInstant(value.toJavaInstant(), zone)));
            }
        };
    }

    /** The offset the ISO rendering should claim, under the same precedence. */
    private static Integer renderOffset(final Formatter formatter, final TypedValue.Instant value) {
        if (formatter.zone() != null) {
            return formatter.zone().getRules().getOffset(value.toJavaInstant()).getTotalSeconds();
        }
        return value.offsetSeconds() == null ? 0 : value.offsetSeconds();
    }
}
