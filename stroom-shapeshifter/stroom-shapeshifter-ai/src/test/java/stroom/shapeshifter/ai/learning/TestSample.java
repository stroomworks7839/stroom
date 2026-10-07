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

package stroom.shapeshifter.ai.learning;

import stroom.meta.api.StandardHeaderArguments;
import stroom.meta.shared.MetaFields;
import stroom.shapeshifter.shared.RoutingFields;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestSample {

    @Test
    void routingHeaderNamesAreStroomsStandardHeaderArguments() {
        // RoutingFields restates the names because StandardHeaderArguments is not shared with the client.
        assertThat(RoutingFields.HEADERS).containsExactly(
                StandardHeaderArguments.FORMAT,
                StandardHeaderArguments.SCHEMA,
                StandardHeaderArguments.COMPRESSION,
                StandardHeaderArguments.SYSTEM,
                StandardHeaderArguments.ENVIRONMENT,
                StandardHeaderArguments.REMOTE_FILE);
    }

    @Test
    void keepsOnlyTheFeedAndTheType() {
        // A126: the stream carries more than its feed and type, and only they travel, the feed first.
        final Sample sample = Sample.of("text",
                Map.of(
                        StandardHeaderArguments.SYSTEM, "Door Access",
                        MetaFields.FIELD_TYPE, "Raw Events",
                        MetaFields.FIELD_FEED, "SYSLOG",
                        "X-Sender-Token", "s3cret"));

        assertThat(sample.headers()).containsExactly(
                Map.entry(MetaFields.FIELD_FEED, "SYSLOG"),
                Map.entry(MetaFields.FIELD_TYPE, "Raw Events"));
    }

    @Test
    void aFieldTheStreamLacksIsAbsent() {
        final Sample sample = Sample.of("text", Map.of(MetaFields.FIELD_TYPE, "Raw Events"));

        assertThat(sample.headers()).containsExactly(Map.entry(MetaFields.FIELD_TYPE, "Raw Events"));
    }
}
