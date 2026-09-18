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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Drafted;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.ai.state.InMemoryLedger;
import stroom.shapeshifter.ai.state.InMemoryReprocessing;
import stroom.shapeshifter.ai.state.InMemoryReprocessing.Request;
import stroom.shapeshifter.shared.DialogueShape;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
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

/**
 * Design 02 §5, scenario 22 (A25): in review mode a learned rule is a draft the router does not bind;
 * streams of the shape are sentinelled onto the ledger naming it; Approve is the promotion and releases
 * the ledger; Reject gives the shape up with the reason. And the case the catalogue leaves implicit: a
 * relearned candidate under review is a draft behind the incumbent, which keeps serving until a person
 * decides.
 */
class TestScenario22ReviewMode {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String FOUR_FIELDS_AND_ALARMS = Scenarios.resource("csv-fields-and-alarms.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DOC = "doc-1";
    private static final String SHAPE = "Feed=DOOR-ACCESS|Type=Raw Events";

    private static ShapeshifterAiDoc review() {
        return ShapeshifterAiDoc.builder()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .dialogueShape(DialogueShape.TARGET_FIRST)
                .promotionMode(PromotionMode.REVIEW)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(5)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Script learning(final Scenarios scenarios, final String splitter) {
        return scenarios.script(splitter, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(splitter))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    private static String lines(final int records, final int alarmEvery) {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < records; i++) {
            final String time = "2020-06-17T08:" + String.format("%02d", i) + ":00.000Z";
            text.append(alarmEvery > 0 && i % alarmEvery == alarmEvery - 1
                    ? time + ",ALARM,door " + i + " forced\n"
                    : time + ",user" + i + ",office,logon\n");
        }
        return text.toString();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), data);
    }

    private static int events(final String output) {
        return output.split("<Event>", -1).length - 1;
    }

