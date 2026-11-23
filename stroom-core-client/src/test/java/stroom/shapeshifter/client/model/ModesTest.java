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

    private static Template document(final List<OutputNode> body) {
        return new Template(ProjectText.newId(), "source", null, false, null, null, null,
                new MatchExpression.Source(), null, null, body, null, false);
    }

    @Test
    void aModeNothingDispatchesIntoIsDispatchedFromTheDocumentInOneStep() {
        // Design 47 C: every rung walked made a mode and then had to go to the document, open
        // its card and point it at the mode. The mode's pane offers that as one action, when
        // the document's own dispatch names no mode and nothing else needs it.
        final Project halfway = new Project("p", 5, null, List.of(
                document(List.of(apply(null))),
                template("pair", "kv", List.of())), null);
        final Modes.DispatchState before = Modes.dispatchState(halfway, "kv");
        assertThat(before.sites()).isZero();
        assertThat(before.offered()).isTrue();
        assertThat(before.reason()).isNull();
        final Project done = Modes.dispatchFromDocument(halfway, "kv");
        assertThat(Modes.applySiteCount(done, "kv")).isEqualTo(1);
        // The card is the card it was, pointed at the mode: what it selects is untouched
        final ApplyTemplates card = (ApplyTemplates) done.templates().get(0).body().get(0);
        assertThat(card.directive().select()).isEqualTo(apply(null).directive().select());
        assertThat(card.directive().dispatch()).isNull();
        final Modes.DispatchState after = Modes.dispatchState(done, "kv");
        assertThat(after.sites()).isEqualTo(1);
        assertThat(after.agreed()).isTrue();
        assertThat(after.offered()).isFalse();
    }

    @Test
    void theDocumentIsNotRepointedWhenThatWouldBeADecision() {
        // It already dispatches somewhere: a second mode wants a second dispatch, and where
        // that goes is the author's to say
        final Project elsewhere = new Project("p", 5, null, List.of(
                document(List.of(apply("kv"))),
                template("pair", "kv", List.of()),
                template("field", "fields", List.of())), null);
        final Modes.DispatchState second = Modes.dispatchState(elsewhere, "fields");
        assertThat(second.offered()).isFalse();
        assertThat(second.reason()).startsWith("the document dispatches into kv");
        assertThat(Modes.dispatchFromDocument(elsewhere, "fields")).isSameAs(elsewhere);

        // The root has templates of its own, which the mode-less dispatch is what runs
        final Project rooted = new Project("p", 5, null, List.of(
                document(List.of(apply(null))),
                template("line", null, List.of()),
                template("pair", "kv", List.of())), null);
        assertThat(Modes.dispatchState(rooted, "kv").offered()).isFalse();
        assertThat(Modes.dispatchState(rooted, "kv").reason()).startsWith("the default mode has templates of its own");
        assertThat(Modes.dispatchFromDocument(rooted, "kv")).isSameAs(rooted);

        // Nothing to point: no dispatch at all, or more than one that names no mode
        final Project none = new Project("p", 5, null, List.of(
                document(List.of()),
                template("pair", "kv", List.of())), null);
        assertThat(Modes.dispatchState(none, "kv").reason()).startsWith("the document has no apply-templates");
        final Project two = new Project("p", 5, null, List.of(
                document(List.of(apply(null), apply(null))),
                template("pair", "kv", List.of())), null);
        assertThat(Modes.dispatchState(two, "kv").reason()).startsWith("the document has 2 dispatches");

        // and no document to dispatch from
        final Project headless = new Project("p", 5, null, List.of(
                template("pair", "kv", List.of())), null);
        assertThat(Modes.dispatchState(headless, "kv").offered()).isFalse();
    }

    @Test
    void aModesStrategyIsSetOnEverySiteWhereTheSitesAgree() {
        final Project project = new Project("p", 5, null, List.of(
                document(List.of(apply("kv"), new If(new Condition.IsFirst(), List.of(apply("kv"))))),
                template("pair", "kv", List.of(apply("fields")))), null);
        final Modes.DispatchState state = Modes.dispatchState(project, "kv");
        assertThat(state.sites()).isEqualTo(2);
        assertThat(state.agreed()).isTrue();
        // Neither names one: they take the project's
        assertThat(state.dispatch()).isNull();
        final Project lexed = Modes.withDispatch(project, "kv", Dispatch.LEXER);
        assertThat(Modes.dispatchState(lexed, "kv").dispatch()).isEqualTo(Dispatch.LEXER);
        assertThat(Modes.dispatchOf(lexed, "kv")).isEqualTo(Dispatch.LEXER);
        // Another mode's site is left alone, and so is a template with no site of this mode
        assertThat(Modes.dispatchState(lexed, "fields").dispatch()).isNull();
        assertThat(lexed.templates().get(1)).isSameAs(project.templates().get(1));
        // Back to the project's
        assertThat(Modes.dispatchState(Modes.withDispatch(lexed, "kv", null), "kv").dispatch()).isNull();
    }

    @Test
    void sitesThatDifferHaveNoStrategyForThePaneToSet() {
        final Project mixed = new Project("p", 5, null, List.of(
                document(List.of(apply("kv", Dispatch.STRICT))),
                template("other", "x", List.of(apply("kv", Dispatch.LEXER))),
                template("pair", "kv", List.of())), null);
        final Modes.DispatchState state = Modes.dispatchState(mixed, "kv");
        assertThat(state.sites()).isEqualTo(2);
        assertThat(state.agreed()).isFalse();
        assertThat(state.dispatch()).isNull();
        assertThat(state.offered()).isFalse();
    }

    private static Project dispatching(final Dispatch dispatch) {
        return new Project("p", 5, null, List.of(
                template("root", null, List.of(apply("kv", dispatch))),
                template("pair", "kv", List.of()),
                template("junk", "kv", List.of())), null);
    }

    @Test
    void theTemplatesWithNoModeAreCalledDefault() {
        // Design 47 §7.3, ruled 2026-11-19: never `root`, which is an ordinary name
        assertThat(Modes.label(null)).isEqualTo("default");
        assertThat(Modes.label("root")).isEqualTo("root");
        assertThat(Modes.label("kv")).isEqualTo("kv");
    }

    @Test
    void aModeCannotBeCalledDefaultOrNothing() {
        assertThat(Modes.whyNotName("default")).contains("templates with no mode");
        assertThat(Modes.whyNotName("")).isEqualTo("A mode needs a name");
        assertThat(Modes.whyNotName("root")).isNull();
        assertThat(Modes.whyNotName("Default")).isNull();
    }

    @Test
    void aModesSitesAreNamedByTemplateAndPlace() {
        // Design 47 §7.2, item 16: the mode pane names where it is dispatched from, at any
        // depth, by the top-level instruction the site is or sits inside
        final Project project = new Project("p", 5, null, List.of(
                template("doc", null, List.of(new OutputNode.Text("x"), apply("kv"))),
                template("rec", "kv", List.of(new If(null, List.of(apply("cols"))))),
                template("col", "cols", List.of())), null);

        assertThat(Modes.dispatchSites(project, "kv")).containsExactly("doc");
        assertThat(Modes.dispatchSites(project, "cols")).containsExactly("rec");
        assertThat(Modes.dispatchSites(project, "nowhere")).isEmpty();
    }

    @Test
    void aPlaceIsNamedOnlyToTellTwoSitesInOneBodyApart() {
        // Design 48 C10: the position is the engine's words, and said only where it is needed
        final Project project = new Project("p", 5, null, List.of(
                template("doc", null, List.of(apply("kv"), new OutputNode.Text("x"), apply("kv"))),
                template("rec", "kv", List.of(apply("kv")))), null);

        assertThat(Modes.dispatchSites(project, "kv")).containsExactly("doc (body pos 1)", "doc (body pos 3)", "rec");
    }

    @Test
    void aModesDispatchNamesItsFirstSitesAndCountsTheRest() {
        // Five templates each dispatching into kv: three named, two counted, every one in the
        // tooltip's
        final Project project = new Project("p", 5, null, List.of(
                template("a", null, List.of(apply("kv"))), template("b", null, List.of(apply("kv"))),
                template("c", null, List.of(apply("kv"))), template("d", null, List.of(apply("kv"))),
                template("e", null, List.of(new OutputNode.Text("x"), apply("kv")))), null);
        final Modes.DispatchState state = Modes.dispatchState(project, "kv");

        assertThat(Modes.dispatchNote(state, false))
                .isEqualTo("dispatched from a, b, c and 2 more");
        assertThat(Modes.dispatchNote(state, true)).endsWith("e");
    }

    @Test
    void twoDispatchesInOneInstructionAreOneSiteAndCanStillDiffer() {
        // An if holding two dispatches into kv with different strategies: named once, and
        // the note says the dispatches differ without speaking of other sites
        final Project project = new Project("p", 5, null, List.of(
                template("rec", null, List.of(new If(null, List.of(apply("kv"), apply("kv", Dispatch.LEXER)))))),
                null);
        final Modes.DispatchState state = Modes.dispatchState(project, "kv");

        assertThat(state.sites()).isEqualTo(2);
        assertThat(state.named()).containsExactly("rec");
        assertThat(Modes.dispatchNote(state, false))
                .isEqualTo("dispatched from rec — the dispatches differ: set it on each card");
    }

    @Test
    void aNewDispatchGoesToTheOneModeNothingDispatchesInto() {
        // Design 47 I: rung 5 adds header_columns and goes straight to the card dispatching it
        final Project project = new Project("p", 5, null, List.of(
                template("pair", "kv", List.of()),
                template("doc", null, List.of(apply("kv")))), null);

        assertThat(Modes.onlyUndispatched(project, List.of("kv", "header_columns"), "kv"))
                .isEqualTo("header_columns");
        // Two waiting is no one answer, and none is none
        assertThat(Modes.onlyUndispatched(project, List.of("kv", "a", "b"), null)).isNull();
        assertThat(Modes.onlyUndispatched(project, List.of("kv"), null)).isNull();
        assertThat(Modes.onlyUndispatched(null, List.of("a"), null)).isNull();
    }

    @Test
    void aNewDispatchNeverGoesToItsOwnTemplatesMode() {
        // A template in data_columns, opened before anything dispatches into its mode: a card
        // there dispatching into data_columns would recurse until the engine stopped it
        final Project project = new Project("p", 5, null, List.of(
                template("data_column", "data_columns", List.of()),
                template("doc", null, List.of(apply("kv")))), null);

        assertThat(Modes.onlyUndispatched(project, List.of("kv", "data_columns"), "data_columns")).isNull();
        // Another template's card may start there
        assertThat(Modes.onlyUndispatched(project, List.of("kv", "data_columns"), "kv")).isEqualTo("data_columns");
    }

    @Test
    void aModeNamedDefaultIsNeverShownAsTheTemplatesWithNoMode() {
        // A project written elsewhere can hold one; the panel headed both `mode: default` and
        // merged them, and the pickers listed `default` twice
        assertThat(Modes.label("default")).isEqualTo("default (reserved)");
        assertThat(Modes.label("default")).isNotEqualTo(Modes.label(null));
        assertThat(Modes.label("Default")).isEqualTo("Default");
    }

    @Test
    void removingAModeSaysItsDispatchesGoToTheDefaultMode() {
        // `root` is an ordinary mode name now: the question must not say the dispatches go there
        assertThat(Modes.removeQuestion("kv", 1))
                .isEqualTo("Remove mode 'kv'? 1 apply-templates site still dispatches into it; those"
                           + " become dispatches into mode default, and the messages will say so.")
                .doesNotContain("root");
        assertThat(Modes.removeQuestion("kv", 2)).contains("2 apply-templates sites still dispatch into it");
    }
}
