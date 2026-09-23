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
import stroom.shapeshifter.ai.stage.Serving;
import stroom.shapeshifter.ai.stage.Stage;
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
/// Every other surface in the feature is reached because something went wrong — a shape was given up, a
/// draft awaits review, a rolling score fell through the floor. A rule serving at 0.93 is above every
/// threshold and nothing will ever raise it, so a person who wants it better has to be able to go and
/// find it. This is the list they find it in: ordered by the traffic each rule carries, because the
/// rule carrying the most streams is the one worth an hour.
class TestWhatIsServing {

    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String DOOR = "Feed=DOOR-ACCESS|Type=Raw Events";
    private static final String LIFT = "Feed=LIFT|Type=Raw Events";

    /// What the stage learns is what the view shows. A rule promoted by a stream is in the list without
    /// anything else being written down: the shape it names is the shape the view joins it to.
    @Test
    void whatTheStageLearnsIsWhatAPersonBrowses() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.rules);
        final ShapeshifterAiDoc doc = doc();

        stage.run(doc, stream("DOOR-ACCESS", 1L));

        final Serving.Page page = scenarios.serving.rules(List.of("doc-1"), null, 0L, 100);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.rules())
                .extracting(ServingRule::getShapeId, ServingRule::getRecords)
                .describedAs("a rule promoted a moment ago has served nothing yet — the promotion "
                             + "resets the shape, so its rolling memory starts from the streams it "
                             + "serves rather than the one it was learned from — and the list shows it "
                             + "all the same: a binding nobody has seen work is the one most worth a "
                             + "look")
                .containsExactly(tuple(DOOR, 0));
        assertThat(page.rules().get(0).getRollingScore())
                .describedAs("and no rolling score at all rather than a zero, which would read as a "
                             + "rule scoring badly")
                .isNull();
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
        scenarios.rules.append("doc-1", rule("busy", DOOR));
        scenarios.rules.append("doc-1", rule("quiet", LIFT));
        scenarios.shapes.scored("doc-1", DOOR, 0.93, 40, 100);
        scenarios.shapes.scored("doc-1", LIFT, 0.71, 3, 100);

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

    /// Each row says how much has been said about its shape (A46), so that a person can see whether the
    /// last hint was acted on before giving another.
    @Test
    void aRowSaysHowMuchHasBeenSaidAboutItsShape() {
        final Scenarios scenarios = new Scenarios();
        scenarios.rules.append("doc-1", rule("busy", DOOR));
        scenarios.guidance.given("doc-1", DOOR, "Timestamps are local, not UTC.", "jo");
        scenarios.guidance.given("doc-1", DOOR, "Column four is a terminal id.", "sam");

        assertThat(scenarios.serving.rules(List.of("doc-1"), null, 0L, 100).rules())
                .extracting(ServingRule::getGuidance)
                .containsExactly(2);
    }

    /// The three kinds of rule the view does not show, each for its own reason. A button offered on a
    /// row that cannot take it is a button that lies.
    @Test
    void whatCannotBeImprovedIsNotInTheList() {
        final Scenarios scenarios = new Scenarios();
        scenarios.rules.append("doc-1", rule("serving", DOOR));
        scenarios.rules.append("doc-1", rule("draft", LIFT).copy().draft(true).build());
        scenarios.rules.append("doc-1", RoutingRule.builder().uuid("reserved").shapeId(LIFT).build());
        scenarios.rules.append("doc-1", rule("by-hand", null));

        assertThat(scenarios.serving.rules(List.of("doc-1"), null, 0L, 100).rules())
                .extracting(ServingRule::getRuleUuid)
                .describedAs("a draft is decided rather than improved (A25), a reserved rule binds "
                             + "nothing, and a rule written by hand came from no shape")
                .containsExactly("serving");
    }

    /// The documents a person may read go into the query, as they do for the ledger: naming none
    /// answers nothing rather than everything.
    @Test
    void aDocumentNobodyNamedIsNotInTheAnswer() {
        final Scenarios scenarios = new Scenarios();
        scenarios.rules.append("doc-1", rule("ours", DOOR));
        scenarios.rules.append("doc-2", rule("theirs", DOOR));

        assertThat(scenarios.serving.rules(List.of("doc-1"), null, 0L, 100).rules())
                .extracting(ServingRule::getRuleUuid).containsExactly("ours");
        assertThat(scenarios.serving.rules(List.of(), null, 0L, 100).rules()).isEmpty();
    }

    private static RoutingRule rule(final String uuid, final String shapeId) {
        return RoutingRule.builder()
                .uuid(uuid)
                .shapeId(shapeId)
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
