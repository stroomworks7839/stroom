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

package stroom.pipeline.xsltfunctions;

import stroom.meta.shared.Meta;
import stroom.pipeline.LocationFactory;
import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.shared.data.PipelineReference;
import stroom.pipeline.state.MetaHolder;
import stroom.util.date.DateFormatterCache;
import stroom.util.date.DateUtil;
import stroom.util.date.ReferenceDateParser;
import stroom.util.shared.NullSafe;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.om.EmptyAtomicSequence;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.StringValue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

class FormatDate extends StroomExtensionFunctionCall {

    public static final String FUNCTION_NAME = "format-date";




    private final MetaHolder metaHolder;

    private final Map<Key, Function<String, Long>> cachedParsers = new HashMap<>();

    private Instant baseTime;

    @Inject
    FormatDate(final MetaHolder metaHolder) {
        this.metaHolder = metaHolder;
    }

    @Override
    void configure(final ErrorReceiver errorReceiver,
                   final LocationFactory locationFactory,
                   final List<PipelineReference> pipelineReferences) {
        super.configure(errorReceiver, locationFactory, pipelineReferences);

        // Reset the parser cache.
        cachedParsers.clear();
    }

    @Override
    protected Sequence call(final String functionName, final XPathContext context, final Sequence[] arguments) {
        String result = null;

        try {
            if (arguments.length == 1) {
                result = convertMilliseconds(functionName, context, arguments);

            } else if (arguments.length >= 2 && arguments.length <= 3) {
                result = convertToStandardDateFormat(functionName, context, arguments);

            } else if (arguments.length >= 4 && arguments.length <= 5) {
                result = convertToSpecifiedDateFormat(functionName, context, arguments);
            }
        } catch (final XPathException | RuntimeException e) {
            log(context, Severity.ERROR, e.getMessage(), e);
        }

        if (result == null) {
            return EmptyAtomicSequence.getInstance();
        }
        return StringValue.makeStringValue(result);
    }

    private String convertMilliseconds(final String functionName, final XPathContext context,
                                       final Sequence[] arguments) throws XPathException {
        String result = null;
        final String milliseconds = getSafeString(functionName, context, arguments, 0);

        try {
            final long ms = Long.parseLong(milliseconds);
            result = DateUtil.createNormalDateTimeString(ms);

        } catch (final RuntimeException e) {
            final StringBuilder sb = new StringBuilder();
            sb.append("Failed to parse date: \"");
            sb.append(milliseconds);
            sb.append('"');
            outputWarning(context, sb, e);
        }

        return result;
    }

    private String convertToStandardDateFormat(final String functionName, final XPathContext context,
                                               final Sequence[] arguments) throws XPathException {
        String result = null;
        final String value = getSafeString(functionName, context, arguments, 0);
        final String pattern = getSafeString(functionName, context, arguments, 1);
        String timeZone = null;
        if (arguments.length == 3) {
            timeZone = getSafeString(functionName, context, arguments, 2);
        }

        // Parse the supplied date.
        long ms = -1;
        try {
            // If the incoming pattern doesn't contain year then we might need to figure the year out for ourselves.
            ms = parseDate(context, value, pattern, timeZone);
        } catch (final RuntimeException e) {
            final StringBuilder sb = new StringBuilder();
            sb.append("Failed to parse date: \"");
            sb.append(value);
            sb.append("\" (Pattern: ");
            sb.append(pattern);
            sb.append(", Time Zone: ");
            sb.append(timeZone);
            sb.append(")");
            outputWarning(context, sb, e);
        }

        if (ms != -1) {
            result = DateUtil.createNormalDateTimeString(ms);
        }

        return result;
    }

    private String convertToSpecifiedDateFormat(final String functionName, final XPathContext context,
                                                final Sequence[] arguments) throws XPathException {
        String result = null;
        final String value = getSafeString(functionName, context, arguments, 0);
        final String patternIn = getSafeString(functionName, context, arguments, 1);
        final String timeZoneIn = getSafeString(functionName, context, arguments, 2);
        final String patternOut = getSafeString(functionName, context, arguments, 3);
        String timeZoneOut = null;
        if (arguments.length == 5) {
            timeZoneOut = getSafeString(functionName, context, arguments, 4);
        }

        // Parse the supplied date.
        long ms = -1;
        try {
            // If the incoming pattern doesn't contain year then we might need to figure the year out for ourselves.
            ms = parseDate(context, value, patternIn, timeZoneIn);
        } catch (final RuntimeException e) {
            final StringBuilder sb = new StringBuilder();
            sb.append("Failed to parse date: \"");
            sb.append(value);
            sb.append("\" (Pattern: ");
            sb.append(patternIn);
            sb.append(", Time Zone: ");
            sb.append(timeZoneIn);
            sb.append(")");
            outputWarning(context, sb, e);
        }

        if (ms != -1) {
            // Resolve the output time zone.
            final ZoneId zoneId = getTimeZone(context, timeZoneOut);
            try {
                // Now format the date using the specified pattern and time
                // zone.
                final ZonedDateTime dateTime = Instant.ofEpochMilli(ms).atZone(zoneId);
                final DateTimeFormatter dateTimeFormatter = DateFormatterCache.getFormatter(patternOut);
                result = dateTimeFormatter.format(dateTime);
            } catch (final RuntimeException e) {
                final StringBuilder sb = new StringBuilder();
                sb.append("Failed to format date: \"");
                sb.append(value);
                sb.append("\" (Pattern: ");
                sb.append(patternOut);
                sb.append(", Time Zone: ");
                sb.append(timeZoneOut);
                sb.append(")");
                outputWarning(context, sb, e);
            }
        }

        return result;
    }

