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

import stroom.meta.shared.MetaFields;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.RecordedAdvisor;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.stage.Shape;
import stroom.shapeshifter.ai.stage.ShapeSignature;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.RoutingFields;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 02 §5, scenarios 14, 25, 26 and 29, and the draft-rule sentinel of 22: what the stage does
 * with a stream before — or instead of — asking the model. Every scenario here runs with an empty
 * script: a question asked is the failure.
 */
class TestScenariosBindingBeforeLearning {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String EXPECTED_EVENTS = Scenarios.resource("csv-logon.events.xml");

    /**
     * Keyed on {@code Format} as well, so that the same CSV under another format label is a new shape
     * the existing splitter happens to consume.
     */
    private static ShapeshifterAiDoc keyedOnFormat() {
        return ShapeshifterAiDoc.builder()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .plan(PlanExample.TARGET_FIRST)
                .learningKey(List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE, RoutingFields.FORMAT))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(5)
                .promotionFloor(0.85)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    /// The shape a CSV stream of this feed and type makes, under the document's learning key.
    private static String shapeX() {
        return Shape.of(keyedOnFormat().getLearningKey(),
                stream("CSV").routingAttributes(ShapeSignature.of(CSV.input()))).id();
    }

    private static Input stream(final String format) {
        return new Input(1L, "DOOR-ACCESS", "Raw Events", Map.of("Format", format), CSV.input());
    }

    /**
     * Learns shape X (Format=CSV) with a scripted model, so that a rule is bound for the feed and type.
     */
    private static StageRun learnShapeX(final Scenarios scenarios, final ShapeshifterAiDoc doc) {
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun run = scenarios.stage(script).run(doc, stream("CSV"));
        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        return run;
    }

    @Test
    void scenario25AnExistingBindingFitsANewShapeWithoutAQuestion() {
        final Scenarios scenarios = new Scenarios();
        final StageRun learned = learnShapeX(scenarios, keyedOnFormat());
        final RoutingRule v1 = ((Promoted) learned.decision()).rule();

        final Script silent = Script.of();
        final StageRun run = scenarios.stage(silent).run(learned.doc(), stream("CSV-v2"));

        assertThat(silent.asked()).isEmpty();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        final RoutingRule bound = ((Promoted) run.decision()).rule();
        assertThat(bound.getPipeline())
                .describedAs("v1's fragment, not a new one: no document was written")
                .isEqualTo(v1.getPipeline());
        assertThat(bound.getUuid()).isNotEqualTo(v1.getUuid());
        assertThat(bound.getExpression().toString()).contains("Format = CSV-v2");
        assertThat(run.shape().id()).isEqualTo("Feed=DOOR-ACCESS|Type=Raw Events|Format=CSV-v2");
        assertThat(run.output()).isEqualTo(EXPECTED_EVENTS);
        assertThat(scenarios.rules.forDocument("doc-1")).hasSize(2);
        assertThat(scenarios.stores.pipelines.list()).hasSize(1);
        assertThat(scenarios.regressionSet.accepted(bound.getUuid()))
                .describedAs("held out by construction: promoted, so accepted")
                .hasSize(1);
    }

