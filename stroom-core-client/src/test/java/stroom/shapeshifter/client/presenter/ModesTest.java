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

import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.config.OutputNode.ApplyTemplates;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Element;
import stroom.shapeshifter.config.OutputNode.If;
import stroom.shapeshifter.config.OutputNode.WhenBranch;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.json.ProjectJson;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Modes exist through templates and apply sites; the editor's rewrites reach both, at any depth. */
class ModesTest {

    private static ApplyTemplates apply(final String mode) {
        return new ApplyTemplates(new ApplyDirective(ProjectJson.readRefOrName("x"), mode, null,
                ApplyDirective.DEFAULT_MAX_DEPTH, false, null));
    }

    private static Template template(final String name, final String mode, final List<OutputNode> body) {
        return new Template(ProjectText.newId(), name, mode, false, null, null, null,
                new MatchExpression.Regex("a", null, 0), null, null, body, null, false);
    }

    private static final Project PROJECT = new Project("p", 5, null, List.of(
            template("root", null, List.of(
                    apply("fields"),
                    new If(new Condition.IsFirst(), List.of(apply("route"))),
                    new Choose(List.of(new WhenBranch(new Condition.IsLast(), List.of(
                            new Element("e", null, false, List.of(apply("fields")))))), List.of()))),
            template("kv", "fields", List.of()),
            template("r", "route", List.of())));

    @Test
    void modesAreTheTemplatesAndTheSitesInFirstAppearanceOrder() {
        assertThat(Modes.of(PROJECT)).containsExactly("fields", "route");
        assertThat(Modes.templateCount(PROJECT, "fields")).isEqualTo(1);
        assertThat(Modes.applySiteCount(PROJECT, "fields")).isEqualTo(2);
        assertThat(Modes.applySiteCount(PROJECT, "route")).isEqualTo(1);
        final Project orphan = new Project("p", 5, null, List.of(template("root", null, List.of(apply("gone")))));
        assertThat(Modes.of(orphan)).containsExactly("gone");
        assertThat(Modes.templateCount(orphan, "gone")).isZero();
    }

    @Test
    void renameMovesTheTemplatesAndFollowsEverySiteIntoBranches() {
        final Project renamed = Modes.rename(PROJECT, "fields", "pairs");
        assertThat(Modes.of(renamed)).containsExactly("pairs", "route");
        assertThat(Modes.templateCount(renamed, "pairs")).isEqualTo(1);
        assertThat(Modes.applySiteCount(renamed, "pairs")).isEqualTo(2);
        assertThat(Modes.applySiteCount(renamed, "fields")).isZero();
        // Untouched sites and templates keep their identity, so the rewrite is minimal.
        assertThat(renamed.templates().get(2)).isEqualTo(PROJECT.templates().get(2));
        assertThat(renamed.templates().get(1).id()).isEqualTo(PROJECT.templates().get(1).id());
        // And the whole thing still prints and reads: the rewrite stays inside the vocabulary.
        assertThat(ProjectText.parse(ProjectText.print(renamed))).isEqualTo(renamed);
    }

    @Test
    void removingAnEmptyModeTurnsItsSitesIntoRootDispatches() {
        final Project orphan = new Project("p", 5, null, List.of(
                template("root", null, List.of(new If(new Condition.IsFirst(), List.of(apply("gone")))))));
        final Project removed = Modes.removeSites(orphan, "gone");
        assertThat(Modes.of(removed)).isEmpty();
        final If i = (If) removed.templates().get(0).body().get(0);
        assertThat(((ApplyTemplates) i.then().get(0)).directive().mode()).isNull();
    }
}
