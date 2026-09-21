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
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Decision.Retracted;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.ai.state.InMemoryLedger;
import stroom.shapeshifter.ai.state.InMemoryReprocessing;
import stroom.shapeshifter.ai.state.InMemoryReprocessing.Request;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.shared.Severity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 02 §5, scenarios 28, 27 and 13: what happens to a binding after it is made. A provisional
 * rule that fails the gate is retracted and its outputs' inputs are requested again; a bound shape
 * whose rolling score falls is relearned while the incumbent serves; binding a shape releases its
 * ledger as a reprocess request. The feed is a headerless CSV of four fields, so that a regex splitter
 * can be right about it and then wrong when the feed changes.
 */
class TestScenariosRetractionRelearningAndRelease {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String FOUR_FIELDS_AND_ALARMS = Scenarios.resource("csv-fields-and-alarms.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DOC = "doc-1";
    private static final String SHAPE = "Feed=DOOR-ACCESS|Type=Raw Events";

    private static ShapeshifterAiDoc doc(final int minRecordsPerShape) {
        return ShapeshifterAiDoc.builder()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .plan(PlanExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(minRecordsPerShape)
                .relearnThreshold(0.8)
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

    private static String access(final int i) {
        return "2020-06-17T08:" + String.format("%02d", i) + ":00.000Z,user" + i + ",office,logon\n";
    }

    /**
     * {@code records} access lines, every {@code alarmEvery}th replaced by an alarm line of three fields.
     */
    private static String lines(final int records, final int alarmEvery) {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < records; i++) {
            text.append(alarmEvery > 0 && i % alarmEvery == alarmEvery - 1
                    ? "2020-06-17T08:" + String.format("%02d", i) + ":00.000Z,ALARM,door " + i + " forced\n"
                    : access(i));
        }
        return text.toString();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), data);
    }

    private static int events(final String output) {
        return output.split("<Event>", -1).length - 1;
    }

    @Test
    void scenario28AProvisionalRuleThatFailsTheGateIsRetracted() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc(10);

        // Six records against a minimum of ten: learned, bound provisionally (scenario 14).
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.stage(script).run(doc, stream(1, lines(6, 0)));
        script.verifyExhausted();
        assertThat(first.decision()).isInstanceOf(Provisional.class);
        final RoutingRule provisional = ((Provisional) first.decision()).rule();
        assertThat(first.bindings().provisional()).isTrue();

        // Four more records: still too few to judge, so the provisional rule serves them as it is.
        final Script silent = Script.of();
        final StageRun second = scenarios.stage(silent).run(first.doc(), stream(2, lines(4, 0)));
        assertThat(second.decision()).isInstanceOf(Bound.class);
        assertThat(second.bindings().provisional()).isTrue();
        assertThat(events(second.output())).isEqualTo(4);

        // Ten records the feed now sends with a fifth field: nothing matches, the candidate scores below
        // the floor on enough records to judge it, and the rule is retracted.
        final String fiveFields = lines(10, 0).replace(",logon\n", ",logon,badge\n");
        final StageRun third = scenarios.stage(silent).run(second.doc(), stream(3, fiveFields));

        assertThat(silent.asked()).isEmpty();
        assertThat(third.decision()).isInstanceOf(Retracted.class);
        final Retracted retracted = (Retracted) third.decision();
        assertThat(retracted.rule()).isEqualTo(provisional);
        assertThat(retracted.score()).isLessThan(doc.getPromotionFloor());
        assertThat(third.doc().getRoutingTable()).describedAs("the shape is unknown again").isEmpty();
        assertThat(third.output()).isNull();
        assertThat(third.bindings()).isNull();
        assertThat(scenarios.shapes.reasonGivenUp(DOC, SHAPE)).describedAs("unknown, not given up").isEmpty();
        // The streams that carried the provisional binding are requested again, as-current; this one,
        // which nothing produced output for, is on the ledger instead.
        assertThat(scenarios.reprocessing.requests()).hasSize(1);
        final Request request = scenarios.reprocessing.requests().get(0);
        assertThat(request.docUuid()).isEqualTo(DOC);
        assertThat(request.inputIds()).containsExactly(1L, 2L);
        assertThat(request.reason()).contains(provisional.getUuid()).contains("retracted");
        assertThat(scenarios.ledger.rows()).extracting(InMemoryLedger.Row::inputId).containsExactly(3L);

