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

import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.util.date.DateFormatterCache;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * {@code format-dateTime}: as {@code stroom.pipeline.xsltfunctions.FormatDateTime}: a date, an
 * optional pattern (Stroom's normal form without one) and an optional zone, to text.
 */
public final class FormatDateTimeFunction extends StroomFunction {

    public FormatDateTimeFunction() {
        super("format-dateTime", Signature.of(1, Kind.STRING, Kind.DATE, Kind.STRING, Kind.STRING), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final TypedValue.Instant value = arguments.date(0);
            if (value == null) {
                context.warn("Illegal non dateTime argument found in function " + name() + "() at position 0");
                return null;
            }
            final String patternOut = arguments.string(1);
            final String timeZoneOut = arguments.size() == 3 ? arguments.string(2) : null;
            final ZoneId zoneId = Dates.zone(context, timeZoneOut);
            try {
                final DateTimeFormatter formatter = DateFormatterCache.getFormatter(patternOut);
                return TypedValue.of(formatter.format(value.toJavaInstant().atZone(zoneId)));
            } catch (final RuntimeException e) {
                context.warn("Failed to format date: \"" + value.asString() + "\" (Pattern: " + patternOut
                             + ", Time Zone: " + timeZoneOut + ")");
                return null;
            }
        };
    }
}
