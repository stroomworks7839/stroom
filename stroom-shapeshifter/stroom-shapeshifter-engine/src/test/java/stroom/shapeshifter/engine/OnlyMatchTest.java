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
 * {@code only_match}: which of a template's matches produce output.
 *
 * <p><b>This is not DS3's {@code onlyMatch}, and the names colliding is a trap.</b> A match
 * limit counts <i>this</i> template's matches, which is what this file pins. DS3's attribute
 * counts the <i>parent's</i> — "only when my parent is on its second match" — so
 * {@code Ds3Migration.onlyMatchGuard} turns it into a condition on {@code __match_idx} and leaves
 * this field null. They are different predicates that share a word, not one feature expressed
 * two ways.
 *
 * <p><b>Written because nothing tested it.</b> {@code ProjectReaderTest} pins the parsing, the
 * two fixtures named {@code *_only_match} carry the DS3 attribute and therefore compile to
 * guards, and no native fixture sets {@code match_limits.only_match} at all. Disabling the filter
 * in {@code Level} passed the entire suite, which is how the gap was found: the conversion of the
 * compiled form to an {@code int[]} would have been unverifiable without this (design 33 §11).
 */
class OnlyMatchTest {

    private static String run(final String onlyMatch, final String input) {
        final String json = """
                {
                  "name": "only", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                     "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "line"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "line",
                     "match": {"regex": {"pattern": "[a-z]+"}}LIMITS,
                     "body": [{"value-of": {"parts": [{"text": "["},
                                                      {"capture": {"group": 0}},
                                                      {"text": "]"}]}}]}
                  ]
                }
                """.replace("LIMITS", onlyMatch == null
                ? ""
                : ", \"match_limits\": {\"only_match\": " + onlyMatch + "}");

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(output));
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void withoutOnlyMatchEveryMatchProducesOutput() {
        assertThat(run(null, "aa bb cc dd")).contains("[aa]", "[bb]", "[cc]", "[dd]");
    }

    @Test
    void onlyTheNamedMatchesProduceOutput() {
        final String out = run("[1, 3]", "aa bb cc dd");
        assertThat(out).contains("[aa]", "[cc]");
        assertThat(out).doesNotContain("[bb]", "[dd]");
    }

    /** One index is the common case, and the one the compiled {@code int[]} is sized for. */
    @Test
    void oneIndexIsHonoured() {
        final String out = run("[2]", "aa bb cc");
        assertThat(out).contains("[bb]");
        assertThat(out).doesNotContain("[aa]", "[cc]");
    }

    /** The indices are one-based, so zero selects nothing at all. */
    @Test
    void indicesAreOneBased() {
        assertThat(run("[4]", "aa bb cc")).doesNotContain("[aa]", "[bb]", "[cc]");
    }
}