        // The next stream of the shape learns afresh — and, with ten records, promotes outright — and
        // binding the shape releases the ledger as a second request.
        final Script again = learning(scenarios, FOUR_FIELDS);
        final StageRun fourth = scenarios.stage(again).run(third.doc(), stream(4, lines(10, 0)));
        again.verifyExhausted();
        assertThat(fourth.decision()).isInstanceOf(Promoted.class);
        assertThat(((Promoted) fourth.decision()).rule().getUuid()).isNotEqualTo(provisional.getUuid());
        assertThat(scenarios.reprocessing.requests()).hasSize(2);
        assertThat(scenarios.reprocessing.requests().get(1).inputIds()).containsExactly(3L);
        assertThat(scenarios.ledger.isEmpty()).isTrue();
    }

    @Test
    void scenario27AFallingRollingScoreTriggersRelearningWhileTheIncumbentServes() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc(5);

        // Seven clean records: learned and promoted at a perfect score.
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.stage(script).run(doc, stream(1, lines(7, 0)));
        script.verifyExhausted();
        assertThat(first.decision()).isInstanceOf(Promoted.class);
        final RoutingRule incumbent = ((Promoted) first.decision()).rule();
        assertThat(((Promoted) first.decision()).score()).isEqualTo(1.0);

        // Twenty records of which six are a second kind the splitter does not match. No new shape: the
        // rule still binds, the fourteen that parse become events, the six are errors on the way — not
        // sentinels, so not on the ledger — and the shape's rolling score falls below the threshold.
        final String withAlarms = lines(20, 3);
        final Script silent = Script.of();
        final StageRun second = scenarios.stage(silent).run(first.doc(), stream(2, withAlarms));

        assertThat(silent.asked()).isEmpty();
        assertThat(second.decision()).isInstanceOf(Bound.class);
        assertThat(events(second.output())).isEqualTo(14);
        assertThat(second.verdicts().get(0).judgements().get(0).score().diagnostics())
                .describedAs("the unmatched lines are the splitter's errors")
                .anyMatch(error -> error.getSeverity() == Severity.ERROR);
        assertThat(second.bindings().score())
                .describedAs("fourteen of twenty lines covered, and slightly fewer of the characters")
                .isBetween(0.65, 0.7);
        assertThat(scenarios.ledger.isEmpty()).isTrue();
        assertThat(scenarios.shapes.rollingScore(DOC, SHAPE)).isLessThan(0.8);
        assertThat(scenarios.shapes.relearnReason(DOC, SHAPE)).isPresent();
        assertThat(second.doc()).isEqualTo(first.doc());

        // The next stream starts an attempt, opening with why the incumbent fell short: the mark's reason
        // and the coverage shortfall, on the chain question and on each element's first. The incumbent
        // serves it meanwhile; the candidate handles both kinds, beats the incumbent on this stream and
        // is no worse on the record it was accepted on, so the rule is rebound to it — same rule, new
        // fragment.
        final Script relearn = scenarios.script(FOUR_FIELDS_AND_ALARMS, XSLT)
                .expect(QuestionMatcher.chain()
                        .withFeedbackMentioning("Relearning: Rolling score")
                        .withFeedbackMentioning("The split consumed 14 of 20 lines"))
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withFeedbackMentioning("14 of 20 lines"))
                .reply(Scenarios.fenced(FOUR_FIELDS_AND_ALARMS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withFeedbackMentioning("Relearning"))
                .reply(Scenarios.fenced(XSLT));
        final StageRun third = scenarios.stage(relearn).run(second.doc(), stream(3, withAlarms));
        relearn.verifyExhausted();

        assertThat(third.decision()).isInstanceOf(Rebound.class);
        final Rebound rebound = (Rebound) third.decision();
        assertThat(rebound.incumbent()).isEqualTo(incumbent);
        assertThat(rebound.rule().getUuid()).isEqualTo(incumbent.getUuid());
        assertThat(rebound.rule().getPipeline()).isNotEqualTo(incumbent.getPipeline());
        assertThat(rebound.rule().getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(rebound.score()).isEqualTo(1.0);
        assertThat(third.doc().getRoutingTable()).containsExactly(rebound.rule());
        assertThat(events(third.output())).describedAs("the incumbent served this stream").isEqualTo(14);
        assertThat(third.bindings().fragment()).isEqualTo(incumbent.getPipeline());
        assertThat(scenarios.regressionSet.accepted(incumbent.getUuid())).hasSize(2);
        assertThat(scenarios.shapes.relearnReason(DOC, SHAPE)).isEmpty();
        assertThat(scenarios.stores.pipelines.list()).hasSize(2);

        // And the stream after that is served by the new fragment, every record an event.
        final StageRun fourth = scenarios.stage(silent).run(third.doc(), stream(4, withAlarms));
        assertThat(fourth.decision()).isInstanceOf(Bound.class);
        assertThat(fourth.bindings().fragment()).isEqualTo(rebound.rule().getPipeline());
        assertThat(events(fourth.output())).isEqualTo(20);
        assertThat(silent.asked()).isEmpty();
    }

    @Test
    void aPinnedRuleIsNeitherPromotedNorRetracted() {
        // Design 01 §7.3 rule 2: a pin exempts a rule from automatic rebinding and retraction, provisional
        // or not. The stream that would retract the rule in scenario 28 is simply served.
        final Scenarios scenarios = new Scenarios();
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.stage(script).run(doc(10), stream(1, lines(6, 0)));
        final RoutingRule pinned = ((Provisional) first.decision()).rule().copy().pinned(true).build();
        final ShapeshifterAiDoc doc = first.doc().copy().routingTable(List.of(pinned)).build();

        final Script silent = Script.of();
        final StageRun run = scenarios.stage(silent).run(doc,
                stream(2, lines(10, 0).replace(",logon\n", ",logon,badge\n")));

        assertThat(run.decision()).isInstanceOf(Bound.class);
        assertThat(run.doc().getRoutingTable()).containsExactly(pinned);
        assertThat(scenarios.reprocessing.requests()).isEmpty();
        assertThat(scenarios.ledger.isEmpty()).isTrue();
        assertThat(silent.asked()).isEmpty();
    }

    @Test
    void aMarkedShapeWaitsForAStreamACandidateCanBeJudgedOn() {
        final Scenarios scenarios = new Scenarios();
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.stage(script).run(doc(5), stream(1, lines(7, 0)));
        assertThat(first.decision()).isInstanceOf(Promoted.class);

        // A blank stream after promotion is nothing to judge the shape on: no score, no mark.
        final Script silent = Script.of();
        final StageRun blank = scenarios.stage(silent).run(first.doc(), stream(2, "\n"));
        assertThat(blank.decision()).isInstanceOf(Bound.class);
        assertThat(scenarios.shapes.relearnReason(DOC, SHAPE)).isEmpty();

        // The falling score marks the shape; a stream of three records cannot meet A14 for a candidate,
        // so the incumbent serves it, the model is not asked, and the mark waits.
        scenarios.stage(silent).run(first.doc(), stream(3, lines(20, 3)));
        assertThat(scenarios.shapes.relearnReason(DOC, SHAPE)).isPresent();
        final Script stillSilent = Script.of();
        final StageRun small = scenarios.stage(stillSilent).run(first.doc(), stream(4, lines(3, 3)));
        assertThat(small.decision()).isInstanceOf(Bound.class);
        assertThat(stillSilent.asked()).isEmpty();
        assertThat(scenarios.shapes.relearnReason(DOC, SHAPE)).isPresent();

        // A stream with enough records is relearned.
        final Script relearn = learning(scenarios, FOUR_FIELDS_AND_ALARMS);
        final StageRun big = scenarios.stage(relearn).run(first.doc(), stream(5, lines(20, 3)));
        relearn.verifyExhausted();
        assertThat(big.decision()).isInstanceOf(Rebound.class);
    }

    @Test
    void scenario13BindingAShapeReleasesItsLedgerAsAReprocessRequest() {
        final Scenarios scenarios = new Scenarios();
        // Two streams arrive while learning is disabled: each is a sentinel and a ledger row (A4).
        final ShapeshifterAiDoc disabled = doc(5).copy().learningMode(LearningMode.DISABLED).build();
        final Script silent = Script.of();
        for (long id = 11; id <= 12; id++) {
            final StageRun run = scenarios.stage(silent).run(disabled, stream(id, lines(6, 0)));
            assertThat(run.decision()).isInstanceOf(Sentinel.class);
        }
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.ledger.rows()).extracting(InMemoryLedger.Row::inputId).containsExactly(11L, 12L);
        assertThat(scenarios.ledger.rows()).allMatch(row -> row.shape().equals(SHAPE));

        // Learning is switched on and the next stream learns the shape: the ledger is cleared and a
        // reprocess request names exactly the two inputs — not this one, which has its output.
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun run = scenarios.stage(script).run(doc(5), stream(13, lines(6, 0)));
        script.verifyExhausted();

        assertThat(run.decision()).isInstanceOf(Promoted.class);
        assertThat(scenarios.ledger.isEmpty()).isTrue();
        assertThat(scenarios.reprocessing.requests()).hasSize(1);
        final Request request = scenarios.reprocessing.requests().get(0);
        assertThat(request.docUuid()).isEqualTo(DOC);
        assertThat(request.inputIds()).containsExactly(11L, 12L);
        assertThat(request.reason()).contains(SHAPE).contains(((Promoted) run.decision()).rule().getUuid());
    }
}
