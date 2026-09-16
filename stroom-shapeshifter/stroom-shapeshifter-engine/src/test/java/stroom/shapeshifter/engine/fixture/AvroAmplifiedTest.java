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

package stroom.shapeshifter.engine.fixture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's library-free Avro parse against the Avro library's own reading of a container
 * the library wrote: many blocks, varied record lengths, the block template's length-prefixed
 * take exercised at every size. This is the parity gate for the {@code avro_users} benchmark
 * row (design 38 §7), as the golden fixture is for one block of three.
 */
class AvroAmplifiedTest {

    @Test
    void theEngineReadsWhatTheLibraryWrote() {
        final AvroContainers.Amplified amplified = AvroContainers.amplify(2500, 100, 38L);
        assertThat(amplified.bytes().length).as("many blocks of many records").isGreaterThan(40_000);
        final EngineHarness.Outcome outcome = EngineHarness.runProjectWholeBuffer(
                FixtureLedger.text("projects/avro_users/project.json"), amplified.bytes());
        assertThat(outcome.messages()).as("a clean parse").isEmpty();
        assertThat(new String(outcome.output(), StandardCharsets.UTF_8)).isEqualTo(amplified.expected());
    }
}
