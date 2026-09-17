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

/** The engine's library-free protobuf parse against protobuf-java's reading of messages it wrote (design 40 §6). */
class ProtobufAmplifiedTest {

    @Test
    void theEngineReadsWhatTheLibraryWrote() {
        final ProtobufMessages.Amplified amplified = ProtobufMessages.amplify(5000, 40L);
        assertThat(amplified.expected()).contains("ok=\"false\"").contains("ok=\"true\"");
        final EngineHarness.Outcome outcome = EngineHarness.runProjectWholeBuffer(
                FixtureLedger.text("projects/protobuf_events/project.json"), amplified.bytes());
        assertThat(outcome.messages()).isEmpty();
        assertThat(new String(outcome.output(), StandardCharsets.UTF_8)).isEqualTo(amplified.expected());
    }
}
