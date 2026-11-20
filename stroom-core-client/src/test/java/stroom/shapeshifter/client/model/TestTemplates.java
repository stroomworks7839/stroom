/*
 * Copyright 2026 Crown Copyright
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

import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.Declaration.Type;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.MatchLimits;
import stroom.shapeshifter.config.Template.ParamDecl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestTemplates {

    private static Template template(final String name, final List<Declaration> declarations) {
        return new Template(ProjectText.newId(), name, null, false, null, null, declarations,
                new MatchExpression.Regex("a", null, 0), null, null, List.of(), null, false);
    }

    @Test
    void aCaptureIsOfferedTheNamesDeclaredOnOtherTemplates() {
        // Rung 5 (design 46 §6.4a): `heading` is declared on the document and bound in
        // header_column, which declares nothing — the engine accepts it, so the editor offers it.
        final Template document = template("document", List.of(new Declaration("heading", Type.LIST)));
        final Template column = template("header_column", List.of());
        final Project project = new Project("p", 5, null, List.of(document, column), null);

        assertThat(Templates.declaredNames(project, column)).containsExactly("heading");
    }

    @Test
    void theListsAreTheDeclaredNamesThatHoldAList() {
        // Rung 5: heading on the document is a list, record_body on a record is not, and a
        // set or a map has no entry a match count says
        final Template document = template("document", List.of(
                new Declaration("heading", Type.LIST), new Declaration("seen", Type.SET)));
        final Template record = template("data_record", List.of(
                new Declaration("record_body", Type.SCALAR), new Declaration("cells", Type.LIST),
                new Declaration("heading", Type.LIST)));
        final Project project = new Project("p", 5, null, List.of(document, record), null);

        assertThat(Templates.declaredLists(project, record)).containsExactly("cells", "heading");
        assertThat(Templates.declaredLists(null, null)).isEmpty();
    }

    @Test
    void aSelectNamingAListIsOfferedWhichEntryItReads() {
        final List<String> lists = List.of("heading");
        // design 44 Q14: the nth of these goes with the nth of those
        assertThat(Templates.subscriptsAfter(lists, "heading", true))
                .containsExactly("[matchCount()]", "[index()]");
        assertThat(Templates.subscriptsAfter(lists, " heading ", true))
                .containsExactly("[matchCount()]", "[index()]");
    }

    @Test
    void indexIsOfferedOnlyWhereAnIterationSetsIt() {
        // Outside one, heading[index()] would read the first entry every time
        assertThat(Templates.subscriptsAfter(List.of("heading"), "heading", false))
                .containsExactly("[matchCount()]");
    }

    @Test
    void aSelectThatIsNotAListsPlainNameIsOfferedNothing() {
        final List<String> lists = List.of("heading");
        // Says which entry already
        assertThat(Templates.subscriptsAfter(lists, "heading[matchCount()]", true)).isEmpty();
        assertThat(Templates.subscriptsAfter(lists, "heading[2]", true)).isEmpty();
        // Not a list, or not a name
        assertThat(Templates.subscriptsAfter(lists, "record_body", true)).isEmpty();
        assertThat(Templates.subscriptsAfter(lists, "$1", true)).isEmpty();
        assertThat(Templates.subscriptsAfter(lists, "head", true)).isEmpty();
        assertThat(Templates.subscriptsAfter(lists, "", true)).isEmpty();
        assertThat(Templates.subscriptsAfter(lists, null, true)).isEmpty();
        // Two names joined are not one name
        assertThat(Templates.subscriptsAfter(lists, "heading heading", true)).isEmpty();
    }

    @Test
    void anOfferedSubscriptReadsBackAsTheMatchIndexItSays() {
        // What the offer writes is what the Select's own reader takes for a match index, so
        // taking it can never leave the card unsaveable
        for (final String subscript : Templates.subscriptsAfter(List.of("heading"), "heading", true)) {
            final String spelt = "heading" + subscript;
            assertThat(Instructions.ref(Instructions.read(spelt))).as(spelt).isEqualTo(spelt);
        }
    }

    @Test
    void theTemplatesOwnNamesComeFirstAndEachNameOnce() {
        final Template document = template("document", List.of(
                new Declaration("heading", Type.LIST), new Declaration("shared", Type.SCALAR)));
        final Template record = template("data_record", List.of(
                new Declaration("record_body", Type.SCALAR), new Declaration("shared", Type.SCALAR)));
        final Project project = new Project("p", 5, null, List.of(document, record), null);

        assertThat(Templates.declaredNames(project, record))
                .containsExactly("record_body", "shared", "heading");
    }

    @Test
    void nothingDeclaredAndNothingToAskAreBothEmpty() {
        final Template bare = template("bare", List.of());

        assertThat(Templates.declaredNames(new Project("p", 5, null, List.of(bare), null), bare)).isEmpty();
        assertThat(Templates.declaredNames(null, null)).isEmpty();
    }

    @Test
    void aTitleTakenLateKeepsWhatWasDoneToTheBodyMeanwhile() {
        // Design 47 §7.8, finding 1: the title is written onto the template as it is by then
        final Template was = template("pair", List.of());
        final Template retitled = Templates.withIdentity(was, "pairs", "kv", false,
                List.of(new ParamDecl("depth", "1")), "utf-8", true);
        final Template bodyChanged = Templates.withBody(was, List.of(new OutputNode.Text("<pair/>")));

        final Template taken = Templates.withIdentityOf(bodyChanged, retitled);

        assertThat(taken.name()).isEqualTo("pairs");
        assertThat(taken.mode()).isEqualTo("kv");
        assertThat(taken.param()).containsExactly(new ParamDecl("depth", "1"));
        assertThat(taken.encoding()).isEqualTo("utf-8");
        assertThat(taken.ignoreErrors()).isTrue();
        assertThat(taken.body()).isEqualTo(bodyChanged.body());
        assertThat(taken.id()).isEqualTo(was.id());
    }

    @Test
    void theRoleIsNotPartOfAnIdentity() {
        final Template was = template("pair", List.of());
        final Template advanceOnly = Templates.withRole(was, true);

        assertThat(Templates.withIdentityOf(advanceOnly, was).consume()).isTrue();
        assertThat(Templates.sameIdentity(advanceOnly, was)).isTrue();
    }

    @Test
    void anIdentityIsTheSameUntilATitleFieldChanges() {
        final Template was = template("pair", List.of());

        assertThat(Templates.sameIdentity(Templates.withBody(was, List.of(new OutputNode.Text("x"))), was))
                .isTrue();
        assertThat(Templates.sameIdentity(Templates.withIdentity(was, "other", null, false, null, null,
                false), was)).isFalse();
        assertThat(Templates.sameIdentity(Templates.withIdentity(was, "pair", "kv", false, null, null,
                false), was)).isFalse();
        assertThat(Templates.sameIdentity(Templates.withIdentity(was, "pair", null, false, null, "ascii",
                false), was)).isFalse();
    }

    @Test
    void aNameIsTakenOnlyByAnotherTemplate() {
        // Design 47 §7.8, finding 4: a call names a template, so two of a name cannot be told apart
        final Template pair = template("pair", List.of());
        final Template junk = template("junk_line", List.of());
        final Project project = new Project("p", 5, null, List.of(pair, junk), null);

        assertThat(Templates.nameTaken(project, "junk_line", pair.id())).isTrue();
        assertThat(Templates.nameTaken(project, "pair", pair.id())).isFalse();
        assertThat(Templates.nameTaken(project, "pairs", pair.id())).isFalse();
        assertThat(Templates.nameTaken(project, "pair", null)).isTrue();
        assertThat(Templates.nameTaken(null, "pair", null)).isFalse();
    }

    @Test
    void aRowSaysHowManyMatchesItsLimitAllows() {
        // Design 47 §7.3, ruled 2026-11-19: what tells apart two templates with one pattern
        assertThat(Templates.limitNote(limited(new MatchLimits(0, 1, null)))).isEqualTo("first only");
        assertThat(Templates.limitNote(limited(new MatchLimits(0, 3, null)))).isEqualTo("at most 3");
        assertThat(Templates.limitNote(limited(MatchLimits.unlimited()))).isNull();
        assertThat(Templates.limitNote(limited(null))).isNull();
        // The fewest and the only matches are not how many: the strip says those
        assertThat(Templates.limitNote(limited(new MatchLimits(2, MatchLimits.UNLIMITED, Set.of(1))))).isNull();
    }

    @Test
    void aNameNoTemplateDeclaresIsOfferedToBeDeclared() {
        // Design 47 I: a variable naming record_body, which nothing declares yet
        final List<String> known = List.of("heading", "index");
        assertThat(Templates.undeclared(known, "record_body")).isTrue();
        assertThat(Templates.undeclared(known, "_x1")).isTrue();
        // Declared already, or a function: nothing to offer
        assertThat(Templates.undeclared(known, "heading")).isFalse();
        assertThat(Templates.undeclared(known, "index")).isFalse();
        // Not a name anyone could declare
        assertThat(Templates.undeclared(known, "")).isFalse();
        assertThat(Templates.undeclared(known, "1x")).isFalse();
        assertThat(Templates.undeclared(known, "a b")).isFalse();
        assertThat(Templates.undeclared(known, "$1")).isFalse();
    }

    @Test
    void declaringAScalarAddsItOnce() {
        final Template plain = template("data_record", List.of());
        final Template declared = Templates.withScalar(plain, "record_body");

        assertThat(declared.declarations()).containsExactly(new Declaration("record_body", Type.SCALAR));
        assertThat(Templates.withScalar(declared, "record_body")).isSameAs(declared);
    }

    @Test
    void aRowWhoseLimitAllowsNoMatchSaysItNeverMatches() {
        assertThat(Templates.limitNote(limited(new MatchLimits(0, 0, null)))).isEqualTo("never matches");
    }

    @Test
    void aTemplateThatEatsItsMatchesHasNoLimitToShow() {
        // The engine never applies a maximum to an eater (Level), so its role switched keeps
        // limits that say nothing about it
        final Template eater = Templates.withRole(limited(new MatchLimits(0, 1, null)), true);
        assertThat(eater.matchLimits().maxMatch()).isEqualTo(1);
        assertThat(Templates.limitNote(eater)).isNull();
    }

    @Test
    void theGuardAndLimitsAreSaidInALine() {
        // The workbench's title bar for them, which says what they hold when folded away
        final Template plain = limited(MatchLimits.unlimited());
        assertThat(Templates.describeGuardAndLimits(plain)).isEqualTo("no guard · no limits");
        assertThat(Templates.unguardedAndUnlimited(plain)).isTrue();
        assertThat(Templates.unguardedAndUnlimited(limited(null))).isTrue();

        final Template limitedOnly = limited(new MatchLimits(2, 5, Set.of(3)));
        assertThat(Templates.describeGuardAndLimits(limitedOnly)).isEqualTo("no guard · min 2 · max 5 · only [3]");
        assertThat(Templates.unguardedAndUnlimited(limitedOnly)).isFalse();
        // Any one limit is a limit
        assertThat(Templates.unguardedAndUnlimited(limited(new MatchLimits(1, MatchLimits.UNLIMITED, null))))
                .isFalse();
        assertThat(Templates.unguardedAndUnlimited(limited(new MatchLimits(0, MatchLimits.UNLIMITED, Set.of(1)))))
                .isFalse();

        final Template guarded = Templates.withGuard(plain, GuardClause.write(
                List.of(new GuardClause("status", GuardClause.Op.EQ, "400", null)), GuardClause.Join.ALL));
        assertThat(Templates.describeGuardAndLimits(guarded)).startsWith("status ").endsWith(" · no limits");
        assertThat(Templates.unguardedAndUnlimited(guarded)).isFalse();
    }

    @Test
    void limitsAreSaidInALine() {
        assertThat(Templates.describeLimits(null)).isEqualTo("no limits");
        assertThat(Templates.describeLimits(MatchLimits.unlimited())).isEqualTo("no limits");
        assertThat(Templates.describeLimits(new MatchLimits(0, 1, null))).isEqualTo("max 1");
        assertThat(Templates.describeLimits(new MatchLimits(2, MatchLimits.UNLIMITED, Set.of()))).isEqualTo("min 2");
    }

    @Test
    void aSequenceThatMatchedNowhereIsTriedAgainOneShorterAtATime() {
        final PatternNode tree = new PatternNode.Sequence(List.of(new PatternNode.TakeWhile("[a-z]", 1, -1),
                new PatternNode.Tag(":"), new PatternNode.TakeUntil("\n", false)));
        final Template template = Templates.withMatch(limited(null), new MatchExpression.Pattern(tree));
        final Project project = new Project("p", 5, null, List.of(template), Map.of());
        final Project runs = Templates.prefixExperiment(project, template);
        // Longest first, so the first to match anywhere is the furthest the sequence got there
        assertThat(runs.templates()).extracting(Template::id)
                .containsExactly(Templates.prefixId(template, 2), Templates.prefixId(template, 1));
        final PatternNode two = ((MatchExpression.Pattern) runs.templates().get(0).match()).node();
        assertThat(PatternNodes.children(two)).hasSize(2);
        // Labelled as the whole tree is tried, so a node's stretch is under the same name
        assertThat(PatternNodes.get(two, new int[]{1}))
                .isEqualTo(new PatternNode.Labelled(new PatternNode.Tag(":"), PatternNodes.trialLabel(new int[]{1}),
                        null));
        // Only a sequence of two or more has anywhere to stop
        final Template one = Templates.withMatch(limited(null), new MatchExpression.Pattern(new PatternNode.Tag("x")));
        assertThat(Templates.prefixExperiment(project, one)).isNull();
        assertThat(Templates.prefixExperiment(project, limited(null))).isNull();
    }

    @Test
    void aTreeIsTriedWithEveryNodeLabelled() {
        final PatternNode tree = new PatternNode.Sequence(List.of(new PatternNode.Tag("a"), new PatternNode.Tag("b")));
        final Template template = Templates.withMatch(limited(null), new MatchExpression.Pattern(tree));
        final Project tried = Templates.treeExperiment(new Project("p", 5, null, List.of(template), Map.of()),
                template);
        final PatternNode node = ((MatchExpression.Pattern) tried.templates().get(0).match()).node();
        assertThat(node).isEqualTo(PatternNodes.labelEvery(tree));
    }

    private static Template limited(final MatchLimits limits) {
        return new Template(ProjectText.newId(), "t", null, false, null, null, List.of(),
                new MatchExpression.Regex("a", null, 0), limits, null, List.of(), null, false);
    }
}
