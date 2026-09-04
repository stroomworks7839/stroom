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

import stroom.meta.shared.Meta;
import stroom.pipeline.state.MetaHolder;
import stroom.shapeshifter.engine.exec.TypedValue;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.util.date.DateFormatterCache;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** What the date functions share: the engine's instant, the reference time, the zone. */
final class Dates {

    private Dates() {
    }

    /** A Java instant as the engine's date value, in UTC. */
    static TypedValue.Instant instant(final Instant instant) {
        return new TypedValue.Instant(instant.getEpochSecond(), instant.getNano(), null);
    }

    /**
     * The reference time a pattern with no year is completed from: the stream's creation time
     * when a {@code MetaHolder} is reachable and has meta, now otherwise, which is
     * {@code FormatDate.getBaseTime}'s rule.
     */
    static Instant baseTime(final FunctionContext context) {
        final MetaHolder holder = context.service(MetaHolder.class);
        final Meta meta = holder == null ? null : holder.getMeta();
        return meta == null ? Instant.now() : Instant.ofEpochMilli(meta.getCreateMs());
    }

    /** A zone by Stroom's names ({@code GMT/BST} included), UTC with a warning when unknown. */
    static ZoneId zone(final FunctionContext context, final String timeZone) {
        try {
            return DateFormatterCache.getZoneId(timeZone);
        } catch (final RuntimeException e) {
            context.warn("Time Zone '" + timeZone + "' is not recognised, defaulting to UTC");
            return ZoneOffset.UTC;
        }
    }
}
