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
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.Serving;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ServingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Ruling A46, design 01 §11.6: **the way in.**
///
/// Every other surface in the feature is reached because something went wrong — a learner gave up, a
/// draft awaits review, a rolling score fell through the floor. A rule serving at 0.93 is above every
/// threshold and nothing will ever raise it, so a person who wants it better has to be able to go and
/// find it. This is the list they find it in: ordered by the traffic each rule carries, because the
/// rule carrying the most streams is the one worth an hour.
class TestWhatIsServing {

    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    /// Two feeds' pipelines, and so two learners (A127).
    private static final Learner DOOR = Scenarios.LEARNER;
    private static final Learner LIFT = new Learner("pipeline-lift", Scenarios.LEARNER.elementId());

    /// What the stage learns is what the view shows. A rule promoted by a stream is in the list without
    /// anything else being written down: the learner it serves is the learner the view joins it to.
    @Test
    void whatTheStageLearnsIsWhatAPersonBrowses() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();

        final Scenarios.Processed learned = scenarios.process(learn(scenarios), doc, stream("DOOR-ACCESS", 1L));
        assertThat(learned.served()).describedAs("the stream it was learned on, served once the wake came")
                .isNotNull();

        final Serving.Page page = scenarios.serving.rules(List.of("doc-1"), null, 0L, 100);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.rules())
                .extracting(ServingRule::getLearner, ServingRule::getRecords)
                .describedAs("the promotion resets the learner, so its rolling memory starts from the streams it "
                             + "serves: the first is the one it was learned on, which the wake serves")
                .containsExactly(tuple(new LearnerRef(DOOR.pipelineUuid(), DOOR.elementId()),
                        learned.served().records()));
        assertThat(page.rules().get(0).getRollingScore())
                .describedAs("and a rolling score, from that stream").isNotNull();
        assertThat(page.rules().get(0).getPromotedScore())
                .describedAs("what it scored when it took over is what a rolling score is read against")
                .isNotNull();
        assertThat(page.rules().get(0).getFragment()).isNotNull();
    }

    /// Busiest first, and the score is what narrows the list rather than what arranges it. Sorting by
    /// score would put the worst rule in the installation at the top whether it carried one stream a
    /// month or a million.
    @Test
    void theBusiestRuleComesFirstAndTheScoreOnlyFilters() {
        final Scenarios scenarios = new Scenarios();
        scenarios.rules.append(DOOR, "doc-1", rule("busy"));
        scenarios.rules.append(LIFT, "doc-1", rule("quiet"));
        scenarios.states.scored(DOOR, "doc-1", 0.93, 40, 100);
        scenarios.states.scored(LIFT, "doc-1", 0.71, 3, 100);

        assertThat(scenarios.serving.rules(List.of("doc-1"), null, 0L, 100).rules())
                .extracting(ServingRule::getRuleUuid)
                .describedAs("the rule carrying forty records before the one carrying three, though it "
                             + "is scoring better")
                .containsExactly("busy", "quiet");
        assertThat(scenarios.serving.rules(List.of("doc-1"), 0.9, 0L, 100).rules())
                .extracting(ServingRule::getRuleUuid)
                .describedAs("and a threshold leaves out what is good enough")
                .containsExactly("quiet");
    }

    /// Each row says how much has been said to its learner (A46), so that a person can see whether the
    /// last hint was acted on before giving another.
    @Test
    void aRowSaysHowMuchHasBeenSaidToItsLearner() {
        final Scenarios scenarios = new Scenarios();
        scenarios.rules.append(DOOR, "doc-1", rule("busy"));
        scenarios.guidance.given(DOOR, "doc-1", "Timestamps are local, not UTC.", "jo");
        scenarios.guidance.given(DOOR, "doc-1", "Column four is a terminal id.", "sam");
        scenarios.guidance.given(LIFT, "doc-1", "Said to another learner.", "jo");

        assertThat(scenarios.serving.rules(List.of("doc-1"), null, 0L, 100).rules())
                .extracting(ServingRule::getGuidance)
                .containsExactly(2);
    }

    /// A draft is not in the list: a button offered on a row that cannot take it is a button that lies.
    @Test
    void whatCannotBeImprovedIsNotInTheList() {
        final Scenarios scenarios = new Scenarios();
        scenarios.rules.append(DOOR, "doc-1", rule("serving"));
        scenarios.rules.append(DOOR, "doc-1", rule("draft").copy().draft(true).build());

        assertThat(scenarios.serving.rules(List.of("doc-1"), null, 0L, 100).rules())
                .extracting(ServingRule::getRuleUuid)
                .describedAs("a draft is decided rather than improved (A25)")
                .containsExactly("serving");
    }

    /// The documents a person may read go into the query, as they do for the attempts: naming none
    /// answers nothing rather than everything.
    @Test
    void aDocumentNobodyNamedIsNotInTheAnswer() {
        final Scenarios scenarios = new Scenarios();
        scenarios.rules.append(DOOR, "doc-1", rule("ours"));
        scenarios.rules.append(LIFT, "doc-2", rule("theirs"));

        assertThat(scenarios.serving.rules(List.of("doc-1"), null, 0L, 100).rules())
                .extracting(ServingRule::getRuleUuid).containsExactly("ours");
        assertThat(scenarios.serving.rules(List.of(), null, 0L, 100).rules()).isEmpty();
    }

    private static RoutingRule rule(final String uuid) {
        return RoutingRule.builder()
                .uuid(uuid)
                .pipeline(stroom.docref.DocRef.builder()
                        .type("Pipeline")
                        .uuid(uuid + "-fragment")
                        .name(uuid + " fragment")
                        .build())
                .score(0.95)
                .promotedTimeMs(Scenarios.NOW.toEpochMilli())
                .build();
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
                // Named, because a document with no scorers scores every chain 1.0.
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.8, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream(final String feed, final long id) {
        return new Input(id, feed, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
