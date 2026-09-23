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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.RegressionSet.Accepted;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Ruling A46: **a way in for a rule that is not failing.** A rule serving at 0.93 is above every
/// threshold, nothing has flagged it, and until now there was no door marked "make this better".
///
/// An improvement does not wait for traffic. The records the rule was accepted on are kept per rule
/// (A18) with the score each achieved, so an attempt has both a sample to learn from and the bar to
/// beat without a stream arriving — a feed that ships once a day can be improved at eleven in the
/// morning. The incumbent serves throughout and a candidate takes over only through the ordinary gate.
class TestImproveARuleThatIsServing {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    /// A transform that makes nothing of the records it is given: it compiles and runs, so what it
    /// fails is the gate that counts what came out.
    private static final String YIELDS_NOTHING = """
            <xsl:stylesheet xmlns="event-logging:3" xpath-default-namespace="records:2"
                            xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
              <xsl:template match="records"><Events/></xsl:template>
            </xsl:stylesheet>""";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String SHAPE = "Feed=DOOR-ACCESS|Type=Raw Events";

    @Test
    void aBetterCandidateReboundsTheRuleAndTheMessageIsCarriedIntoTheAsking() {
        final Scenarios scenarios = new Scenarios();
        final Script learning = learn(scenarios);
        final Stage stage = scenarios.stage(document -> learning, scenarios.rules);
        final ShapeshifterAiDoc doc = doc();

        final StageRun learned = stage.run(doc, stream());
        final RoutingRule bound = scenarios.rules.forDocument("doc-1").get(0);
        assertThat(learned.bindings()).isNotNull();
        assertThat(bound.getShapeId())
                .describedAs("a learned rule records the shape it was learned for, which is what an "
                             + "improvement claims its attempt against")
                .isEqualTo(SHAPE);

        // Asked for out of the blue: no stream arrives, and the regression set is the sample.
        final Script improving = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final Stage improver = scenarios.stage(document -> improving, scenarios.rules);

        final StageRun improved = improver.improve(doc, bound.getUuid(),
                "The fourth column is a terminal id, not a user.", "jo");

        assertThat(improved.decision())
                .describedAs("a candidate that is no worse on what the rule was accepted on takes over")
                .isInstanceOf(Rebound.class);
        assertThat(scenarios.rules.forDocument("doc-1"))
                .describedAs("the same rule, rebound: it keeps its uuid and its history (§7.3 rule 2)")
                .hasSize(1)
                .first()
                .satisfies(rule -> assertThat(rule.getUuid()).isEqualTo(bound.getUuid()));
        assertThat(scenarios.guidance.standing("doc-1", SHAPE))
                .describedAs("the message is guidance for the shape, so it is carried into this attempt "
                             + "and stands for the relearning after it")
                .extracting(given -> given.message() + " (" + given.author() + ")")
                .containsExactly("The fourth column is a terminal id, not a user. (jo)");
    }

