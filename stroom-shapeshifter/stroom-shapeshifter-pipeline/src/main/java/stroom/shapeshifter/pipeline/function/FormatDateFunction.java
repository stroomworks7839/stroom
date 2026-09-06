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

package stroom.shapeshifter.pipeline.function;

import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.engine.value.Dates;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.util.date.DateFormatterCache;
import stroom.util.date.DateUtil;
import stroom.util.date.ReferenceDateParser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code format-date}: as {@code stroom.pipeline.xsltfunctions.FormatDate}, in its three forms.
 * One argument is epoch milliseconds; two or three are a value, its pattern and its zone, to
 * Stroom's normal form; four or five add the output pattern and zone. The parser is
 * stroom-util's {@link ReferenceDateParser}, the one the XSLT function runs. A context
 * function: a pattern with no year is completed from the stream's creation time.
 */
public final class FormatDateFunction extends StroomFunction {

    public FormatDateFunction() {
        super("format-date",
                Signature.of(1, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING),
                Purity.CONTEXT);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return new Call(context);
    }

    private final class Call implements FunctionCall {

        private final FunctionContext context;
        private final Map<String, Function<String, Instant>> parsers = new HashMap<>();
        private Instant baseTime;

        private Call(final FunctionContext context) {
            this.context = context;
        }

        @Override
        public TypedValue call(final Arguments arguments) {
            return switch (arguments.size()) {
                case 1 -> text(millis(arguments));
                case 2, 3 -> text(toNormalForm(arguments));
                case 4, 5 -> text(toPattern(arguments));
                default -> null;
            };
        }

        private String millis(final Arguments arguments) {
            final String milliseconds = requiredString(context, arguments, 0);
            if (milliseconds == null) {
                return null;
            }
            try {
                return DateUtil.createNormalDateTimeString(Long.parseLong(milliseconds));
            } catch (final RuntimeException e) {
                context.warn("Failed to parse date: \"" + milliseconds + "\"");
                return null;
            }
        }

        private String toNormalForm(final Arguments arguments) {
            final String value = requiredString(context, arguments, 0);
            final String pattern = requiredString(context, arguments, 1);
            if (value == null || pattern == null) {
                return null;
            }
            final String timeZone = arguments.size() == 3 ? arguments.string(2) : null;
            final Instant parsed = parse(value, pattern, timeZone);
            return parsed == null ? null : DateUtil.createNormalDateTimeString(parsed.toEpochMilli());
        }

        private String toPattern(final Arguments arguments) {
            final String value = requiredString(context, arguments, 0);
            final String patternIn = requiredString(context, arguments, 1);
            final String timeZoneIn = arguments.string(2);
            final String patternOut = requiredString(context, arguments, 3);
            if (value == null || patternIn == null || patternOut == null) {
                return null;
            }
            final String timeZoneOut = arguments.size() == 5 ? arguments.string(4) : null;
            final Instant parsed = parse(value, patternIn, timeZoneIn);
            if (parsed == null) {
                return null;
            }
            final ZoneId zoneId = Dates.zone(context, timeZoneOut);
            try {
                final ZonedDateTime dateTime = parsed.atZone(zoneId);
                final DateTimeFormatter formatter = DateFormatterCache.getFormatter(patternOut);
                return formatter.format(dateTime);
            } catch (final RuntimeException e) {
                context.warn("Failed to format date: \"" + value + "\" (Pattern: " + patternOut
                             + ", Time Zone: " + timeZoneOut + ")");
                return null;
            }
        }

        private Instant parse(final String value, final String pattern, final String timeZone) {
            try {
                final Function<String, Instant> parser = parsers.computeIfAbsent(pattern + " " + timeZone,
                        key -> ReferenceDateParser.create(pattern, Dates.zone(context, timeZone), this::baseTime));
                return parser.apply(value);
            } catch (final RuntimeException e) {
                context.warn("Failed to parse date: \"" + value + "\" (Pattern: " + pattern
                             + ", Time Zone: " + timeZone + ")");
                return null;
            }
        }

        private Instant baseTime() {
            if (baseTime == null) {
                baseTime = Dates.baseTime(context);
            }
            return baseTime;
        }
    }
}
