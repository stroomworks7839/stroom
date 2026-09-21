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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.engine.PatternPrint;
import stroom.shapeshifter.engine.ProjectReader;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.output.XmlByteSink;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 44 §3: a project defines pattern parts once and a tree names them with {@code ref},
 * ahead of the standard library; the parts ride with the configuration, compile into the
 * naming template's plan, and print as their definitions.
 */
class ProjectLibraryTest {

    private static String project(final String patterns, final String match) {
        return """
                {"name": "t", "version": 5,
                 "patterns": %s,
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "row",
                   "match": {"pattern": %s},
                   "body": [{"value-of": {"parts": [{"text": "<r k=\\""}, {"capture": {"label": "k"}},
                                                    {"text": "\\" v=\\""}, {"capture": {"label": "v"}},
                                                    {"text": "\\"/>"}]}}]}]}
                """.formatted(patterns, match);
    }

    private static String run(final String json, final String input) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shapeshifter.runWhole(Shapeshifter.compile(ProjectReader.read(json)),
                input.getBytes(StandardCharsets.UTF_8), new XmlByteSink(out));
        return out.toString(StandardCharsets.UTF_8);
    }

    private static final String PARTS = """
            {"KEY": {"take_while": "[a-z]"},
             "PAIR": {"sequence": [{"ref": "KEY", "label": "k"}, {"tag": "="},
                                    {"take_while": "[0-9]", "label": "v"}]}}
            """;

    @Test
    void refToAProjectPartMatchesAsThePartInlinedWould() {
        final String composed = project(PARTS, """
                {"sequence": [{"ref": "PAIR"}, {"tag": ";"}]}
                """);
        final String inlined = project("{}", """
                {"sequence": [{"take_while": "[a-z]", "label": "k"}, {"tag": "="},
                              {"take_while": "[0-9]", "label": "v"}, {"tag": ";"}]}
                """);
        assertThat(run(composed, "ab=12;cd=3;")).isEqualTo(run(inlined, "ab=12;cd=3;"))
                .isEqualTo("<r k=\"ab\" v=\"12\"/><r k=\"cd\" v=\"3\"/>");
    }

    @Test
    void partsReachOneAnotherAndTheProjectComesFirst() {
        // KEY is a project part named from inside PAIR, itself named from the tree; the
        // template's labels resolve through both. The standard library is still there beneath.
        final String json = project("""
                {"KEY": {"take_while": "[a-z]"},
                 "PAIR": {"sequence": [{"ref": "KEY", "label": "k"}, {"tag": "="},
                                        {"ref": "digits", "label": "v"}]}}
                """, """
                {"sequence": [{"ref": "PAIR"}, {"tag": ";"}]}
                """);
        assertThat(run(json, "ab=12;")).isEqualTo("<r k=\"ab\" v=\"12\"/>");
    }

    @Test
    void projectPartMayNotTakeAStandardLibraryName() {
        final String json = project("""
                {"digits": {"take_while": "[0-9]"}}
                """, """
                {"tag": "x"}
                """);
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(json)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Pattern 'digits' in the project's library has the standard library's name");
    }

    @Test
    void anUnknownNameAndACycleAreRefusedAtCompile() {
        final String unknown = project("{}", """
                {"ref": "NOWHERE"}
                """);
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(unknown)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Template 'row'")
                .hasMessageContaining("no matcher named 'NOWHERE'");
        final String cycle = project("""
                {"A": {"sequence": [{"tag": "a"}, {"ref": "B"}]},
                 "B": {"ref": "A"}}
                """, """
                {"ref": "A"}
                """);
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(cycle)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("refers to itself via A -> B -> A");
    }

    @Test
    void patternsRoundTripThroughTheReaderInOrder() {
        final Project read = ProjectReader.read(project(PARTS, "{\"ref\": \"PAIR\"}"));
        assertThat(read.patterns().keySet()).containsExactly("KEY", "PAIR");
        assertThat(read.patterns().get("KEY")).isEqualTo(new PatternNode.TakeWhile("[a-z]", 1,
                PatternNode.Repeat.UNBOUNDED));
        final Project again = ProjectReader.read(ProjectReader.write(read));
        assertThat(again).isEqualTo(read);
        assertThat(ProjectReader.write(ProjectReader.read(project("{}", "{\"tag\": \"x\"}"))))
                .as("an empty library is not written").doesNotContain("patterns");
    }

    @Test
    void refPrintsAsItsDefinitionAndACyclicOneSaysSo() {
        final Map<String, PatternNode> patterns = ProjectReader.read(project(PARTS, "{\"ref\": \"PAIR\"}"))
                .patterns();
        final PatternNode tree = new PatternNode.Sequence(List.of(new PatternNode.Ref("PAIR"),
                new PatternNode.Tag(";")));
        assertThat(PatternPrint.print(tree, null, patterns)).isEqualTo("(?<k>[a-z]+)=(?<v>[0-9]+);");
        assertThatThrownBy(() -> PatternPrint.print(new PatternNode.Ref("PAIR"), null))
                .as("without the project, a project name is unknown")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAIR");
        assertThatThrownBy(() -> PatternPrint.print(new PatternNode.Ref("A"), null,
                Map.of("A", new PatternNode.Ref("B"), "B", new PatternNode.Ref("A"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refers to itself via A -> B -> A");
    }
}
