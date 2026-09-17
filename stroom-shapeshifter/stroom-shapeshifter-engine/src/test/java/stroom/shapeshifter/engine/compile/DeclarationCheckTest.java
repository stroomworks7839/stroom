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

import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.engine.ProjectReader;
import stroom.shapeshifter.engine.Shapeshifter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 35 §12 phase 2: a name is declared once, with a type, and every binding and reference
 * is judged against the declarations. The refusals name the template and the name, because a
 * misspelt name would otherwise read as absent for ever.
 */
class DeclarationCheckTest {

    private static RefExpression ref(final String var) {
        return new RefExpression(List.of(new RefExpression.RefPart.Capture(var, 0, null)));
    }

    private static Declaration scalar(final String name) {
        return new Declaration(name, Declaration.Type.SCALAR);
    }

    /** A source dispatching to one line template, whose declarations and body are the test's. */
    private static Project project(final List<Declaration> onSource,
                                   final List<Declaration> onLine,
                                   final List<OutputNode> body) {
        final Template line = new Template(
                UUID.randomUUID().toString(), "line", "doc", false, null, List.of(), onLine,
                new MatchExpression.Regex("([^\n]*)\n", null, 0),
                new Template.MatchLimits(0, -1, null),
                List.of(new CaptureBinding("seed", new CaptureBinding.CaptureSource.Group(1), null)),
                body,
                null, false);
        final Template source = new Template(
                UUID.randomUUID().toString(), "source", null, false, null, List.of(), onSource,
                new MatchExpression.Source(),
                new Template.MatchLimits(0, -1, null), List.of(),
                List.of(new OutputNode.ApplyTemplates(new OutputNode.ApplyDirective(
                        new RefExpression(List.of(new RefExpression.RefPart.Capture(null, 0, null))),
                        "doc", List.of(), OutputNode.ApplyDirective.DEFAULT_MAX_DEPTH, false,
                        null))),
                null, false);
        return new Project("t", 5, Project.SourceConfig.defaults(), List.of(source, line));
    }

    @Test
    void declaredOnTheTemplateItIsBoundInIsFine() {
        assertThatCode(() -> Shapeshifter.compile(project(List.of(), List.of(scalar("seed")),
                List.of(new OutputNode.ValueOf(ref("seed"))))))
                .doesNotThrowAnyException();
    }

    /** Resolution is dynamic (design 35 §3): a declaration on the source covers the line. */
    @Test
    void declaredOnTheSourceIsFine() {
        assertThatCode(() -> Shapeshifter.compile(project(List.of(scalar("seed")), List.of(),
                List.of(new OutputNode.ValueOf(ref("seed"))))))
                .doesNotThrowAnyException();
    }

