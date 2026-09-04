/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.util.date;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Parses a date by a pattern, filling in what the pattern does not say from a reference time:
 * a pattern with no year takes the reference year, and a parsed date that lands after the
 * reference is pulled back a year, a month or a week, so that "01/04" seen in March is last
 * April. Week-based patterns (week of year, day of week) default the same way through
 * {@link WeekFields}.
 *
 * <p>This is the parser behind the {@code format-date} and {@code parse-dateTime} XSLT
 * functions, extracted so that anything else Stroom parses dates with — the Shapeshifter
 * variants of those functions — runs the one implementation rather than a copy. Their tests
 * are its tests.
 */
public final class ReferenceDateParser {

    private static final Locale LOCALE = Locale.ENGLISH;
    private static final WeekFields WEEK_FIELDS = WeekFields.of(LOCALE);
    private static final String DAY_OF_WEEK = "DayOfWeek";
    private static final String WEEK_OF_MONTH = "WeekOfMonth";
    private static final String WEEK_OF_YEAR = "WeekOfYear";
    private static final String WEEK_OF_WEEK_BASED_YEAR = "WeekOfWeekBasedYear";
    private static final String WEEK_BASED_YEAR = "WeekBasedYear";
    private static final String YEAR_OF_ERA = "YearOfEra";
    private static final String YEAR = "Year";
    private static final String MONTH_OF_YEAR = "MonthOfYear";
    private static final String DAY_OF_MONTH = "DayOfMonth";

    private ReferenceDateParser() {
    }

    /**
     * A parser for a pattern in a zone. The reference time is asked for only when the pattern
     * needs it, and then once.
     *
     * @param pattern  a {@link DateTimeFormatter} pattern
     * @param zoneId   the zone a value with no zone of its own is in
     * @param baseTime the reference time — the stream's creation time in a pipeline, now otherwise
     */
    public static Function<String, Instant> create(final String pattern,
                                                   final ZoneId zoneId,
                                                   final Supplier<Instant> baseTime) {
        final DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder()
                .parseLenient()
                .parseCaseInsensitive()
                .appendPattern(pattern);
        final DateTimeFormatter parseFormatter = builder.toFormatter(LOCALE);
        final FieldSet fieldSet = new FieldSet(parseFormatter);

        if (fieldSet.contains(WEEK_BASED_YEAR)
            || fieldSet.contains(WEEK_OF_WEEK_BASED_YEAR)
            || fieldSet.contains(WEEK_OF_YEAR)
            || fieldSet.contains(WEEK_OF_MONTH)) {
            return createWeekBasedParser(fieldSet, builder, zoneId, baseTime);
        } else if (fieldSet.contains(DAY_OF_WEEK) && !fieldSet.contains(DAY_OF_MONTH)) {
            return createWeekBasedParser(fieldSet, builder, zoneId, baseTime);
        } else {
            return createRegularParser(fieldSet, builder, zoneId, baseTime);
        }
    }

    private static Function<String, Instant> createWeekBasedParser(final FieldSet fieldSet,
                                                                   final DateTimeFormatterBuilder builder,
                                                                   final ZoneId zoneId,
                                                                   final Supplier<Instant> baseTime) {
        final ZonedDateTime referenceDateTime = baseTime.get().atZone(zoneId);

        if (!fieldSet.contains(WEEK_BASED_YEAR)
            && !fieldSet.contains(YEAR_OF_ERA)
            && !fieldSet.contains(YEAR)) {
            builder.parseDefaulting(WEEK_FIELDS.weekBasedYear(), referenceDateTime.get(WEEK_FIELDS.weekBasedYear()));
        }

        if (!fieldSet.contains(WEEK_OF_WEEK_BASED_YEAR)) {
            if (!fieldSet.contains(WEEK_OF_YEAR)
                && !fieldSet.contains(WEEK_OF_MONTH)) {
                builder.parseDefaulting(WEEK_FIELDS.weekOfWeekBasedYear(),
                        referenceDateTime.get(WEEK_FIELDS.weekOfWeekBasedYear()));
            } else if (fieldSet.contains(WEEK_OF_MONTH)) {
                builder.parseDefaulting(ChronoField.MONTH_OF_YEAR, referenceDateTime.get(ChronoField.MONTH_OF_YEAR));
                builder.parseDefaulting(ChronoField.YEAR_OF_ERA, referenceDateTime.get(ChronoField.YEAR_OF_ERA));
            } else {
                builder.parseDefaulting(ChronoField.YEAR_OF_ERA, referenceDateTime.get(ChronoField.YEAR_OF_ERA));
            }
        }

        if (!fieldSet.contains(DAY_OF_WEEK)) {
            builder.parseDefaulting(WEEK_FIELDS.dayOfWeek(), referenceDateTime.get(WEEK_FIELDS.dayOfWeek()));
        }

        final DateTimeFormatter formatter = builder.toFormatter(LOCALE);
        return new WeekBasedParser(fieldSet, formatter, zoneId, referenceDateTime);
    }

