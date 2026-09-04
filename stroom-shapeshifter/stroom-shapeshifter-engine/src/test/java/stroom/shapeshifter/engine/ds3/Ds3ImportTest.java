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

package stroom.shapeshifter.engine.ds3;

import stroom.shapeshifter.engine.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;
import stroom.shapeshifter.engine.config.Template;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the DS3 import preserves, and what it refuses.
 *
 * <p>The golden fixtures prove the whole pipeline; these tests pin the pieces the fixtures do
 * not happen to exercise — {@code advance}, the {@code @}-form in data attributes, a group's
 * {@code ignoreErrors} in the shapes that used to lose it, and the strictness that makes an
 * unimportable configuration say so instead of importing wrong.
 */
class Ds3ImportTest {

    // -----------------------------------------------------------------------------------
    // What survives the conversion
    // -----------------------------------------------------------------------------------

    @Test
    void importsRegexAdvance() {
        final Project project = Ds3Migration.importXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" version="3.0">
                  <regex pattern="(\\w+) (\\w+)" advance="1">
                    <data name="word" value="$1"/>
                  </regex>
                </dataSplitter>
                """);
        final MatchExpression.Regex regex = project.templates().stream()
                .map(Template::match)
                .filter(MatchExpression.Regex.class::isInstance)
                .map(MatchExpression.Regex.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(regex.advance()).isEqualTo(1);
    }

    @Test
    void advanceDefaultsToTheEndOfTheWholeMatch() {
        final Project project = Ds3Migration.importXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" version="3.0">
                  <regex pattern="(a)">
                    <data name="x" value="$1"/>
                  </regex>
                </dataSplitter>
                """);
        final MatchExpression.Regex regex = project.templates().stream()
                .map(Template::match)
                .filter(MatchExpression.Regex.class::isInstance)
                .map(MatchExpression.Regex.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(regex.advance()).isZero();
    }

