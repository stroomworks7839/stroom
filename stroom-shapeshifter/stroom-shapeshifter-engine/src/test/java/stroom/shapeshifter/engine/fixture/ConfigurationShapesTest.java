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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fixture's challengers (design 37 phase 8): the same input and the same output as the
 * fixture's own configuration, in a different shape. The benchmark measures each shape as a
 * workload of its own, beside the fixture's; this is the parity gate that makes the numbers
 * comparable — a shape that writes different bytes is not a faster way of doing the job.
 */
class ConfigurationShapesTest {

    /** ausearch reads four known keys out of every record: a map, a switch into scalars, a template per key. */
    @ParameterizedTest
    @ValueSource(strings = {"project.json", "challenger-switch.project.json", "challenger-dispatch.project.json"})
    void ausearchShapesWriteTheSameBytes(final String configuration) {
        sameBytes("ausearch", configuration);
    }

    /** log_sessions files every status into a map to read one: filter at capture, or scan at use. */
    @ParameterizedTest
    @ValueSource(strings = {"project.json", "challenger-filtered.project.json", "challenger-scan.project.json"})
    void logSessionsShapesWriteTheSameBytes(final String configuration) {
        sameBytes("log_sessions", configuration);
    }

    /** apache_httpd looks a month name up in a twelve-entry table refilled on every line: a switch instead. */
    @ParameterizedTest
    @ValueSource(strings = {"project.json", "challenger-switch.project.json"})
    void apacheHttpdShapesWriteTheSameBytes(final String configuration) {
        sameBytes("apache_httpd", configuration);
    }

    /** win_sec reads thirty-nine labels out of a map filled by one template: a template per label, or a switch. */
    @ParameterizedTest
    @ValueSource(strings = {"project.json", "challenger-perkey.project.json", "challenger-switch.project.json"})
    void winSecShapesWriteTheSameBytes(final String configuration) {
        sameBytes("win_sec", configuration);
    }

    private static void sameBytes(final String fixture, final String configuration) {
        final EngineHarness.Outcome outcome = EngineHarness.runProject(
                FixtureLedger.text("projects/" + fixture + "/" + configuration),
                FixtureLedger.bytes("projects/" + fixture + "/input.txt"));
        // Warnings pass, as they do for the fixture itself; an error or a fatal is a shape that did not do the job.
        assertThat(outcome.messages()).as("messages from " + fixture + "/" + configuration)
                .noneMatch(m -> m.severity().equals("Error") || m.severity().equals("Fatal"));
        assertThat(new String(outcome.output(), StandardCharsets.UTF_8)).isEqualTo(
                new String(FixtureLedger.bytes("projects/" + fixture + "/example_output.xml"), StandardCharsets.UTF_8));
    }
}
