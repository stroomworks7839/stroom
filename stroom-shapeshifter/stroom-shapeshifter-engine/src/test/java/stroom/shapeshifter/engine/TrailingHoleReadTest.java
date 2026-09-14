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
 * At configuration level: a capture that fails on a template's <i>last</i> match is simply not
 * recorded — {@code Store.remove} does not grow the store — so the store ends at the previous
 * match and an unindexed read lands there. No hole exists at the end; the read is not skipping
 * anything. Pinned before design 35 phase 3, where a failed capture appends absence and
 * {@code last(list)} returns it (§8) — the one case where that ruling reads differently from
 * today. Sabotage: make the failed cast write a marker instead of removing, and the read shows it.
 */
class TrailingHoleReadTest {

    @Test
    void readAfterAFailedFinalCaptureWalksBackToThePreviousMatch() {
        final String json = """
                {
                  "name": "hole", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "rec"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "rec", "mode": "rec",
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
        // Three tokens; the third is not a number, so its cast fails and nothing is recorded at
        // match 3. The store ends at match 2 and the read lands there.
        assertThat(run(json, "1 2 x\n")).isEqualTo("[2]");
    }

    private static String run(final String json, final String input) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(out));
        return out.toString(StandardCharsets.UTF_8).replace("\n", "");
    }
}