    @Test
    void aCandidateThatIsWorseOnAnAcceptedRecordIsRefusedAndTheIncumbentServesOn() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.rules);
        final ShapeshifterAiDoc doc = doc();
        stage.run(doc, stream());
        final RoutingRule bound = scenarios.rules.forDocument("doc-1").get(0);

        // A bar no candidate can reach. What the gate is about is the *comparison* — a candidate that
        // is worse on a record the rule was accepted on is not an improvement — and saying so with a
        // score rather than by writing a deliberately mediocre stylesheet keeps the test about the gate.
        scenarios.regressionSet.discard(bound.getUuid());
        scenarios.regressionSet.accept(bound.getUuid(),
                List.of(new Accepted(CSV.input(), 1.5, List.of())), doc.getRegressionCap());

        final Stage improver = scenarios.stage(document -> learn(scenarios), scenarios.rules);
        final StageRun improved = improver.improve(doc, bound.getUuid(), null, "jo");

        assertThat(improved.decision())
                .describedAs("worse on a record the rule was accepted on is not an improvement, whatever "
                             + "else it does (A18)")
                .isInstanceOf(Kept.class);
        assertThat(((Kept) improved.decision()).reason()).contains("accepted on an earlier stream");
        assertThat(scenarios.rules.forDocument("doc-1").get(0).getPipeline())
                .describedAs("and the incumbent is still bound, having served throughout")
                .isEqualTo(bound.getPipeline());
    }

    @Test
    void whatCannotBeImprovedSaysSoRatherThanGuessing() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.rules);
        final ShapeshifterAiDoc doc = doc();
        stage.run(doc, stream());
        final RoutingRule bound = scenarios.rules.forDocument("doc-1").get(0);

        assertThatThrownBy(() -> stage.improve(doc, "not-a-rule", null, "jo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a rule of this document");

        scenarios.rules.append("doc-1", RoutingRule.builder().uuid("by-hand")
                .pipeline(bound.getPipeline()).build());
        assertThatThrownBy(() -> stage.improve(doc, "by-hand", null, "jo"))
                .describedAs("a rule an operator wrote by hand did not come from a shape, and there is "
                             + "nothing to learn again")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("did not come from a learned shape");

        scenarios.regressionSet.discard(bound.getUuid());
        assertThatThrownBy(() -> stage.improve(doc, bound.getUuid(), null, "jo"))
                .describedAs("without a record it was accepted on there is nothing to learn from and "
                             + "nothing to beat: an improvement judged on nothing is a promotion in "
                             + "disguise")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no accepted records");
    }

    /// The four states in which an improvement must refuse rather than proceed, each of which would
    /// undo something somebody else decided.
    @Test
    void anImprovementRefusesWhatOtherDecisionsHaveSettled() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.rules);
        final ShapeshifterAiDoc doc = doc();
        stage.run(doc, stream());
        final RoutingRule bound = scenarios.rules.forDocument("doc-1").get(0);

        scenarios.rules.replace("doc-1", bound.copy().pinned(true).build());
        assertThatThrownBy(() -> stage.improve(doc, bound.getUuid(), null, "jo"))
                .describedAs("a pin freezes a rule (§7.3 rule 2), and an improvement rebinds it — which "
                             + "is exactly what the pin is there to stop")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pinned");
        scenarios.rules.replace("doc-1", bound.copy().pinned(false).build());

        assertThatThrownBy(() -> stage.improve(doc().copy().learningMode(LearningMode.DISABLED).build(),
                bound.getUuid(), null, "jo"))
                .describedAs("the kill switch is a kill switch: a button that asked anyway would be a "
                             + "way round it")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");

        scenarios.shapes.awaitReview("doc-1", SHAPE, "some-draft");
        assertThatThrownBy(() -> stage.improve(doc, bound.getUuid(), null, "jo"))
                .describedAs("one draft per shape: a second would overwrite the pointer that says which "
                             + "is awaiting review, leaving the first undecidable")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already awaiting review");
        scenarios.shapes.reset("doc-1", SHAPE);

        // And a shape marked for relearning has been learned again by hand: the mark is spent.
        scenarios.shapes.markForRelearning("doc-1", SHAPE, "drifting");
        stage.improve(doc, bound.getUuid(), null, "jo");
        assertThat(scenarios.shapes.relearnReason("doc-1", SHAPE))
                .describedAs("or the next stream would relearn the rule somebody has only now improved")
                .isEmpty();
    }

    /// The model is asked about the feed it is improving. There is no stream here to read the headers
    /// off, so they come from the shape the rule recorded (A29: shown means bound).
    @Test
    void theQuestionsKnowWhichFeedIsBeingImproved() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.rules);
        final ShapeshifterAiDoc doc = doc();
        stage.run(doc, stream());
        final RoutingRule bound = scenarios.rules.forDocument("doc-1").get(0);

        final Script asking = learn(scenarios);
        final StageRun improved = scenarios.stage(document -> asking, scenarios.rules)
                .improve(doc, bound.getUuid(), null, "jo");

        assertThat(improved.transcript()).isNotEmpty();
        assertThat(asking.asked()).first()
                .describedAs("the question carries the shape's own headers, not an unnamed sample")
                .isInstanceOfSatisfying(Chain.class, chain -> assertThat(chain.sample().headers())
                        .containsEntry("Feed", FEED)
                        .containsEntry("Type", "Raw Events"));
    }

    private Script learn(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(1)
                .promotionFloor(0.85)
                // Named, because a document with no scorers scores every chain 1.0 and no candidate
                // could ever be worse than what it would replace.
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.8, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream() {
        return new Input(1L, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