    /**
     * The first two streams of the shape: one learns the draft, the next is refused by it.
     */
    private static StageRun drafted(final Scenarios scenarios) {
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.stage(script).run(review(), stream(1, lines(7, 0)));
        script.verifyExhausted();

        assertThat(first.decision()).isInstanceOf(Drafted.class);
        final RoutingRule draft = ((Drafted) first.decision()).rule();
        assertThat(draft.isDraft()).isTrue();
        assertThat(draft.getPromotedTimeMs()).isNull();
        assertThat(draft.getUuid()).isNotNull();
        assertThat(first.doc().getRoutingTable()).containsExactly(draft);
        assertThat(first.output()).describedAs("nothing is produced under a draft").isNull();
        assertThat(first.bindings()).isNull();
        assertThat(scenarios.stores.pipelines.list()).describedAs("the fragment is written all the same").hasSize(1);
        assertThat(scenarios.ledger.rows()).extracting(InMemoryLedger.Row::inputId).containsExactly(1L);
        assertThat(scenarios.ledger.rows().get(0).reason())
                .contains("Awaiting review").contains(draft.getUuid()).contains(SHAPE);
        assertThat(scenarios.reprocessing.requests()).describedAs("a draft releases nothing").isEmpty();

        final Script silent = Script.of();
        final StageRun second = scenarios.stage(silent).run(first.doc(), stream(2, lines(4, 0)));
        assertThat(second.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) second.decision()).reason())
                .startsWith("Awaiting review: draft rule " + draft.getUuid())
                .contains(draft.getPipeline().getName())
                .contains(SHAPE);
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.ledger.rows()).extracting(InMemoryLedger.Row::inputId).containsExactly(1L, 2L);
        return second;
    }

    @Test
    void aDraftWaitsAndApprovePromotesIt() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        final RoutingRule draft = waiting.doc().getRoutingTable().get(0);

        final ShapeshifterAiDoc approved = scenarios.stage(Script.of()).approve(waiting.doc(), draft.getUuid());

        assertThat(approved.getRoutingTable()).hasSize(1);
        final RoutingRule live = approved.getRoutingTable().get(0);
        assertThat(live.getUuid()).isEqualTo(draft.getUuid());
        assertThat(live.isDraft()).isFalse();
        assertThat(live.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(live.getPipeline()).isEqualTo(draft.getPipeline());
        assertThat(live.getScore()).isEqualTo(draft.getScore());
        assertThat(scenarios.ledger.isEmpty()).isTrue();
        assertThat(scenarios.reprocessing.requests()).hasSize(1);
        final Request request = scenarios.reprocessing.requests().get(0);
        assertThat(request.inputIds()).containsExactly(1L, 2L);
        assertThat(request.reason()).contains("approved").contains(SHAPE);
        assertThat(scenarios.regressionSet.accepted(live.getUuid())).hasSize(1);

        final Script silent = Script.of();
        final StageRun third = scenarios.stage(silent).run(approved, stream(3, lines(7, 0)));
        assertThat(third.decision()).isInstanceOf(Bound.class);
        assertThat(events(third.output())).isEqualTo(7);
        assertThat(silent.asked()).isEmpty();
    }

    @Test
    void rejectDiscardsTheDraftAndGivesTheShapeUp() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        final RoutingRule draft = waiting.doc().getRoutingTable().get(0);

        final ShapeshifterAiDoc rejected = scenarios.stage(Script.of())
                .reject(waiting.doc(), draft.getUuid(), "the wrong parser");

        assertThat(rejected.getRoutingTable()).isEmpty();
        assertThat(scenarios.regressionSet.accepted(draft.getUuid())).isEmpty();
        assertThat(scenarios.shapes.reasonGivenUp(DOC, SHAPE)).contains("Rejected: the wrong parser");
        assertThat(scenarios.ledger.rows()).describedAs("the ledger keeps what it had").hasSize(2);
        assertThat(scenarios.reprocessing.requests()).isEmpty();

        // The model is not asked again: the shape is given up until an operator says otherwise.
        final Script silent = Script.of();
        final StageRun third = scenarios.stage(silent).run(rejected, stream(3, lines(7, 0)));
        assertThat(third.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) third.decision()).reason()).contains("Rejected: the wrong parser");
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.ledger.rows()).hasSize(3);
    }

    @Test
    void onlyADraftCanBeApprovedOrRejected() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        final RoutingRule draft = waiting.doc().getRoutingTable().get(0);
        final ShapeshifterAiDoc approved = scenarios.stage(Script.of()).approve(waiting.doc(), draft.getUuid());

        assertThatThrownBy(() -> scenarios.stage(Script.of()).approve(approved, draft.getUuid()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a draft");
        assertThatThrownBy(() -> scenarios.stage(Script.of()).reject(approved, "no-such-rule", "whatever"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRelearnedCandidateIsADraftBehindTheIncumbentUntilApproved() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        final ShapeshifterAiDoc bound = scenarios.stage(Script.of())
                .approve(waiting.doc(), waiting.doc().getRoutingTable().get(0).getUuid());
        final RoutingRule incumbent = bound.getRoutingTable().get(0);
        final String withAlarms = lines(20, 3);

        // The score falls and the shape is marked, as in scenario 27.
        final Script silent = Script.of();
        final StageRun marked = scenarios.stage(silent).run(bound, stream(3, withAlarms));
        assertThat(marked.decision()).isInstanceOf(Bound.class);
        assertThat(scenarios.shapes.relearnReason(DOC, SHAPE)).isPresent();

        // Relearned: the incumbent serves, and the candidate is written as a draft behind it.
        final Script relearn = learning(scenarios, FOUR_FIELDS_AND_ALARMS);
        final StageRun relearned = scenarios.stage(relearn).run(bound, stream(4, withAlarms));
        relearn.verifyExhausted();
        assertThat(relearned.decision()).isInstanceOf(Drafted.class);
        final RoutingRule draft = ((Drafted) relearned.decision()).rule();
        assertThat(draft.getExpression()).isEqualTo(incumbent.getExpression());
        assertThat(draft.getUuid()).isNotEqualTo(incumbent.getUuid());
        assertThat(relearned.doc().getRoutingTable()).containsExactly(incumbent, draft);
        assertThat(events(relearned.output())).describedAs("the incumbent served").isEqualTo(14);
        assertThat(relearned.bindings().fragment()).isEqualTo(incumbent.getPipeline());
        assertThat(scenarios.ledger.isEmpty()).describedAs("nothing was refused").isTrue();

        // While the draft waits the incumbent keeps serving, the model is not asked, no second draft.
        final StageRun meanwhile = scenarios.stage(silent).run(relearned.doc(), stream(5, withAlarms));
        assertThat(meanwhile.decision()).isInstanceOf(Bound.class);
        assertThat(((Bound) meanwhile.decision()).rule()).isEqualTo(incumbent);
        assertThat(meanwhile.doc().getRoutingTable()).hasSize(2);
        assertThat(silent.asked()).isEmpty();

        // Approve rebinds the incumbent to the draft's fragment: one rule, the incumbent's uuid, the
        // draft's fragment, and a regression set that carries both histories.
        final ShapeshifterAiDoc approved = scenarios.stage(Script.of()).approve(relearned.doc(), draft.getUuid());
        assertThat(approved.getRoutingTable()).hasSize(1);
        final RoutingRule rebound = approved.getRoutingTable().get(0);
        assertThat(rebound.getUuid()).isEqualTo(incumbent.getUuid());
        assertThat(rebound.getPipeline()).isEqualTo(draft.getPipeline());
        assertThat(rebound.isDraft()).isFalse();
        assertThat(scenarios.regressionSet.accepted(incumbent.getUuid())).hasSize(2);
        assertThat(scenarios.regressionSet.accepted(draft.getUuid())).isEmpty();
        assertThat(scenarios.reprocessing.requests()).describedAs("the shape's ledger was already empty").hasSize(1);

        final StageRun after = scenarios.stage(silent).run(approved, stream(6, withAlarms));
        assertThat(events(after.output())).isEqualTo(20);
    }

    @Test
    void rejectingARelearnedDraftLeavesTheIncumbentServing() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        final ShapeshifterAiDoc bound = scenarios.stage(Script.of())
                .approve(waiting.doc(), waiting.doc().getRoutingTable().get(0).getUuid());
        final RoutingRule incumbent = bound.getRoutingTable().get(0);
        final String withAlarms = lines(20, 3);
        scenarios.stage(Script.of()).run(bound, stream(3, withAlarms));
        final StageRun relearned = scenarios.stage(learning(scenarios, FOUR_FIELDS_AND_ALARMS))
                .run(bound, stream(4, withAlarms));
        final RoutingRule draft = ((Drafted) relearned.decision()).rule();

        final ShapeshifterAiDoc rejected = scenarios.stage(Script.of()).reject(relearned.doc(), draft.getUuid(), "no");

        // The incumbent serves on; the rejection is recorded against the shape, and the same falling
        // score does not mark it or produce the same draft again until an operator says otherwise.
        assertThat(rejected.getRoutingTable()).containsExactly(incumbent);
        assertThat(scenarios.shapes.reasonGivenUp(DOC, SHAPE)).contains("Rejected: no");
        final Script silent = Script.of();
        for (long id = 5; id <= 7; id++) {
            final StageRun after = scenarios.stage(silent).run(rejected, stream(id, withAlarms));
            assertThat(after.decision()).isInstanceOf(Bound.class);
            assertThat(events(after.output())).isEqualTo(14);
        }
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.shapes.relearnReason(DOC, SHAPE)).isEmpty();
    }

    @Test
    void approvingAProvisionalDraftStartsItsRegressionSet() {
        // Too few records for A14; a person approving the draft is design 01 §6's exception, and the rule
        // goes live with the records it was judged on in its regression set (A18).
        final Scenarios scenarios = new Scenarios();
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.stage(script).run(review().copy().minRecordsPerShape(10).build(),
                stream(1, lines(6, 0)));
        final RoutingRule draft = ((Drafted) first.decision()).rule();
        assertThat(draft.isProvisional()).isTrue();

        final ShapeshifterAiDoc approved = scenarios.stage(Script.of()).approve(first.doc(), draft.getUuid());

        final RoutingRule live = approved.getRoutingTable().get(0);
        assertThat(live.isProvisional()).isFalse();
        assertThat(live.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(scenarios.regressionSet.accepted(live.getUuid())).hasSize(1);
    }

    @Test
    void aPinnedIncumbentRefusesApprovalOfADraftInItsPlace() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        final ShapeshifterAiDoc bound = scenarios.stage(Script.of())
                .approve(waiting.doc(), waiting.doc().getRoutingTable().get(0).getUuid());
        final String withAlarms = lines(20, 3);
        scenarios.stage(Script.of()).run(bound, stream(3, withAlarms));
        final StageRun relearned = scenarios.stage(learning(scenarios, FOUR_FIELDS_AND_ALARMS))
                .run(bound, stream(4, withAlarms));
        final RoutingRule draft = ((Drafted) relearned.decision()).rule();
        final RoutingRule pinned = relearned.doc().getRoutingTable().get(0).copy().pinned(true).build();
        final ShapeshifterAiDoc doc = relearned.doc().copy().routingTable(List.of(pinned, draft)).build();

        assertThatThrownBy(() -> scenarios.stage(Script.of()).approve(doc, draft.getUuid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pinned");
    }
}
