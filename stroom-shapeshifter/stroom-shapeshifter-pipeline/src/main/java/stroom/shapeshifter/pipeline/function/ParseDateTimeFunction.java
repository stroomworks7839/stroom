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

import stroom.shapeshifter.engine.exec.TypedValue;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.util.date.DateUtil;
import stroom.util.date.ReferenceDateParser;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code parse-dateTime}: as {@code stroom.pipeline.xsltfunctions.ParseDateTime}: a value, an
 * optional pattern (Stroom's normal form without one) and an optional zone, to a date. The
 * parser is stroom-util's {@link ReferenceDateParser}, the one the XSLT function runs.
 */
public final class ParseDateTimeFunction extends StroomFunction {

    public ParseDateTimeFunction() {
        super("parse-dateTime", Signature.of(1, Kind.DATE, Kind.STRING, Kind.STRING, Kind.STRING), Purity.CONTEXT);
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
            final String value = requiredString(context, arguments, 0);
            if (value == null) {
                return null;
            }
            final String pattern = arguments.string(1);
            final String timeZone = arguments.size() == 3 ? arguments.string(2) : null;
            try {
                final Instant instant;
                if (pattern == null) {
                    instant = DateUtil.parseNormalDateTimeStringToInstant(value);
                } else {
                    instant = parsers.computeIfAbsent(pattern + " " + timeZone,
                            key -> ReferenceDateParser.create(pattern, Dates.zone(context, timeZone), this::baseTime))
                            .apply(value);
                }
                return Dates.instant(instant);
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
