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
 * A transform with a {@code name} binds its result instead of writing it — the {@code Transform}
 * branch of {@code Binding}, which design 35 keeps as a value source (§4) but which had no test
 * naming that it binds rather than emits.
 */
class NamedTransformTest {

    @Test
    void namedTransformBindsRatherThanWrites() {
        final String json = """
                {
                  "name": "tx", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "line"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "line",
                     "match": {"regex": {"pattern": "([A-Za-z]+)\\n"}},
                     "captures": [{"name": "w", "select": {"group": 1}}],
                     "body": [
                       {"lower-case": {"select": [{"parts": [{"capture": {"var_id": "w", "group": 0}}]}],
                                       "name": "lc"}},
                       {"text": "<"},
                       {"value-of": {"parts": [{"capture": {"var_id": "lc", "group": 0}}]}},
                       {"text": ">"}]}
                  ]
                }
                """;
        // Nothing but the angle-bracketed read reaches output: the transform bound, it did not write.
        assertThat(run(json, "HeLLo\n")).isEqualTo("<hello>");
    }

    private static String run(final String json, final String input) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(out));
        return out.toString(StandardCharsets.UTF_8).replace("\n", "");
    }
}