    @Test
    void scenario29DisabledStillSelectsAmongBoundVariants() {
        final Scenarios scenarios = new Scenarios();
        final StageRun learned = learnShapeX(scenarios, keyedOnFormat());
        final ShapeshifterAiDoc disabled = learned.doc().copy().learningMode(LearningMode.DISABLED).build();

        final Script silent = Script.of();
        final StageRun fits = scenarios.stage(silent).run(disabled, stream("CSV-v2"));
        assertThat(fits.decision()).isInstanceOf(Promoted.class);
        assertThat(silent.asked()).isEmpty();

        // A stream nothing bound can handle is a sentinel: the model is never asked.
        final Input unlike = new Input(2L, "DOOR-ACCESS", "Raw Events", Map.of("Format", "JSON"),
                "{\"not\": \"csv\"}\n");
        final StageRun sentinel = scenarios.stage(silent).run(fits.doc(), unlike);
        assertThat(sentinel.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) sentinel.decision()).reason()).contains("disabled");
        assertThat(silent.asked()).isEmpty();
    }

    @Test
    void scenario26AReservedRuleGivesTheShapeUp() {
        final Scenarios scenarios = new Scenarios();
        final RoutingRule reserved = RoutingRule.builder()
                .uuid("reserved-1")
                .expression(ExpressionOperator.builder()
                        .addTerm(MetaFields.FIELD_FEED, Condition.EQUALS, "DOOR-ACCESS")
                        .build())
                .build();
        final ShapeshifterAiDoc doc = keyedOnFormat();
        scenarios.rules.append("doc-1", reserved);

        final Script silent = Script.of();
        final StageRun run = scenarios.stage(silent).run(doc, stream("CSV"));

        assertThat(run.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) run.decision()).reason()).startsWith("Reserved: rule 1");
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.rules.forDocument("doc-1")).containsExactly(reserved);
        assertThat(scenarios.stores.pipelines.list()).isEmpty();
    }

    @Test
    void scenario14TooFewRecordsBindsProvisionallyThenPromotesWhenEnoughArrive() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = keyedOnFormat().copy().minRecordsPerShape(10).build();
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        // Seven lines — six records and a header — against a minimum of ten: the candidate clears the
        // floor, so it is bound and serves this stream, but provisionally — A14 cannot yet be met.
        final StageRun first = scenarios.stage(script).run(doc, stream("CSV"));
        script.verifyExhausted();
        assertThat(first.decision()).isInstanceOf(Provisional.class);
        final Provisional provisional = (Provisional) first.decision();
        assertThat(provisional.records()).isEqualTo(7);
        assertThat(provisional.required()).isEqualTo(10);
        assertThat(provisional.rule().isProvisional()).isTrue();
        assertThat(provisional.rule().getPromotedTimeMs()).isNull();
        assertThat(first.output()).isEqualTo(EXPECTED_EVENTS);
        assertThat(scenarios.regressionSet.accepted(provisional.rule().getUuid()))
                .describedAs("nothing is accepted until promotion")
                .isEmpty();

        // The same shape again with twelve records: the provisional rule serves it and, now that the
        // shape has enough records and clears the floor, is promoted in place.
        final String[] lines = CSV.input().split("\n");
        final StringBuilder twelve = new StringBuilder(lines[0]).append('\n');
        for (int i = 0; i < 2; i++) {
            for (int line = 1; line < lines.length; line++) {
                twelve.append(lines[line]).append('\n');
            }
        }
        final Script silent = Script.of();
        final StageRun second = scenarios.stage(silent).run(first.doc(),
                new Input(2L, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), twelve.toString()));

        assertThat(silent.asked()).isEmpty();
        assertThat(second.decision()).isInstanceOf(Promoted.class);
        final RoutingRule promoted = ((Promoted) second.decision()).rule();
        assertThat(promoted.getUuid()).isEqualTo(provisional.rule().getUuid());
        assertThat(promoted.isProvisional()).isFalse();
        assertThat(promoted.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(scenarios.rules.forDocument("doc-1")).containsExactly(promoted);
        assertThat(scenarios.regressionSet.accepted(promoted.getUuid())).hasSize(1);

        // And a third stream is simply bound.
        final StageRun third = scenarios.stage(silent).run(second.doc(), stream("CSV"));
        assertThat(third.decision()).isInstanceOf(Bound.class);
    }

    @Test
    void aDraftRuleIsNotBoundAndItsShapeIsSentinelled() {
        // The routing half of scenario 22 (A25): the router does not bind a draft; a stream of its shape
        // is sentinelled naming the draft, and the model is not asked again.
        final Scenarios scenarios = new Scenarios();
        final StageRun learned = learnShapeX(scenarios, keyedOnFormat());
        final RoutingRule live = ((Promoted) learned.decision()).rule();
        final RoutingRule draft = live.copy().draft(true).build();
        scenarios.rules.replace("doc-1", draft);

        final Script silent = Script.of();
        final StageRun run = scenarios.stage(silent).run(learned.doc(), stream("CSV"));

        assertThat(run.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) run.decision()).reason())
                .startsWith("Awaiting review: draft rule " + draft.getUuid());
        assertThat(silent.asked()).isEmpty();
        assertThat(run.output()).isNull();
    }

    @Test
    void aSecondNodeMeetingTheSameShapeSentinelsRatherThanWaiting() {
        // A42: one learner per shape across the cluster. The loser does not wait — that would hold a
        // processing thread for the length of an attempt, and at hundreds of threads a shape's first
        // minute would stall the cluster — it writes its ledger row and returns, and the winner's
        // promotion releases the backlog as A12 releases any other.
        final Scenarios scenarios = new Scenarios();
        scenarios.shapes.lease("doc-1", shapeX(), "node-2", Scenarios.NOW.toEpochMilli(), Long.MAX_VALUE);

        final Script silent = Script.of();
        scenarios.node = "node-1";
        final StageRun run = scenarios.stage(silent).run(keyedOnFormat(), stream("CSV"));

        assertThat(run.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) run.decision()).reason()).contains("Another node is learning this shape");
        assertThat(silent.asked()).describedAs("the model is asked once for a shape, not once per node")
                .isEmpty();
        assertThat(scenarios.ledger.rows()).hasSize(1);
        assertThat(scenarios.rules.forDocument("doc-1")).isEmpty();

        // The lease given back, the next stream of the shape learns, and the ledger's backlog is released.
        scenarios.shapes.releaseLease("doc-1", shapeX(), "node-2");
        final StageRun learned = learnShapeX(scenarios, keyedOnFormat());

        assertThat(learned.decision()).describedAs(learned.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(scenarios.reprocessing.requests()).hasSize(1);
        assertThat(scenarios.reprocessing.requests().get(0).inputIds()).containsExactly(1L);
        assertThat(scenarios.ledger.isEmpty()).isTrue();
    }

    @Test
    void twoStagesSharingTheStateAreOneLearner() {
        // Not a lease planted by hand: two stages over one state, as two nodes are, each taking the lease
        // for itself. The first holds it until its rule is written — a second node that took it in between
        // would find no rule for the shape, learn it again, and append a second rule for one selector.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = keyedOnFormat();
        final Script watching = Script.of();
        scenarios.node = "node-2";
        final Stage other = scenarios.stage(watching);
        scenarios.node = "node-1";

        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        // The other node arrives at the moment the rule is written: the model has been asked and answered,
        // so a lease released when the dialogue ended would be free, and it would learn the shape again.
        final Rules watched = new Rules() {
            @Override
            public List<RoutingRule> forDocument(final String docUuid) {
                return scenarios.rules.forDocument(docUuid);
            }

            @Override
            public Optional<RoutingRule> byUuid(final String docUuid, final String ruleUuid) {
                return scenarios.rules.byUuid(docUuid, ruleUuid);
            }

            @Override
            public RoutingRule append(final String docUuid, final RoutingRule rule) {
                final StageRun meanwhile = other.run(doc, stream("CSV"));
                assertThat(meanwhile.decision()).describedAs(meanwhile.decision().toString())
                        .isInstanceOf(Sentinel.class);
                assertThat(watching.asked()).describedAs("it did not ask the model").isEmpty();
                return scenarios.rules.append(docUuid, rule);
            }

            @Override
            public RoutingRule insert(final String docUuid, final RoutingRule rule, final int at) {
                return scenarios.rules.insert(docUuid, rule, at);
            }

            @Override
            public void move(final String docUuid, final String ruleUuid, final int to) {
                scenarios.rules.move(docUuid, ruleUuid, to);
            }

            @Override
            public void replace(final String docUuid, final RoutingRule rule) {
                scenarios.rules.replace(docUuid, rule);
            }

            @Override
            public void remove(final String docUuid, final String ruleUuid) {
                scenarios.rules.remove(docUuid, ruleUuid);
            }
        };

        final StageRun run = scenarios.stage(script, watched).run(doc, stream("CSV"));

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(scenarios.rules.forDocument("doc-1"))
                .describedAs("one selector, one rule: the loser did not write a second")
                .hasSize(1);
        assertThat(scenarios.stores.pipelines.list()).describedAs("and no orphaned fragment").hasSize(1);
    }

    @Test
    void aLeaseThatHasExpiredIsFreeForTheNextNode() {
        // The holder died mid-attempt: its lease runs out and the next node in learns, rather than the
        // shape being stuck until someone notices.
        final Scenarios scenarios = new Scenarios();
        scenarios.shapes.lease("doc-1", shapeX(), "node-2", Scenarios.NOW.toEpochMilli(),
                Scenarios.NOW.toEpochMilli() - 1);

        final StageRun run = learnShapeX(scenarios, keyedOnFormat());

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
    }

    @Test
    void everyAttemptIsRecordedWithItsTurnsAndWhatItCameTo() {
        // A28: the record outlives the node that made it, and is the same record whichever mode produced
        // it. What it does not carry is the rendered prompt, which holds the stream's own text and waits
        // for redaction (A17, A38).
        final Scenarios scenarios = new Scenarios();

        final StageRun learned = learnShapeX(scenarios, keyedOnFormat());

        final List<Recorded> recorded = scenarios.attempts.forDocument("doc-1", 10);
        assertThat(recorded).hasSize(1);
        final Recorded attempt = recorded.get(0);
        assertThat(attempt.status()).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(attempt.ruleUuid()).isEqualTo(((Promoted) learned.decision()).rule().getUuid());
        assertThat(attempt.score()).isEqualTo(((Promoted) learned.decision()).score());
        assertThat(attempt.attempt().shape()).isEqualTo(shapeX());
        assertThat(attempt.attempt().feed()).isEqualTo("DOOR-ACCESS");
        assertThat(attempt.attempt().node()).isEqualTo("node-1");
        assertThat(attempt.turns()).hasSameSizeAs(learned.transcript());
        assertThat(attempt.turns()).extracting(Turn::number)
                .describedAs("every turn of the dialogue, in order, including those the structure answered")
                .isEqualTo(IntStream.rangeClosed(1, learned.transcript().size()).boxed().toList());
        assertThat(attempt.turns().get(0).kind()).isEqualTo(QuestionKind.CHAIN);
        assertThat(attempt.turns().get(0).question())
                .describedAs("what was asked, without the stream's own text in it")
                .contains("Chain").contains("DSParser")
                .doesNotContain("jim,warehouse");
        assertThat(attempt.turns().get(0).answer()).isEqualTo("DSParser -> XSLTFilter");
        assertThat(attempt.turns()).allSatisfy(turn ->
                assertThat(turn.answeredBy()).describedAs("nothing but the model answers a turn yet")
                        .isNotNull());
        assertThat(attempt.turns()).extracting(Turn::outcome).containsOnly(StepOutcome.PASSED);
    }

    @Test
    void anAttemptThatBoundNothingSaysSoAndOneThatIsLeasedAwaySaysNothing() {
        final Scenarios scenarios = new Scenarios();
        // Nothing to learn with: the attempt is opened, abandoned and recorded as abandoned.
        final ShapeshifterAiDoc nothingRunnable = keyedOnFormat().copy()
                .allowedElements(List.of("JSONParser"))
                .build();

        scenarios.stage(Script.of()).run(nothingRunnable, stream("CSV"));

        final List<Recorded> recorded = scenarios.attempts.forDocument("doc-1", 10);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).status()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(recorded.get(0).ruleUuid()).isNull();

        // A node that does not win the lease opens no attempt: it did not learn anything.
        scenarios.shapes.lease("doc-1", shapeX(), "node-2", Scenarios.NOW.toEpochMilli(), Long.MAX_VALUE);
        scenarios.stage(Script.of()).run(keyedOnFormat(), stream("CSV"));

        assertThat(scenarios.attempts.forDocument("doc-1", 10))
                .describedAs("one attempt, the abandoned one: the loser recorded none")
                .hasSize(1);
    }

    @Test
    void whatAnAttemptSpentIsCountedFromOneAdvisor() {
        // The node's Advisors makes a new advisor per call, each counting its own tokens, so an attempt
        // that asked one and read another would record nothing spent — which is what it did.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        // A fresh advisor per call, as the node's does, each charging as it is asked.
        final Advisors fresh = document -> new CountingAdvisor(script);

        final StageRun run = scenarios.stage(fresh, scenarios.rules).run(keyedOnFormat(), stream("CSV"));

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(scenarios.attempts.forDocument("doc-1", 10).get(0).tokensSpent())
                .describedAs("the attempt asked one advisor, so its charges are the attempt's")
                .isGreaterThan(0L);
    }

    @Test
    void aTurnIsRecordedAsItIsAskedNotOnlyWhenTheAttemptEnds() {
        // The transcript a person most needs is the one from an attempt that did not finish.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(() -> {
                    // Mid-attempt: the chain question is already a row, answered and judged.
                    final Recorded far = scenarios.attempts.forDocument("doc-1", 10).get(0);
                    assertThat(far.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
                    assertThat(far.turns()).isNotEmpty();
                    assertThat(far.turns().get(0).kind()).isEqualTo(QuestionKind.CHAIN);
                    assertThat(far.turns().get(0).answer()).isEqualTo("DSParser -> XSLTFilter");
                    assertThat(far.turns().get(0).outcome()).isEqualTo(StepOutcome.PASSED);
                    throw new IllegalStateException("the model fell over");
                });

        assertThatThrownBy(() -> scenarios.stage(script).run(keyedOnFormat(), stream("CSV")))
                .isInstanceOf(IllegalStateException.class);

        final Recorded attempt = scenarios.attempts.forDocument("doc-1", 10).get(0);
        assertThat(attempt.status()).describedAs("an attempt that threw says so rather than vanishing")
                .isEqualTo(AttemptStatus.ERROR);
        assertThat(attempt.decision()).contains("the model fell over");
        assertThat(attempt.turns()).describedAs("with the turns it had got to").isNotEmpty();
    }


    @Test
    void anAttemptThatStopsAtAQuestionIsCarriedOnFromWhatItWasAnswered() {
        // A28 and A45: an attempt parked at a question keeps its claim on the shape, and the answers it
        // already has are not asked again. The dialogue keeps no state of its own — it is re-walked with
        // those answers, which re-derives what they produced.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = keyedOnFormat();
        // The chain is answered; the parser question is not, so the attempt stops there.
        final Script started = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter");

        final StageRun parked = scenarios.stage(new StoppingAdvisor(started, 1))
                .run(doc, stream("CSV"));

        assertThat(parked.decision()).describedAs(parked.decision().toString()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) parked.decision()).reason()).contains("Awaiting the model");
        final Recorded waiting = scenarios.attempts.forDocument("doc-1", 10).get(0);
        assertThat(waiting.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(waiting.turns()).describedAs("the chain it was answered").hasSize(1);
        assertThat(scenarios.rules.forDocument("doc-1")).isEmpty();

        // While it waits it holds the shape: another stream of it learns nothing beside it.
        final Script other = Script.of();
        assertThat(scenarios.stage(other).run(doc, stream("CSV")).decision()).isInstanceOf(Sentinel.class);
        assertThat(other.asked()).isEmpty();

        // The worker carries it on. The chain is answered from the record, so the model is asked only what
        // the attempt had not reached.
        final Script carrying = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun resumed = scenarios.stage(Script.of())
                .resume(doc, waiting.id(), stream("CSV"), carrying);

        carrying.verifyExhausted();
        assertThat(resumed.decision()).describedAs(resumed.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(carrying.asked()).describedAs("the chain was not asked again")
                .noneMatch(Question.Chain.class::isInstance);
        assertThat(scenarios.rules.forDocument("doc-1")).hasSize(1);
        final Recorded finished = scenarios.attempts.byId(waiting.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(finished.turns()).describedAs("every turn, the replayed ones and the new")
                .hasSize(resumed.transcript().size());
    }


    // --------------------------------------------------------------------------------


    /// An advisor that answers so many questions and then stops, as a deferred attempt does when its
    /// budget says to wait for the worker (A5, A28).
    private static final class StoppingAdvisor implements Advisor {

        private final Advisor delegate;
        private final int answers;
        private int asked;

        private StoppingAdvisor(final Advisor delegate, final int answers) {
            this.delegate = delegate;
            this.answers = answers;
        }

        @Override
        public String ask(final List<Exchange> transcript, final Question question) {
            if (asked++ >= answers) {
                return RecordedAdvisor.awaiting().ask(transcript, question);
            }
            return delegate.ask(transcript, question);
        }
    }


    // --------------------------------------------------------------------------------


    /// An advisor that charges for what it is asked, as the node's does.
    private static final class CountingAdvisor implements Advisor {

        private final Advisor delegate;
        private long tokens;

        private CountingAdvisor(final Advisor delegate) {
            this.delegate = delegate;
        }

        @Override
        public String ask(final List<Exchange> transcript, final Question question) {
            tokens += 100;
            return delegate.ask(transcript, question);
        }

        @Override
        public long tokensUsed() {
            return tokens;
        }
    }
}
