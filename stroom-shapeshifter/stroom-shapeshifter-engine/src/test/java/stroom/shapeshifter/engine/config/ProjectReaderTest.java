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

package stroom.shapeshifter.engine.config;

import stroom.shapeshifter.engine.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;
import stroom.shapeshifter.engine.fixture.FixtureLedger;
import stroom.shapeshifter.engine.fixture.FixtureLedger.Family;
import stroom.shapeshifter.engine.fixture.FixtureLedger.Fixture;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configuration model has to carry every configuration in the corpus, losslessly.
 *
 * <p>"Parses" is too weak a claim to be worth testing: a lenient reader parses anything and
 * quietly drops what it does not understand, and the result runs, wrongly. So each configuration
 * is read, written back out, and read again, and the two models must be equal. Anything the model
 * cannot represent disappears on the way out and the second read disagrees.
 *
 * <p>The reader is also strict — an unknown field is an error — which is the other half of the
 * same argument, and is checked here directly.
 */
class ProjectReaderTest {

    @TestFactory
    List<DynamicTest> everyConfigurationRoundTrips() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Fixture fixture : FixtureLedger.all()) {
            final String path = switch (fixture.family()) {
                case NATIVE -> "native/" + fixture.name() + "/project.json";
                case PROJECTS -> "projects/" + fixture.name() + "/project.json";
                default -> null;
            };
            if (path != null) {
                tests.add(DynamicTest.dynamicTest(fixture.id(), () -> roundTrip(path)));
            }
        }
        assertThat(tests).as("the corpus must contain configurations to check").hasSize(40);
        return tests;
    }

    private static void roundTrip(final String path) {
        final Project first = ProjectReader.read(FixtureLedger.bytes(path));
        assertThat(first.templates()).as("%s has templates", path).isNotEmpty();

        final Project second = ProjectReader.read(ProjectReader.write(first));
        assertThat(second)
                .as("%s must survive a write and a second read unchanged", path)
                .isEqualTo(first);
    }

    /**
     * The corpus is one big regression test for the reader, but it only proves what it contains.
     * This is the shape of the format stated directly, so a change that breaks it fails here with
     * a name rather than in a fixture with a diff.
     */
    @Test
    void readsTheShapesTheFormatIsMadeOf() {
        final Project project = ProjectReader.read("""
                {
                  "name": "shapes",
                  "version": 3,
                  "source": {"buffer_size": 4096, "ignore_errors": true, "encoding": "utf-8"},
                  "templates": [
                    {
                      "id": "00000000-0000-0000-0000-000000000001",
                      "name": "root",
                      "match": "source",
                      "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                    "mode": "row"}}]
                    },
                    {
                      "id": "00000000-0000-0000-0000-000000000002",
                      "name": "row",
                      "mode": "row",
                      "match": {"regex": {"pattern": "^(\\\\w+)=(\\\\d+)$",
                                          "flags": {"case_insensitive": true, "dot_all": false},
                                          "advance": 1}},
                      "match_limits": {"min_match": 1, "max_match": 5, "only_match": [1, 3]},
                      "captures": [
                        {"name": "key", "select": {"group": 1}},
                        {"name": "old", "select": {"select": {"parts": [
                            {"Store": {"var_id": "prev", "group": 2}}]}}}
                      ],
                      "body": [
                        {"text": "<row>"},
                        {"if": {"test": {"and": [{"exists": {"select": {"parts": []}}},
                                                {"not": "is-first"}]},
                                "then": [{"trim": {"select": [{"parts": [{"text": " x "}]}]}}]}}
                      ]
                    }
                  ]
                }
                """);

        assertThat(project.source().bufferSize()).isEqualTo(4096);
        assertThat(project.templates()).hasSize(2);

        // A variant with no payload is a bare string, not an empty object.
        assertThat(project.templates().getFirst().match()).isInstanceOf(MatchExpression.Source.class);

        final Template row = project.templates().get(1);
        assertThat(row.match()).isEqualTo(
                new MatchExpression.Regex("^(\\w+)=(\\d+)$", new Template.RegexFlags(true, false), 1));
        assertThat(row.matchLimits().onlyMatch()).containsExactlyInAnyOrder(1, 3);

        // "Store" is the old spelling of a capture reference and still has to read.
        assertThat(row.captures().get(1).select()).isEqualTo(new CaptureSource.Select(
                new RefExpression(List.of(new RefPart.Capture("prev", 2, null)))));
    }

    @Test
    void rejectsWhatItDoesNotUnderstand() {
        assertThatThrownBy(() -> ProjectReader.read("{"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("not valid JSON");

        assertThatThrownBy(() -> ProjectReader.read("""
                {"name": "x", "version": 3, "templates": [], "colour": "blue"}
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Unknown field 'colour'");

        assertThatThrownBy(() -> ProjectReader.read("""
                {"name": "x", "version": 3, "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "t", "match": {"telepathy": {}}}]}
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Unknown match expression: telepathy");
    }

    @Test
    void carriesAReferenceExpressionsParts() {
        final Project project = ProjectReader.read("""
                {"name": "x", "version": 3, "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "t", "match": "all",
                   "body": [{"value-of": {"parts": [
                     {"text": "["},
                     {"capture": {"var_id": "heading", "group": 1,
                                  "match_index": {"index": 1, "is_offset": true}}},
                     {"text": "]"}]}}]}]}
                """);

        final OutputNode.ValueOf valueOf = (OutputNode.ValueOf) project.templates()
                .getFirst().body().getFirst();
        assertThat(valueOf.select().parts()).containsExactly(
                new RefPart.Text("["),
                new RefPart.Capture("heading", 1, new RefExpression.MatchIndex(1, true, false, null)),
                new RefPart.Text("]"));
    }
}