    @Test
    void singleUnnamedGroupKeepsItsIgnoreErrors() {
        // The one-unnamed-group collapse must not eat the flag: with ignoreErrors set, the
        // group survives as a dispatched level whose directive carries it.
        final Project project = Ds3Migration.importXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" version="3.0">
                  <split delimiter="\\n">
                    <group ignoreErrors="true">
                      <regex pattern="(a)">
                        <data name="x" value="$1"/>
                      </regex>
                    </group>
                  </split>
                </dataSplitter>
                """);
        assertThat(directives(project))
                .anyMatch(OutputNode.ApplyDirective::ignoreErrors);
    }

    @Test
    void atReferencesInDataAttributesAreReferencesNotText() {
        // '@foo.1' means group 1 of the variable foo, in both attributes — not the six
        // characters '@foo.1'.
        final Project project = Ds3Migration.importXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" version="3.0">
                  <split delimiter=",">
                    <var id="foo"/>
                    <data name="@foo.1" value="@foo.1"/>
                  </split>
                </dataSplitter>
                """);
        final List<RefPart> parts = refParts(project);
        assertThat(parts)
                .noneMatch(part -> part instanceof RefPart.Text text && text.value().contains("@foo"));
        assertThat(parts)
                .filteredOn(part -> part instanceof RefPart.Capture capture
                                    && "foo".equals(capture.varId()))
                .isNotEmpty();
    }

    @Test
    void varComputedFromAnotherVarReadsAtTheCurrentMatch() {
        // '$a$1' names which group of a to *store*; reading it back must be indexed by the
        // parent's match count, or every match would read whatever a stored last.
        final Project project = Ds3Migration.importXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" version="3.0">
                  <split delimiter=",">
                    <var id="a"/>
                    <var id="b" value="$a$1"/>
                    <data name="x" value="@b"/>
                  </split>
                </dataSplitter>
                """);
        assertThat(variableReads(project, "b")).containsExactly(
                new RefPart.Capture("a", 0, new MatchIndex(0, false, false, "__match_count")));
    }

    @Test
    void varInsideAGroupWithExpressionsReadsAtTheCurrentMatch() {
        // The same rule on the group() conversion path.
        final Project project = Ds3Migration.importXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" version="3.0">
                  <split delimiter="\\n">
                    <group value="$1">
                      <regex pattern="(x)">
                        <data name="y" value="$1"/>
                      </regex>
                      <var id="v" value="$q$1"/>
                    </group>
                  </split>
                </dataSplitter>
                """);
        assertThat(variableReads(project, "v")).containsExactly(
                new RefPart.Capture("q", 0, new MatchIndex(0, false, false, "__match_count")));
    }

    @Test
    void nestedGroupKeepsItsValueAndIgnoreErrors() {
        // A group inside a group-with-expressions is still a group: its value selects what is
        // dispatched and its ignoreErrors gates that level.
        final Project project = Ds3Migration.importXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" version="3.0">
                  <split delimiter="\\n">
                    <group value="$1">
                      <regex pattern="(a)">
                        <data name="x" value="$1"/>
                      </regex>
                      <group value="$2" ignoreErrors="true">
                        <regex pattern="(b)">
                          <data name="y" value="$1"/>
                        </regex>
                      </group>
                    </group>
                  </split>
                </dataSplitter>
                """);
        assertThat(directives(project)).anyMatch(directive ->
                directive.ignoreErrors()
                && directive.select().parts().equals(List.of(new RefPart.Capture(null, 2, null))));
    }

    @Test
    void theSameConfigurationGivesTheSameIdentifiers() {
        final String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" version="3.0">
                  <split delimiter="\\n" maxMatch="1">
                    <data name="header" value="$1"/>
                  </split>
                  <split delimiter="\\n">
                    <group value="$1">
                      <regex pattern="(a)">
                        <data name="x" value="$1"/>
                      </regex>
                    </group>
                  </split>
                </dataSplitter>
                """;
        final List<UUID> first = Ds3Migration.importXml(xml).templates().stream()
                .map(Template::id).toList();
        final List<UUID> second = Ds3Migration.importXml(xml).templates().stream()
                .map(Template::id).toList();
        assertThat(first).isEqualTo(second);
        assertThat(first).doesNotHaveDuplicates();
    }

    // -----------------------------------------------------------------------------------
    // What is refused, loudly
    // -----------------------------------------------------------------------------------

    @Test
    void refusesAnUnknownAttribute() {
        assertThatThrownBy(() -> Ds3Migration.importXml("""
                <dataSplitter>
                  <regex pattern="(a)" maxmatch="5">
                    <data name="x" value="$1"/>
                  </regex>
                </dataSplitter>
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Unknown attribute 'maxmatch' on <regex>");
    }

    @Test
    void refusesMatchOrderByName() {
        assertThatThrownBy(() -> Ds3Migration.importXml("""
                <dataSplitter>
                  <split delimiter="," matchOrder="sequence">
                    <data name="x" value="$1"/>
                  </split>
                </dataSplitter>
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("matchOrder")
                .hasMessageContaining("dispatch");
    }

    @Test
    void acceptsTheDocumentedVersionAndNamespaceAttributes() {
        // Every real DS3 document carries these; strictness must not turn them away.
        final Project project = Ds3Migration.importXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd"
                              version="3.0">
                  <split delimiter=",">
                    <data name="x" value="$1"/>
                  </split>
                </dataSplitter>
                """);
        assertThat(project.templates()).isNotEmpty();
    }

    @Test
    void refusesADocumentThatIsNotADataSplitter() {
        assertThatThrownBy(() -> Ds3Migration.importXml("<split delimiter=\",\"/>"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("dataSplitter");
    }

    @Test
    void refusesANestedDataSplitter() {
        assertThatThrownBy(() -> Ds3Migration.importXml("""
                <dataSplitter>
                  <dataSplitter/>
                </dataSplitter>
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("document element");
    }

    @Test
    void refusesANonExpressionAtTheRoot() {
        // It would be silently dropped otherwise, which is worse than refusing.
        assertThatThrownBy(() -> Ds3Migration.importXml("""
                <dataSplitter>
                  <var id="x"/>
                  <split delimiter=",">
                    <data name="y" value="$1"/>
                  </split>
                </dataSplitter>
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("<var>");
    }

    @Test
    void refusesANonPositiveBufferSize() {
        assertThatThrownBy(() -> Ds3Migration.importXml("""
                <dataSplitter bufferSize="0">
                  <split delimiter=",">
                    <data name="x" value="$1"/>
                  </split>
                </dataSplitter>
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("bufferSize");
        assertThatThrownBy(() -> Ds3Migration.importXml("""
                <dataSplitter bufferSize="-3">
                  <split delimiter=",">
                    <data name="x" value="$1"/>
                  </split>
                </dataSplitter>
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("bufferSize");
    }

    // -----------------------------------------------------------------------------------
    // Walking the converted project
    // -----------------------------------------------------------------------------------

    private static List<OutputNode> nodes(final Project project) {
        final List<OutputNode> all = new ArrayList<>();
        for (final Template template : project.templates()) {
            template.body().forEach(node -> walk(node, all));
        }
        return all;
    }

    private static void walk(final OutputNode node, final List<OutputNode> into) {
        into.add(node);
        switch (node) {
            case OutputNode.Variable variable -> variable.body().forEach(n -> walk(n, into));
            case OutputNode.If condition -> condition.then().forEach(n -> walk(n, into));
            case OutputNode.Element element -> element.body().forEach(n -> walk(n, into));
            case OutputNode.Attribute attribute -> attribute.body().forEach(n -> walk(n, into));
            default -> {
                // The migration emits nothing else with children.
            }
        }
    }

    private static List<OutputNode.ApplyDirective> directives(final Project project) {
        return nodes(project).stream()
                .filter(OutputNode.ApplyTemplates.class::isInstance)
                .map(node -> ((OutputNode.ApplyTemplates) node).directive())
                .toList();
    }

    /** Every {@link RefPart} anywhere in the project — bodies and capture bindings both. */
    private static List<RefPart> refParts(final Project project) {
        final List<RefPart> parts = new ArrayList<>();
        for (final OutputNode node : nodes(project)) {
            switch (node) {
                case OutputNode.ValueOf valueOf -> parts.addAll(valueOf.select().parts());
                case OutputNode.Translate translate ->
                        translate.select().forEach(select -> parts.addAll(select.parts()));
                case OutputNode.Trim trim -> trim.select().forEach(select -> parts.addAll(select.parts()));
                case OutputNode.ApplyTemplates apply ->
                        parts.addAll(apply.directive().select().parts());
                default -> {
                    // No expressions to collect.
                }
            }
        }
        for (final Template template : project.templates()) {
            template.captures().forEach(capture -> {
                if (capture.select() instanceof CaptureSource.Select select) {
                    parts.addAll(select.select().parts());
                }
            });
        }
        return parts;
    }

    /** The parts read by the body-level variable of the given name. */
    private static List<RefPart> variableReads(final Project project, final String name) {
        return nodes(project).stream()
                .filter(node -> node instanceof OutputNode.Variable variable
                                && name.equals(variable.name()))
                .map(OutputNode.Variable.class::cast)
                .flatMap(variable -> variable.body().stream())
                .filter(OutputNode.ValueOf.class::isInstance)
                .map(node -> ((OutputNode.ValueOf) node).select())
                .flatMap(select -> select.parts().stream())
                .toList();
    }
}
