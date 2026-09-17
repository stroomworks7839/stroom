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
 * A match makes one value, its span, and every group is a range of it (design 37 §5, phase
 * 3d). The span is group 0 unless a group in a look-around lies outside it — which no fixture
 * does, so this pins it: a group captured inside a look-ahead is past the end of the match,
 * and must still hold its bytes, at the root (over the window, where the span is a copy) and
 * below it (over a value, where the span is a slice).
 */
class SpanGroupsTest {

    private static String run(final String input) {
        final String json = """
                {
                  "name": "span", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                     "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "word"}}]},

                    {"id": "00000000-0000-0000-0000-000000000002", "name": "word", "mode": "word",
                     "match": {"regex": {"pattern": "([a-z]+)(?=(,[a-z]+))"}},
                     "body": [
                       {"value-of": {"parts": [
                           {"text": "["}, {"capture": {"group": 1}},
                           {"text": "|"}, {"capture": {"group": 2}},
                           {"text": "|"}, {"capture": {"group": 0}}, {"text": "]"}]}},
                       {"apply-templates": {"select": {"parts": [{"capture": {"group": 2}}]},
                                            "mode": "inner"}}
                     ]},

                    {"id": "00000000-0000-0000-0000-000000000003", "name": "inner", "mode": "inner",
                     "match": {"regex": {"pattern": ",([a-z])(?=([a-z]))"}},
                     "body": [{"value-of": {"parts": [
                         {"text": "<"}, {"capture": {"group": 1}}, {"capture": {"group": 2}}, {"text": ">"}]}}]}
                  ]
                }
                """;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(output));
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void lookAheadGroupOutsideTheMatchKeepsItsBytesAtEveryLevel() {
        final String out = run("alpha,beta,gamma");
        // The root regex matches "alpha" and looks ahead at ",beta": group 2 lies past group 0.
        assertThat(out).contains("[alpha|,beta|alpha]");
        // The nested regex runs over the slice ",beta" and does the same one level down.
        assertThat(out).contains("<be>");
        assertThat(out).contains("[beta|,gamma|beta]");
    }
}