    private static Function<String, Instant> createRegularParser(final FieldSet fieldSet,
                                                                 final DateTimeFormatterBuilder builder,
                                                                 final ZoneId zoneId,
                                                                 final Supplier<Instant> baseTime) {
        if ((fieldSet.contains(YEAR) || fieldSet.contains(YEAR_OF_ERA))
            && fieldSet.contains(MONTH_OF_YEAR)
            && fieldSet.contains(DAY_OF_MONTH)) {
            final DateTimeFormatter formatter = builder.toFormatter(LOCALE);
            return new RegularParser(formatter, zoneId);
        } else {
            final ZonedDateTime referenceDateTime = baseTime.get().atZone(zoneId);
            builder.parseDefaulting(ChronoField.YEAR_OF_ERA, referenceDateTime.get(ChronoField.YEAR_OF_ERA));
            builder.parseDefaulting(ChronoField.MONTH_OF_YEAR, referenceDateTime.get(ChronoField.MONTH_OF_YEAR));
            builder.parseDefaulting(ChronoField.DAY_OF_MONTH, referenceDateTime.get(ChronoField.DAY_OF_MONTH));
            final DateTimeFormatter formatter = builder.toFormatter(LOCALE);
            return new RegularParserWithReferenceTime(fieldSet, formatter, zoneId, referenceDateTime);
        }
    }

    private static ZonedDateTime parseBest(final String value, final DateTimeFormatter formatter, final ZoneId zoneId) {
        final TemporalAccessor temporalAccessor = formatter.parseBest(value,
                ZonedDateTime::from,
                LocalDateTime::from,
                LocalDate::from);
        if (temporalAccessor instanceof ZonedDateTime) {
            return ((ZonedDateTime) temporalAccessor).withZoneSameInstant(zoneId);
        }
        if (temporalAccessor instanceof LocalDateTime) {
            return ((LocalDateTime) temporalAccessor).atZone(zoneId);
        }
        return ((LocalDate) temporalAccessor).atStartOfDay(zoneId);
    }

    private static class FieldSet {

        private final String resolvedPattern;

        FieldSet(final DateTimeFormatter parseFormatter) {
            this.resolvedPattern = parseFormatter.toString();
        }

        private boolean contains(final String fieldName) {
            return resolvedPattern.contains("(" + fieldName + ",");
        }
    }

    private static class RegularParser implements Function<String, Instant> {

        private final DateTimeFormatter formatter;
        private final ZoneId zoneId;

        RegularParser(final DateTimeFormatter formatter,
                      final ZoneId zoneId) {
            this.formatter = formatter;
            this.zoneId = zoneId;
        }

        @Override
        public Instant apply(final String value) {
            final ZonedDateTime dateTime = parseBest(value, formatter, zoneId);
            return dateTime.toInstant();
        }
    }

    private static class RegularParserWithReferenceTime implements Function<String, Instant> {

        private final DateTimeFormatter formatter;
        private final ZoneId zoneId;
        private final ZonedDateTime referenceDateTime;
        private final Function<ZonedDateTime, ZonedDateTime> adjustment;

        RegularParserWithReferenceTime(final FieldSet fieldSet,
                                       final DateTimeFormatter formatter,
                                       final ZoneId zoneId,
                                       final ZonedDateTime referenceDateTime) {
            this.formatter = formatter;
            this.zoneId = zoneId;
            this.referenceDateTime = referenceDateTime;

            if (!fieldSet.contains(YEAR_OF_ERA)
                && !fieldSet.contains(YEAR)) {
                if (!fieldSet.contains(MONTH_OF_YEAR)) {
                    adjustment = value -> value.minusMonths(1);
                } else {
                    adjustment = value -> value.minusYears(1);
                }
            } else {
                adjustment = value -> value;
            }
        }

        @Override
        public Instant apply(final String value) {
            ZonedDateTime dateTime = parseBest(value, formatter, zoneId);
            if (dateTime.isAfter(referenceDateTime)) {
                dateTime = adjustment.apply(dateTime);
            }
            return dateTime.toInstant();
        }
    }

    private static class WeekBasedParser implements Function<String, Instant> {

        private final DateTimeFormatter formatter;
        private final ZoneId zoneId;
        private final ZonedDateTime referenceDateTime;
        private final Function<ZonedDateTime, ZonedDateTime> adjustment;

        WeekBasedParser(final FieldSet fieldSet,
                        final DateTimeFormatter formatter,
                        final ZoneId zoneId,
                        final ZonedDateTime referenceDateTime) {
            this.formatter = formatter;
            this.zoneId = zoneId;
            this.referenceDateTime = referenceDateTime;

            if (!fieldSet.contains(YEAR_OF_ERA)
                && !fieldSet.contains(YEAR)
                && !fieldSet.contains(WEEK_BASED_YEAR)) {
                if (!fieldSet.contains(MONTH_OF_YEAR)
                    && !fieldSet.contains(WEEK_OF_WEEK_BASED_YEAR)
                    && !fieldSet.contains(WEEK_OF_YEAR)) {
                    adjustment = value -> value.minusWeeks(1);
                } else {
                    adjustment = value -> value.minusWeeks(52);
                }
            } else {
                adjustment = value -> value;
            }
        }

        @Override
        public Instant apply(final String value) {
            ZonedDateTime dateTime = parseBest(value, formatter, zoneId);
            if (dateTime.isAfter(referenceDateTime)) {
                dateTime = adjustment.apply(dateTime);
            }
            return dateTime.toInstant();
        }
    }
}