    @Test
    void bindingOfAnUndeclaredNameIsRefusedByName() {
        assertThatThrownBy(() -> Shapeshifter.compile(project(List.of(), List.of(),
                List.of(new OutputNode.ValueOf(ref("seed"))))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Template 'line' binds 'seed'")
                .hasMessageContaining("no declaration names");
    }

    @Test
    void readOfAnUndeclaredNameIsRefusedByName() {
        assertThatThrownBy(() -> Shapeshifter.compile(project(List.of(), List.of(scalar("seed")),
                List.of(new OutputNode.ValueOf(ref("sede"))))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Template 'line' reads 'sede'")
                .hasMessageContaining("no declaration names");
    }

    /** Declared, but nothing writes it: the older refusal still stands behind the new one. */
    @Test
    void readOfADeclaredNameNothingWritesIsRefused() {
        assertThatThrownBy(() -> Shapeshifter.compile(project(List.of(scalar("never")),
                List.of(scalar("seed")),
                List.of(new OutputNode.ValueOf(ref("never"))))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("reads 'never'")
                .hasMessageContaining("nothing writes");
    }

    @Test
    void secondDeclarationOfANameIsRefusedNamingBothTemplates() {
        assertThatThrownBy(() -> Shapeshifter.compile(project(List.of(scalar("seed")),
                List.of(scalar("seed")),
                List.of(new OutputNode.ValueOf(ref("seed"))))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Template 'line' declares 'seed'")
                .hasMessageContaining("template 'source' already declares");
    }

    /** A loop's {@code as} and a parameter are declarations in place; they owe no other. */
    @Test
    void parameterIsADeclarationInPlace() {
        final Template.ParamDecl depth = new Template.ParamDecl("depth", "0");
        final Template line = new Template(
                UUID.randomUUID().toString(), "line", "doc", false, null, List.of(depth), List.of(scalar("seed")),
                new MatchExpression.Regex("([^\n]*)\n", null, 0),
                new Template.MatchLimits(0, -1, null),
                List.of(new CaptureBinding("seed", new CaptureBinding.CaptureSource.Group(1), null)),
                List.of(new OutputNode.ValueOf(ref("depth"))),
                null, false);
        final Project base = project(List.of(), List.of(), List.of());
        final Project withParam = new Project("t", 5, Project.SourceConfig.defaults(),
                List.of(base.templates().getFirst(), line));
        assertThatCode(() -> Shapeshifter.compile(withParam)).doesNotThrowAnyException();
    }

    /**
     * The phase's gate, pinned: a native fixture compiles as shipped, and the same fixture with
     * its declarations removed fails at compile time with a message naming the reference.
     */
    @Test
    void fixtureWithItsDeclarationsRemovedFailsNamingTheReference() throws IOException {
        final String json = Files.readString(
                Path.of("src/test/resources/fixtures/native/001_csv_with_header/project.json"));
        assertThatCode(() -> Shapeshifter.compile(ProjectReader.read(json))).doesNotThrowAnyException();
        final String undeclared = json.replaceAll("\"declarations\":\\s*\\[[^\\]]*\\],?", "");
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(undeclared)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("no declaration names")
                .hasMessageMatching("(?s)Template '\\w+' (binds|reads) '\\w+'.*");
    }

    /** A map is read by key: a reference to the whole of one could only fail when written. */
    @Test
    void readingAMapWholeIsRefused() {
        final CaptureBinding pairs = new CaptureBinding("pairs", new CaptureBinding.CaptureSource.KeyValue(
                new RefExpression(List.of(new RefExpression.RefPart.Capture(null, 1, null))),
                new RefExpression(List.of(new RefExpression.RefPart.Capture(null, 1, null)))), null);
        final Template line = new Template(
                UUID.randomUUID().toString(), "line", "doc", false, null, List.of(),
                List.of(new Declaration("pairs", Declaration.Type.MAP)),
                new MatchExpression.Regex("([^\n]*)\n", null, 0),
                new Template.MatchLimits(0, -1, null),
                List.of(pairs),
                List.of(new OutputNode.ValueOf(ref("pairs"))),
                null, false);
        final Project base = project(List.of(), List.of(), List.of());
        final Project whole = new Project("t", 5, Project.SourceConfig.defaults(),
                List.of(base.templates().getFirst(), line));
        assertThatThrownBy(() -> Shapeshifter.compile(whole))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("reads 'pairs', which is declared as a map")
                .hasMessageContaining("get or last");
        final Project byKey = new Project("t", 5, Project.SourceConfig.defaults(),
                List.of(base.templates().getFirst(), new Template(
                        line.id(), line.name(), line.mode(), false, null, List.of(), line.declarations(),
                        line.match(), line.matchLimits(), line.captures(),
                        List.of(new OutputNode.ValueOf(new RefExpression(
                                List.of(new RefExpression.RefPart.Accessor(
                                        RefExpression.RefPart.Accessor.Kind.GET,
                                        new RefExpression(List.of(new RefExpression.RefPart.Capture("pairs", 0, null))),
                                        RefExpression.text("k"), null, null))))),
                        null, false)));
        assertThatCode(() -> Shapeshifter.compile(byKey)).doesNotThrowAnyException();
    }

}
