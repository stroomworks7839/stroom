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

import stroom.shapeshifter.engine.ProjectReader;
import stroom.shapeshifter.engine.output.XmlByteSink;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A variable whose body dispatches to templates that capture into the variable's own name.
 *
 * <p>Those captures are bound in the scope the variable opens, and have to survive its closing —
 * {@code Body.variable} promotes them, keeping their per-match structure so a later reference can
 * still ask for the second one. <b>Nothing tested it.</b> Deleting the promotion outright passed
 * the whole suite, which is how this file came to exist (design 33 §11 E).
 */
class VariablePromotionTest {

    private static String run(final String input) {
        final String json = """
                {
                  "name": "promote", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                     "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "outer"}}]},

                    {"id": "00000000-0000-0000-0000-000000000002", "name": "outer", "mode": "outer",
                     "declarations": [{"name": "word", "type": "list"}],
                     "match": {"regex": {"pattern": ".+"}},
                     "body": [
                       {"variable": {"name": "word", "body": [
                           {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                "mode": "word"}}]}},
                       {"value-of": {"parts": [
                           {"text": "first="},
                           {"capture": {"var_id": "word", "group": 0,
                                        "match_index": {"index": 1, "is_offset": false}}}]}},
                       {"value-of": {"parts": [
                           {"text": " second="},
                           {"capture": {"var_id": "word", "group": 0,
                                        "match_index": {"index": 2, "is_offset": false}}}]}},
                       {"value-of": {"parts": [
                           {"text": " latest="},
                           {"last": {"of": "word"}}]}}
                     ]},

                    {"id": "00000000-0000-0000-0000-000000000003", "name": "word", "mode": "word",
                     "match": {"regex": {"pattern": "[a-z]+"}},
                     "captures": [{"name": "word", "select": {"group": 0}}],
                     "body": []}
                  ]
                }
                """;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(output));
        return output.toString(StandardCharsets.UTF_8);
    }

    /**
     * The captures outlive the scope that made them, and keep their per-match structure: the
     * first and second matches are both still reachable by index.
     */
    @Test
    void capturesMadeInsideAVariableSurviveItsScope() {
        final String out = run("alpha beta gamma");
        assertThat(out).contains("first=alpha");
        assertThat(out).contains("second=beta");
        assertThat(out).contains("latest=gamma");
    }
}
