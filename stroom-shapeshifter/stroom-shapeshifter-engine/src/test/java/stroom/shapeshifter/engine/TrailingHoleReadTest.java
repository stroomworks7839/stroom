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

package stroom.shapeshifter.engine;

import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.output.XmlByteSink;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * At configuration level: a capture that fails on a template's <i>last</i> match is absence,
 * assigned like any value (design 35 §8), so an unindexed read lands on it. Before phase 3 the
 * failed capture was simply not recorded — the store ended at the previous match and the read
 * walked back to it — and this test pinned that; phase 3 flipped it, deliberately: a read that
 * walks back is returning a value from a position the data did not fill.
 */
class TrailingHoleReadTest {

    @Test
    void readAfterAFailedFinalCaptureIsAbsent() {
        assertThat(run(CONFIG.replace("TYPE", "scalar"), "1 2 x\n")).isEqualTo("[]");
    }

    /**
     * The same on a list, which is where the ruling bites: the failed final capture is an
     * appended absence, so {@code last} is absent rather than the previous match's value.
     */
    @Test
    void failedFinalCaptureIntoAListAppendsAbsence() {
        assertThat(run(CONFIG.replace("TYPE", "list"), "1 2 x\n")).isEqualTo("[]");
    }

    /** And a failed middle capture keeps the positions after it aligned with their matches. */
    @Test
    void failedMiddleCaptureKeepsPositionsAligned() {
        final String third = CONFIG.replace("TYPE", "list").replace(
                "{\"capture\": {\"var_id\": \"v\", \"group\": 0}}",
                "{\"capture\": {\"var_id\": \"v\", \"group\": 0, \"match_index\": {\"index\": 3}}}");
        assertThat(run(third, "1 x 3\n")).isEqualTo("[3]");
    }

    private static final String CONFIG = """
                {
                  "name": "hole", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "rec"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "rec", "mode": "rec",
                     "declarations": [{"name": "v", "type": "TYPE"}],
                     "match": {"regex": {"pattern": "[^\\n]*\\n"}},
                     "body": [
                       {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                            "mode": "tok"}},
                       {"text": "["}, {"value-of": {"parts": [{"capture": {"var_id": "v", "group": 0}}]}},
                       {"text": "]"}]},
                    {"id": "00000000-0000-0000-0000-000000000003", "name": "tok", "mode": "tok",
                     "match": {"regex": {"pattern": "([a-z0-9]+)"}},
                     "captures": [{"name": "v", "select": {"group": 1}, "as": "number"}],
                     "body": []}
                  ]
                }
                """;

    private static String run(final String json, final String input) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(out));
        return out.toString(StandardCharsets.UTF_8).replace("\n", "");
    }
}
