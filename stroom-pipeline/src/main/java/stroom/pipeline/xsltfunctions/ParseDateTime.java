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
import net.sf.saxon.value.DateTimeValue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

class ParseDateTime extends StroomExtensionFunctionCall {

    public static final String FUNCTION_NAME = "parse-dateTime";




    private final MetaHolder metaHolder;

    private final Map<Key, Function<String, Instant>> cachedParsers = new HashMap<>();

    private Instant baseTime;

    @Inject
    ParseDateTime(final MetaHolder metaHolder) {
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
        DateTimeValue result = null;

        try {
            if (arguments.length >= 1 && arguments.length <= 3) {
                result = convertToStandardDateFormat(functionName, context, arguments);
            }
        } catch (final XPathException | RuntimeException e) {
            log(context, Severity.ERROR, e.getMessage(), e);
        }

        if (result == null) {
            return EmptyAtomicSequence.getInstance();
        }
        return result;
    }

    private DateTimeValue convertToStandardDateFormat(final String functionName, final XPathContext context,
                                               final Sequence[] arguments) throws XPathException {
        DateTimeValue result = null;
        final String value = getSafeString(functionName, context, arguments, 0);

        String pattern = null;
        if (arguments.length >= 2) {
            pattern = getSafeString(functionName, context, arguments, 1);
        }

        String timeZone = null;
        if (arguments.length == 3) {
            timeZone = getSafeString(functionName, context, arguments, 2);
        }

        // Parse the supplied date.
        Instant instant = null;
        try {
            // If the incoming pattern doesn't contain year then we might need to figure the year out for ourselves.
            instant = parseDate(context, value, pattern, timeZone);
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

        if (instant != null) {
            result = DateTimeValue.fromJavaInstant(instant);
        }

        return result;
    }

    private Instant parseDate(final XPathContext context, final String value, final String pattern,
                              final String timeZone) {
        // no pattern means iso 8061 format
        if (pattern == null) {
            return DateUtil.parseNormalDateTimeStringToInstant(value);
        }

        final Key key = new Key(pattern, timeZone);
        final Function<String, Instant> parser = cachedParsers.computeIfAbsent(key,
                k -> createParser(context, k.pattern, k.timeZone));
        return parser.apply(value);
    }

    private Function<String, Instant> createParser(final XPathContext context,
                                                final String pattern,
                                                final String timeZone) {
        // The parser itself is stroom-util's ReferenceDateParser, shared with the Shapeshifter
        // variant of this function so that both run one implementation.
        final ZoneId zoneId = getTimeZone(context, timeZone);
        final Function<String, Instant> parser = ReferenceDateParser.create(pattern, zoneId, this::getBaseTime);
        return parser;
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
