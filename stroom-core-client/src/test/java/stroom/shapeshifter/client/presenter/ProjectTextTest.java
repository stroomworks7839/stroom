/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.config.Cast;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.json.JsonNumber;
import stroom.shapeshifter.config.json.JsonObject;
import stroom.shapeshifter.config.json.JsonString;
import stroom.shapeshifter.config.json.ProjectJson;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client's edge onto the project text, and the with-copies the Design tab edits through:
 * every edit the tab can make prints and reads back equal, on the JVM, before GWT sees it.
 */
class ProjectTextTest {

    @Test
    void newProjectPrintsAndReadsBack() {
        final Project empty = ProjectText.empty("New doc");
        final Project again = ProjectText.parse(ProjectText.print(empty));
        assertThat(again).isEqualTo(empty);
        assertThat(again.name()).isEqualTo("New doc");
        assertThat(again.version()).isEqualTo(ProjectText.CURRENT_VERSION);
    }

    @Test
    void newProjectsStartWithTheirDocumentTemplate() {
        // Design 44 §5m: a project without one cannot open a root element, so it cannot emit a
        // well-formed document — and nothing would say so. The body is the input loop, which is
        // what an author wraps a root element around.
        final Project empty = ProjectText.empty("New doc");
        assertThat(empty.templates()).hasSize(1);
        final Template document = empty.templates().getFirst();
        assertThat(document.match()).isInstanceOf(MatchExpression.Source.class);
        assertThat(document.mode()).isNull();
        assertThat(document.body()).singleElement().isInstanceOf(OutputNode.ApplyTemplates.class);
    }

    @Test
    void newIdsAreTheShapeTheReaderInsistsOn() {
        for (int i = 0; i < 50; i++) {
            final String id = ProjectText.newId();
            assertThat(id).hasSize(36).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }
    }

    @Test
    void templateBuiltByTheEditorRoundTrips() {
        final Template created = Templates.create("kv", "fields", true);
        final Template edited = Templates.withCaptures(
                Templates.withDeclarations(
                        Templates.withMatch(created, new MatchExpression.Regex("(?<k>\\w+)=(?<v>\\S+)", null, 0)),
                        List.of(new Declaration("k", Declaration.Type.SCALAR),
                                new Declaration("seen", Declaration.Type.MAP,
                                        List.of(new Declaration.Entry("a", "b"))))),
                List.of(new CaptureBinding("k", new CaptureSource.Group(1), null),
                        new CaptureBinding("v", new CaptureSource.Label("v"), Cast.INTEGER)));
        final Project project = new Project("p", ProjectText.CURRENT_VERSION, null, List.of(edited), null);
        final Project again = ProjectText.parse(ProjectText.print(project));
        assertThat(again).isEqualTo(project);
        assertThat(again.templates().get(0).id()).isEqualTo(created.id());
    }

    @Test
    void templateAsTheDialogFirstWritesItPrints() {
        // New Template, name typed, OK: a blank regex and nothing else yet, into an empty document.
        final Template blank = Templates.withIdentity(Templates.create("", null, true), "first", null, true,
                List.of(), null, false);
        final Project project = new Project("p", ProjectText.CURRENT_VERSION, null, List.of(blank), null);
        final Project again = ProjectText.parse(ProjectText.print(project));
        assertThat(again).isEqualTo(project);
    }

    @Test
    void matchAndPatternNodeHaveTheirOwnTextForms() {
        final PatternNode.Sequence node = new PatternNode.Sequence(List.of(
                new PatternNode.Tag("GET "),
                new PatternNode.Labelled(new PatternNode.TakeUntil(" ", false), "path", null)));
        assertThat(ProjectText.parsePatternNode(ProjectText.printPatternNode(node))).isEqualTo(node);

        final MatchExpression parts = new MatchExpression.Parts(List.of(
                new MatchExpression.MatchPart.Pattern(node),
                new MatchExpression.MatchPart.Take(new MatchExpression.Length.Literal(4), "rest")));
        assertThat(ProjectText.parseMatch(ProjectText.printMatch(parts))).isEqualTo(parts);
        assertThat(Templates.describe(parts)).isEqualTo("pattern sequence (2) · take 4 as rest");
        assertThat(Templates.describe(node.items().get(1))).isEqualTo("take_until \" \" → path");
    }

    @Test
    void theCaptureDialogBuildsWhatTheReaderReads() {
        // The dialog composes the wire form and hands it to the one reader (CaptureEditPresenter.write).
        final JsonObject node = new JsonObject();
        node.put("name", "when");
        node.put("select", new JsonObject().put("group", JsonNumber.of(2)));
        node.put("as", "date");
        final CaptureBinding capture = ProjectJson.readCapture(node);
        assertThat(capture).isEqualTo(new CaptureBinding("when", new CaptureSource.Group(2), Cast.DATE));

        final JsonObject bad = new JsonObject();
        bad.put("name", "x");
        bad.put("select", new JsonObject().put("label", new JsonString("")));
        assertThat(ProjectJson.readCapture(bad).select()).isEqualTo(new CaptureSource.Label(""));
    }

    @Test
    void syntaxErrorNamesItsPlace() {
        assertThatThrownBy(() -> ProjectText.parse("{\"name\": \"p\", \"version\": 5, \"templates\": [}"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("line 1");
    }
}
