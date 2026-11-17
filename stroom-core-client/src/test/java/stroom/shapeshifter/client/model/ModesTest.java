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

package stroom.shapeshifter.client.model;

import stroom.shapeshifter.client.component.Regex;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.Dispatch;
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

    private static ApplyTemplates apply(final String mode, final Dispatch dispatch) {
        return new ApplyTemplates(new ApplyDirective(ProjectJson.readRefOrName("x"), mode, null,
                ApplyDirective.DEFAULT_MAX_DEPTH, false, dispatch));
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
            template("r", "route", List.of())), null);

    @Test
    void aModeWithTemplatesAndNoSiteIsTheHalfFinishedEdit() {
        // The panel says "nothing dispatches into this" on exactly this predicate (design 46
        // §5.4, ruled): a mode holding templates that nothing dispatches into never runs, and
        // the author is halfway through a two-part edit rather than mistaken.
        final Project halfway = new Project("p", 5, null, List.of(
                template("root", null, List.of()),
                template("pair", "kv", List.of())), null);
        assertThat(Modes.templateCount(halfway, "kv")).isEqualTo(1);
        assertThat(Modes.applySiteCount(halfway, "kv")).isZero();
        // And finishing the edit settles it
        final Project dispatched = new Project("p", 5, null, List.of(
                template("root", null, List.of(apply("kv"))),
                template("pair", "kv", List.of())), null);
        assertThat(Modes.applySiteCount(dispatched, "kv")).isEqualTo(1);
    }

    @Test
    void modesAreTheTemplatesAndTheSitesInFirstAppearanceOrder() {
        assertThat(Modes.of(PROJECT)).containsExactly("fields", "route");
        assertThat(Modes.templateCount(PROJECT, "fields")).isEqualTo(1);
        assertThat(Modes.applySiteCount(PROJECT, "fields")).isEqualTo(2);
        assertThat(Modes.applySiteCount(PROJECT, "route")).isEqualTo(1);
        final Project orphan = new Project("p", 5, null, List.of(template("root", null, List.of(apply("gone")))),
                null);
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
                template("root", null, List.of(new If(new Condition.IsFirst(), List.of(apply("gone")))))), null);
        final Project removed = Modes.removeSites(orphan, "gone");
        assertThat(Modes.of(removed)).isEmpty();
        final If i = (If) removed.templates().get(0).body().get(0);
        assertThat(((ApplyTemplates) i.then().get(0)).directive().mode()).isNull();
    }

    @Test
    void aModesOrderMeansWhateverItsDispatchSiteSays() {
        // The one finding where a mistake is silent rather than laborious (design 46 §10 W4):
        // the same ordered list means priority, a tie-break, or nothing, and the strategy that
        // decides is a picker on a card in another pane. The panel says which, from this.
        assertThat(Modes.dispatchOf(dispatching(Dispatch.STRICT), "kv")).isEqualTo(Dispatch.STRICT);
        assertThat(Modes.dispatchOf(dispatching(Dispatch.LEXER), "kv")).isEqualTo(Dispatch.LEXER);
        assertThat(Modes.dispatchOf(dispatching(Dispatch.CLASSIFY), "kv"))
                .isEqualTo(Dispatch.CLASSIFY);
    }

    @Test
    void aSiteThatNamesNoStrategyTakesTheProjects() {
        // Blank means "the project's" (InstructionEdit's own help), so the panel must resolve it
        // the way the engine does rather than reading the card alone.
        final Project project = new Project("p", 5,
                new Project.SourceConfig(20_000, false, "utf-8", Dispatch.LEXER, false, 100_000),
                List.of(template("root", null, List.of(apply("kv", null))),
                        template("pair", "kv", List.of())), null);
        assertThat(Modes.dispatchOf(project, "kv")).isEqualTo(Dispatch.LEXER);
    }

    @Test
    void aModeWithNoSiteHasNoStrategyToReport() {
        final Project halfway = new Project("p", 5, null, List.of(
                template("root", null, List.of()),
                template("pair", "kv", List.of())), null);
        // Null, and applySiteCount is what tells the caller why: the panel has its own note for
        // this case and must not also claim the order means nothing.
        assertThat(Modes.dispatchOf(halfway, "kv")).isNull();
        assertThat(Modes.applySiteCount(halfway, "kv")).isZero();
    }

    @Test
    void sitesThatDisagreeHaveNoOneAnswer() {
        // No configuration in the corpus does this — of 80 modes with a site, six have more than
        // one and none of those mixes strategies — but a sentence that is sometimes false is
        // worse than none, so the panel is told there is no answer rather than given the first.
        final Project mixed = new Project("p", 5, null, List.of(
                template("root", null, List.of(apply("kv", Dispatch.STRICT))),
                template("other", null, List.of(apply("kv", Dispatch.LEXER))),
                template("pair", "kv", List.of())), null);
        assertThat(Modes.applySiteCount(mixed, "kv")).isEqualTo(2);
        assertThat(Modes.dispatchOf(mixed, "kv")).isNull();
    }

    @Test
    void theRootLevelIsEnteredByTheRunWithTheProjectsOwnStrategy() {
        // No card dispatches into the root group, so its strategy is the project's. It matters:
        // library_parts and progressive_len_records have no source template at all and their
        // root mode holds the templates the run enters directly (design 46 §6.3).
        final Project project = new Project("p", 5,
                new Project.SourceConfig(20_000, false, "utf-8", Dispatch.CLASSIFY, false, 100_000),
                List.of(template("a", null, List.of()), template("b", null, List.of())), null);
        assertThat(Modes.dispatchOf(project, null)).isEqualTo(Dispatch.CLASSIFY);
    }

    private static Project dispatching(final Dispatch dispatch) {
        return new Project("p", 5, null, List.of(
                template("root", null, List.of(apply("kv", dispatch))),
                template("pair", "kv", List.of()),
                template("junk", "kv", List.of())), null);
    }
}