    long parseDate(final XPathContext context, final String value, final String pattern, final String timeZone) {
        final Key key = new Key(pattern, timeZone);
        final Function<String, Long> parser = cachedParsers.computeIfAbsent(key,
                k -> createParser(context, k.pattern, k.timeZone));
        return parser.apply(value);
    }

//    long parseDate(final XPathContext context, final String value, final String pattern, final String timeZone) {
//        ZonedDateTime dateTime;
//
//        // Don't use the defaulting formatter if we can help it.
//        if (pattern.contains(FULL_YEAR_PATTERN)
//        && pattern.contains(FULL_MONTH_PATTERN)
//        && pattern.contains(FULL_DAY_PATTERN)) {
//            final DateTimeFormatter formatter = DateFormatterCache.getFormatter(pattern);
//            final ZoneId zoneId = getTimeZone(context, timeZone);
//
//            // Parse the date as best we can.
//            dateTime = DateUtil.parseBest(value, formatter, zoneId);
//
//        } else {
//            final ZoneId zoneId = getTimeZone(context, timeZone);
//            final ZonedDateTime referenceDateTime = getBaseTime();
//            final DateTimeFormatter formatter = DateFormatterCache.getDefaultingFormatter(pattern, referenceDateTime);
//
//            // Parse the date as best we can.
//            dateTime = DateUtil.parseBest(value, formatter, zoneId);
//
//            // Subtract a year if the date appears to be after our reference time.
//            if (dateTime.isAfter(referenceDateTime)) {
//                if (!pattern.contains("y")) {
//                    if (!pattern.contains("M")) {
//                        dateTime = dateTime.minusMonths(1);
//                    } else {
//                        dateTime = dateTime.minusYears(1);
//                    }
//                }
//            }
//        }
//
//        return dateTime.toInstant().toEpochMilli();
//    }

    private Function<String, Long> createParser(final XPathContext context,
                                                final String pattern,
                                                final String timeZone) {
        // The parser itself is stroom-util's ReferenceDateParser, shared with the Shapeshifter
        // variant of this function so that both run one implementation.
        final ZoneId zoneId = getTimeZone(context, timeZone);
        final Function<String, Instant> parser = ReferenceDateParser.create(pattern, zoneId, this::getBaseTime);
        return value -> parser.apply(value).toEpochMilli();
    }

    private ZoneId getTimeZone(final XPathContext context, final String timeZone) {
        try {
            return DateFormatterCache.getZoneId(timeZone);
        } catch (final RuntimeException e) {
            final StringBuilder sb = new StringBuilder();
            sb.append("Time Zone '");
            sb.append(timeZone);
            sb.append("' is not recognised, defaulting to UTC");
            outputWarning(context, sb, e);
        }
        return ZoneOffset.UTC;
    }

    // Pkg private for testing
    Instant getBaseTime() {
        if (baseTime == null) {
            baseTime = NullSafe.getOrElseGet(
                    metaHolder,
                    MetaHolder::getMeta,
                    Meta::getCreateMs,
                    Instant::ofEpochMilli,
                    Instant::now);
        }
        return baseTime;
    }

    // --------------------------------------------------------------------------------


    // --------------------------------------------------------------------------------


    private static class Key {

        private final String pattern;
        private final String timeZone;

        Key(final String pattern, final String timeZone) {
            this.pattern = pattern;
            this.timeZone = timeZone;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final Key key = (Key) o;
            return Objects.equals(pattern, key.pattern) &&
                   Objects.equals(timeZone, key.timeZone);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pattern, timeZone);
        }
    }


    // --------------------------------------------------------------------------------


    // --------------------------------------------------------------------------------


    // --------------------------------------------------------------------------------


}
