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

package stroom.shapeshifter.ai.impl.db;

import stroom.docref.DocRef;
import stroom.pipeline.shared.PipelineDoc;
import stroom.shapeshifter.ai.stage.Attempts.Attempt;
import stroom.shapeshifter.ai.stage.Attempts.Page;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.ai.stage.Bindings;
import stroom.shapeshifter.ai.stage.Replayable;
import stroom.shapeshifter.ai.stage.Spend.Spent;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.util.shared.DefaultLocation;
import stroom.util.shared.PageRequest;
import stroom.util.shared.TextRange;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/// The shape state of A26 and the ledger of §5.2 as rows.
class TestShapesAndLedgerDao {

    private static final String DOC = "doc-" + System.nanoTime();
    private static final String SHAPE = "Feed=DOOR-ACCESS|Type=Raw Events";

    @Inject
    private ShapesDao shapes;
    @Inject
    private LedgerDao ledger;
    @Inject
    private SpendDao spend;
    @Inject
    private AttemptsDao attempts;
    @Inject
    private OutputsDao outputs;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        shapes.reset(DOC, SHAPE);
        ledger.release(DOC, SHAPE);
    }

    @Test
    void aShapeRemembersWhyItWasGivenUpMarkedOrHeldForReview() {
        assertThat(shapes.reasonGivenUp(DOC, SHAPE)).describedAs("a shape nothing has said anything about")
                .isEmpty();

        shapes.giveUp(DOC, SHAPE, "Rejected: the wrong parser");
        shapes.markForRelearning(DOC, SHAPE, "Rolling score 0.6 fell below 0.8");
        shapes.awaitReview(DOC, SHAPE, "rule-1");

        assertThat(shapes.reasonGivenUp(DOC, SHAPE)).contains("Rejected: the wrong parser");
        assertThat(shapes.relearnReason(DOC, SHAPE)).contains("Rolling score 0.6 fell below 0.8");
        assertThat(shapes.draftAwaiting(DOC, SHAPE)).contains("rule-1");
        assertThat(shapes.shapeAwaiting(DOC, "rule-1")).contains(SHAPE);
        assertThat(shapes.shapeAwaiting(DOC, "rule-2")).isEmpty();

        shapes.reset(DOC, SHAPE);
        assertThat(shapes.reasonGivenUp(DOC, SHAPE)).isEmpty();
        assertThat(shapes.relearnReason(DOC, SHAPE)).isEmpty();
        assertThat(shapes.draftAwaiting(DOC, SHAPE)).isEmpty();
    }

    @Test
    void theRollingScoreIsOfWhatTheShapeHasDoneLately() {
        // Nothing is said until the shape has brought enough records to rest a judgement on.
        assertThat(shapes.scored(DOC, SHAPE, 1.0, 4, 10)).isEmpty();
        assertThat(shapes.scored(DOC, SHAPE, 1.0, 4, 10)).isEmpty();

        final OptionalDouble rolling = shapes.scored(DOC, SHAPE, 0.0, 4, 10);

        assertThat(rolling).isPresent();
        assertThat(rolling.getAsDouble()).describedAs("eight good records, then four bad, remembering ten")
                .isEqualTo(8.0 / 12);
        // Reset starts it afresh, as a promotion or a retraction does.
        shapes.reset(DOC, SHAPE);
        assertThat(shapes.scored(DOC, SHAPE, 1.0, 1, 1)).hasValue(1.0);
    }

    @Test
    void theLedgerNamesWhatToReplayAndWhereAndReleasesItOnce() {
        ledger.sentinelled(DOC, SHAPE, 1L, "pipeline-1", "Unknown shape");
        ledger.sentinelled(DOC, SHAPE, 2L, "pipeline-2", "Unknown shape");
        ledger.sentinelled(DOC, "another-shape", 3L, null, "Unknown shape");

        final List<Replayable> released = ledger.release(DOC, SHAPE);

        assertThat(released).extracting(Replayable::inputId)
                .describedAs("oldest first: the backlog is replayed in order").containsExactly(1L, 2L);
        assertThat(released).extracting(Replayable::pipeline)
                .describedAs("and each through the pipeline that sentinelled it, which may not be this one")
                .containsExactly("pipeline-1", "pipeline-2");
        assertThat(ledger.release(DOC, SHAPE))
                .describedAs("a second release finds nothing: two nodes must not both replay it")
                .isEmpty();
        assertThat(ledger.release(DOC, "another-shape")).extracting(Replayable::pipeline)
                .describedAs("a stream sentinelled by no pipeline names none").containsExactly((String) null);
    }

    @Test
    void aStreamSentinelledTwiceIsReplayedOnce() {
        ledger.sentinelled(DOC, SHAPE, 1L, "pipeline-1", "Unknown shape");
        ledger.sentinelled(DOC, SHAPE, 1L, "pipeline-1", "Unknown shape, again");

        assertThat(ledger.release(DOC, SHAPE)).extracting(Replayable::inputId).containsExactly(1L);
    }

    @Test
    void aShapeIdLongerThanAColumnIsStillOneShape() {
        // A learning key may name a sender-supplied header, so an id has no bound; rows are found by its
        // hash and the id is kept as written.
        final String long1 = SHAPE + "|RemoteFile=" + "a".repeat(2000);
        final String long2 = SHAPE + "|RemoteFile=" + "b".repeat(2000);

        shapes.giveUp(DOC, long1, "too long to lose");
        ledger.sentinelled(DOC, long1, 7L, "pipeline-1", "Unknown shape");

        assertThat(shapes.reasonGivenUp(DOC, long1)).contains("too long to lose");
        assertThat(shapes.reasonGivenUp(DOC, long2)).describedAs("a different long id is a different shape")
                .isEmpty();
        assertThat(ledger.release(DOC, long1)).extracting(Replayable::inputId).containsExactly(7L);
        shapes.reset(DOC, long1);
    }

    @Test
    void resettingAShapeLeavesTheAttemptLearningItAlone() {
        // Reset is about what was learned, not about who is learning: a promotion resets the shape while
        // the attempt that promoted it is still open, and must not hand the shape to another node (A45).
        final String shape = SHAPE + "-reset";
        attempts.opened(new Attempt(DOC, shape, "F", "T", 1L, "node-1",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();

        shapes.reset(DOC, shape);

        assertThat(attempts.opened(new Attempt(DOC, shape, "F", "T", 2L, "node-2",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now())).isEmpty();
    }

    @Test
    void anAttemptIsCarriedOnByItsOwnNodeOrByWhoeverFindsItLapsed() {
        // A45: taking an attempt up is taking its shape. The heartbeat is what keeps a slow model call
        // from costing a node the shape it is learning.
        final String shape = SHAPE + "-resume";
        final long id = attempts.opened(new Attempt(DOC, shape, "F", "T", 1L, "node-1",
                ExecutionMode.DEFERRED, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.parked(id, AttemptStatus.AWAITING_MODEL, now() + 60_000L, 500L);

        assertThat(attempts.awaiting(10)).extracting(Recorded::id)
                .describedAs("what deferred mode's worker advances").contains(id);
        assertThat(attempts.claimed(id, "node-2", now(), now() + 120_000L))
                .describedAs("a parked attempt is nobody's: any node's worker may take it").isTrue();
        assertThat(attempts.byId(id).orElseThrow().status()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(attempts.awaiting(10)).extracting(Recorded::id)
                .describedAs("and is no longer waiting once taken").doesNotContain(id);
        assertThat(attempts.claimed(id, "node-1", now(), now() + 120_000L))
                .describedAs("but a running attempt is its own node's").isFalse();
        assertThat(attempts.claimed(id, "node-2", now(), now() + 120_000L))
                .describedAs("which carries it on").isTrue();

        attempts.heartbeat(id, now() - 1);
        assertThat(attempts.open(DOC, shape, now())).describedAs("lapsed, and the shape is free").isEmpty();
        assertThat(attempts.claimed(id, "node-1", now(), now() + 60_000L))
                .describedAs("a node that died lets the next one in").isTrue();
        assertThat(attempts.byId(id).orElseThrow().attempt().node()).isEqualTo("node-1");

        // What it spent on each leg is added up, not overwritten, or a resumed attempt's cost is only its
        // last leg's (A5).
        attempts.closed(id, AttemptStatus.PROMOTED, "Promoted 1.0", "rule-3", 1.0, 700L);
        assertThat(attempts.byId(id).orElseThrow().tokensSpent()).isEqualTo(1_200L);
        assertThat(attempts.claimed(id, "node-1", now(), now() + 60_000L))
                .describedAs("a finished attempt is not carried on").isFalse();
        attempts.parked(id, AttemptStatus.AWAITING_MODEL, now() + 60_000L, 1L);
        assertThat(attempts.byId(id).orElseThrow().status())
                .describedAs("nor parked back into life").isEqualTo(AttemptStatus.PROMOTED);
        assertThat(attempts.awaiting(10)).extracting(Recorded::id).doesNotContain(id);
    }

    @Test
    void whatEveryNodeSpendsIsCountedInOnePlace() {
        // A44: a budget divided by node count is not a budget, and a feed burning spend on one node is
        // invisible to the others.
        final long window = 60_000L;
        assertThat(spend.spent(DOC, window).tokens()).isZero();

        final Spent afterOne = spend.record(DOC, 1_500L, 3, window);
        final Spent afterTwo = spend.record(DOC, 2_500L, 4, window);

        assertThat(afterOne.tokens()).isEqualTo(1_500L);
        assertThat(afterTwo.tokens()).describedAs("the second node adds to the first's count")
                .isEqualTo(4_000L);
        assertThat(afterTwo.calls()).isEqualTo(7);
        assertThat(afterTwo.windowStartMs()).isEqualTo(afterOne.windowStartMs());
        assertThat(spend.spent(DOC, window).tokens()).isEqualTo(4_000L);
        assertThat(spend.spent("another-document", window).tokens())
                .describedAs("one document's spend is its own").isZero();

        // A window that has run out starts the count again: what matters is what was spent lately.
        final Spent afresh = spend.spent(DOC, 0L);
        assertThat(afresh.tokens()).isZero();
        assertThat(spend.record(DOC, 100L, 1, 0L).tokens()).isEqualTo(100L);
    }

    @Test
    void anAttemptAndItsTurnsAreRowsThatOutliveTheNode() {
        // A28: what was asked and answered survives the node that asked it, and the turns are readable in
        // the order they were put.
        final long id = attempts.opened(new Attempt(DOC, SHAPE, "DOOR-ACCESS", "Raw Events", 42L, "node-1",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.turn(id, new Turn(1, "chain", 1, QuestionKind.CHAIN, "CHAIN: choose from [DSParser]",
                "DSParser -> XSLTFilter", "local-model", StepOutcome.PASSED));
        attempts.turn(id, new Turn(2, "configure", 2, QuestionKind.CONFIGURE, "CONFIGURE: DSParser",
                "<dataSplitter/>", "local-model", StepOutcome.COVERAGE_SHORT));

        attempts.closed(id, AttemptStatus.PROMOTED, "Promoted 0.97", "rule-1", 0.97, 1_234L);

        final Recorded read = attempts.byId(id).orElseThrow();
        assertThat(read.status()).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(read.decision()).isEqualTo("Promoted 0.97");
        assertThat(read.ruleUuid()).isEqualTo("rule-1");
        assertThat(read.score()).isEqualTo(0.97);
        assertThat(read.tokensSpent()).isEqualTo(1_234L);
        assertThat(read.attempt().shape()).isEqualTo(SHAPE);
        assertThat(read.attempt().inputId()).isEqualTo(42L);
        assertThat(read.attempt().executionMode()).isEqualTo(ExecutionMode.INLINE);
        assertThat(read.attempt().expiryMs())
                .describedAs("a claim that has ended holds nothing (A45)").isZero();
        assertThat(read.turns()).extracting(Turn::number).containsExactly(1, 2);
        assertThat(read.turns().get(1).candidate()).isEqualTo(2);
        assertThat(read.turns().get(1).outcome()).isEqualTo(StepOutcome.COVERAGE_SHORT);
        assertThat(read.turns().get(0).answeredBy()).isEqualTo("local-model");

        // A shape with an id longer than any column is still one attempt, found by its hash.
        final String long1 = SHAPE + "|RemoteFile=" + "a".repeat(2000);
        final long longId = attempts.opened(new Attempt(DOC, long1, null, null, null, "node-1",
                ExecutionMode.DEFERRED, PromotionMode.REVIEW, now()), now()).orElseThrow();
        assertThat(attempts.byId(longId).orElseThrow().attempt().shape()).isEqualTo(long1);
        assertThat(attempts.forDocument(DOC, 10)).extracting(Recorded::id)
                .describedAs("newest first, for the Supervisor view")
                .startsWith(longId, id);
    }

    @Test
    void oneOpenAttemptPerShapeIsWhatOneLearnerMeans() {
        // A45: the attempt row is the claim. A parked attempt is still learning, and one whose expiry has
        // passed has lapsed, so the next node takes the shape.
        final String shape = SHAPE + "-claim";
        final long mine = attempts.opened(new Attempt(DOC, shape, "F", "T", 1L, "node-1",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();

        assertThat(attempts.opened(new Attempt(DOC, shape, "F", "T", 2L, "node-2",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now()))
                .describedAs("one learner per shape").isEmpty();
        assertThat(attempts.open(DOC, shape, now()).orElseThrow().id()).isEqualTo(mine);

        // Parked awaiting the model, it keeps the shape.
        attempts.parked(mine, AttemptStatus.AWAITING_MODEL, now() + 60_000L, 0L);
        assertThat(attempts.open(DOC, shape, now())).isPresent();
        assertThat(attempts.opened(new Attempt(DOC, shape, "F", "T", 3L, "node-2",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now())).isEmpty();

        // Lapsed, and the next node in takes it.
        attempts.parked(mine, AttemptStatus.AWAITING_MODEL, now() - 1, 0L);
        assertThat(attempts.open(DOC, shape, now())).isEmpty();
        final Long theirs = attempts.opened(new Attempt(DOC, shape, "F", "T", 4L, "node-2",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        assertThat(theirs).isNotEqualTo(mine);

        // Closed, it holds nothing, and the shape is free for the attempt after it.
        attempts.closed(theirs, AttemptStatus.PROMOTED, "Promoted 1.0", "rule-2", 1.0, 0L);
        assertThat(attempts.open(DOC, shape, now())).isEmpty();
        assertThat(attempts.opened(new Attempt(DOC, shape, "F", "T", 5L, "node-3",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now())).isPresent();
    }

    @Test
    void aPersonsAnswerReplacesATurnsAndWhatFollowedItGoes() {
        // A28: *answer instead* and *edit and re-run from here*. What came after the turn answered is a
        // consequence of an answer that has changed, and the walk derives it again.
        final String shape = SHAPE + "-amend";
        final long id = attempts.opened(new Attempt(DOC, shape, "F", "T", 1L, "node-1",
                ExecutionMode.DEFERRED, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.turn(id, new Turn(1, "chain", 1, QuestionKind.CHAIN, "Chain: choose from [DSParser]",
                "DSParser", "local-model", StepOutcome.PASSED));
        attempts.turn(id, new Turn(2, "configure", 1, QuestionKind.CONFIGURE, "Configure: DSParser",
                "<dataSplitter/>", "local-model", StepOutcome.COVERAGE_SHORT));
        attempts.turn(id, new Turn(3, "configure", 2, QuestionKind.CONFIGURE, "Configure: DSParser",
                "<dataSplitter/>", "local-model", StepOutcome.COVERAGE_SHORT));
        attempts.closed(id, AttemptStatus.ABANDONED, "No passing configuration", null, null, 10L);

        // Opened again, it takes its shape back.
        assertThat(attempts.reopened(id, now(), now() + 60_000L)).isTrue();
        assertThat(attempts.byId(id).orElseThrow().status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(attempts.byId(id).orElseThrow().decision())
                .describedAs("what it came to before is not what it has come to now").isNull();
        assertThat(attempts.opened(new Attempt(DOC, shape, "F", "T", 2L, "node-2",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now()))
                .describedAs("and holds it against another").isEmpty();
        assertThat(attempts.reopened(id, now(), now() + 60_000L))
                .describedAs("opening an open attempt again is nothing to do").isTrue();

        attempts.amended(id, 2, "<dataSplitter>better</dataSplitter>", "an.operator");

        final Recorded read = attempts.byId(id).orElseThrow();
        assertThat(read.turns()).extracting(Turn::number).containsExactly(1, 2);
        assertThat(read.turns().get(1).answer()).isEqualTo("<dataSplitter>better</dataSplitter>");
        assertThat(read.turns().get(1).answeredBy()).isEqualTo("an.operator");
        assertThat(read.turns().get(1).outcome())
                .describedAs("unjudged: it has not been run yet").isNull();
        assertThat(read.turns().get(0).answeredBy())
                .describedAs("the turns before it are as they were").isEqualTo("local-model");
        assertThat(attempts.awaiting(10)).extracting(Recorded::id).contains(id);

        assertThatThrownBy(() -> attempts.amended(id, 9, "anything", "an.operator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(attempts.byId(id).orElseThrow().turns())
                .describedAs("a turn number that names nothing leaves the transcript as it was")
                .hasSize(2);
        attempts.closed(id, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    @Test
    void anAttemptIsOpenedAgainDespiteAClaimNobodyIsBehind() {
        // A45: an attempt whose node died holds its shape until something releases it. Opening one again
        // releases it, as opening a new one does, or a person could never re-run a shape a dead node had
        // been learning. And the claim of the attempt reopened is pushed out, or the next stream of the
        // shape would sweep away the very answer they had just given.
        final String shape = SHAPE + "-lapsed";
        final long dead = attempts.opened(new Attempt(DOC, shape, "F", "T", 1L, "node-dead",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() - 1), now() - 2).orElseThrow();
        attempts.closed(dead, AttemptStatus.PROMOTED, "Promoted", "rule-x", 1.0, 0L);
        final long mine = attempts.opened(new Attempt(DOC, shape, "F", "T", 2L, "node-1",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() - 1), now() - 2).orElseThrow();
        attempts.turn(mine, new Turn(1, "chain", 1, QuestionKind.CHAIN, "Chain: choose from [DSParser]",
                "DSParser", "local-model", StepOutcome.PASSED));
        attempts.parked(mine, AttemptStatus.AWAITING_MODEL, now() - 1, 0L);
        // A third attempt takes the shape and its node dies holding it.
        final long stopped = attempts.opened(new Attempt(DOC, shape, "F", "T", 3L, "node-dead",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() - 1), now()).orElseThrow();

        assertThat(attempts.reopened(mine, now(), now() + 60_000L))
                .describedAs("the lapsed claim is released, as opening a new attempt would release it")
                .isTrue();

        assertThat(attempts.byId(stopped).orElseThrow().status()).isEqualTo(AttemptStatus.ABANDONED);
        final Recorded reopened = attempts.byId(mine).orElseThrow();
        assertThat(reopened.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(reopened.attempt().expiryMs()).isGreaterThan(now());
        assertThat(attempts.open(DOC, shape, now()).orElseThrow().id()).isEqualTo(mine);
        attempts.closed(mine, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    @Test
    void anAttemptBeingWalkedNowIsNotOpenedAgain() {
        final String shape = SHAPE + "-walking";
        final long id = attempts.opened(new Attempt(DOC, shape, "F", "T", 1L, "node-1",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();

        assertThat(attempts.reopened(id, now(), now() + 60_000L))
                .describedAs("its answers are that walk's to give").isFalse();

        attempts.heartbeat(id, now() - 1);
        assertThat(attempts.reopened(id, now(), now() + 60_000L))
                .describedAs("the walk stopped without finishing, and a person may answer it").isTrue();
        assertThat(attempts.byId(id).orElseThrow().status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        attempts.closed(id, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    @Test
    void anAttemptCannotBeOpenedAgainWhileAnotherHoldsItsShape() {
        final String shape = SHAPE + "-contended";
        final long mine = attempts.opened(new Attempt(DOC, shape, "F", "T", 1L, "node-1",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.closed(mine, AttemptStatus.PROMOTED, "Promoted 1.0", "rule-9", 1.0, 0L);
        final long theirs = attempts.opened(new Attempt(DOC, shape, "F", "T", 2L, "node-2",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();

        assertThat(attempts.reopened(mine, now(), now() + 60_000L))
                .describedAs("a person is told rather than two attempts learning one shape").isFalse();
        assertThat(attempts.byId(mine).orElseThrow().status()).isEqualTo(AttemptStatus.PROMOTED);
        attempts.closed(theirs, AttemptStatus.ABANDONED, "done with", null, null, 0L);
        assertThat(attempts.reopened(mine, now(), now() + 60_000L))
                .describedAs("and may run again once the shape is free").isTrue();
        attempts.closed(mine, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    @Test
    void whatARuleProducedIsFoundWhereverItWasProduced() {
        // Design 01 §7.3 rule 3: retracting a rule means finding the inputs whose outputs it produced,
        // and the node that retracts is rarely the node that produced them. The bindings are on the
        // output stream's attributes too, where a person reads them, but a custom stream attribute is not
        // a field stroom can query.
        final DocRef fragment = PipelineDoc.buildDocRef().uuid("fragment-1").name("door-v1").build();
        outputs.emitted(1L, "pipeline-1", new Bindings(DOC, "rule-1", fragment,
                RecordBoundary.ofElement("Event").atDepth(1), false, 0.97));
        outputs.emitted(2L, "pipeline-2", new Bindings(DOC, "rule-1", fragment, null, false, 0.98));
        outputs.emitted(3L, null, new Bindings(DOC, "rule-2", fragment, null, true, 0.5));

        assertThat(outputs.boundBy("rule-1", "fragment-1"))
                .extracting(Replayable::inputId, Replayable::pipeline)
                .describedAs("each through the pipeline that produced it, which may not be this one")
                .containsExactly(tuple(1L, "pipeline-1"), tuple(2L, "pipeline-2"));
        assertThat(outputs.boundBy("rule-2", "fragment-1")).extracting(Replayable::pipeline)
                .describedAs("a stream no pipeline produced names none").containsExactly((String) null);
        assertThat(outputs.boundBy("rule-3", "fragment-1")).isEmpty();

        // A rule keeps its uuid when it is rebound (§7.3 rule 3), so what an earlier generation produced
        // is not what a retraction of this one asks for.
        final DocRef rebound = PipelineDoc.buildDocRef().uuid("fragment-2").name("door-v2").build();
        outputs.emitted(4L, "pipeline-1", new Bindings(DOC, "rule-1", rebound, null, false, 0.99));
        assertThat(outputs.boundBy("rule-1", "fragment-2")).extracting(Replayable::inputId)
                .describedAs("only what this binding produced").containsExactly(4L);
        assertThat(outputs.boundBy("rule-1", "fragment-1")).extracting(Replayable::inputId)
                .describedAs("and the generation before it is left alone").containsExactly(1L, 2L);

        // A stream processed twice by one pipeline under one rule is one thing to replay; by two
        // pipelines it is two outputs, and a retraction asks for both, because both are still there.
        outputs.emitted(1L, "pipeline-3", new Bindings(DOC, "rule-1", fragment, null, false, 0.99));
        assertThat(outputs.boundBy("rule-1", "fragment-1"))
                .extracting(Replayable::inputId, Replayable::pipeline)
                .containsExactly(tuple(1L, "pipeline-1"), tuple(2L, "pipeline-2"), tuple(1L, "pipeline-3"));
        outputs.emitted(1L, "pipeline-1", new Bindings(DOC, "rule-1", fragment,
                RecordBoundary.ofElement("Event").atDepth(1), false, 0.95));
        assertThat(outputs.boundBy("rule-1", "fragment-1"))
                .describedAs("and the same pipeline again is the same output, said again")
                .hasSize(3);

        // What an as-processed reprocess asks (design 01 §7.3): what was bound when this input was last
        // processed by this pipeline, so that the fragment which produced the output produces it again.
        assertThat(outputs.asProcessed(DOC, 2L, "pipeline-2")).get()
                .extracting(Bindings::ruleUuid, bindings -> bindings.fragment().getUuid(), Bindings::provisional)
                .containsExactly("rule-1", "fragment-1", false);
        assertThat(outputs.asProcessed(DOC, 1L, "pipeline-1")).get()
                .describedAs("under the boundary that produced it, not the rule's boundary today")
                .extracting(bindings -> bindings.boundary().getElement(),
                        bindings -> bindings.boundary().splitDepth().orElseThrow())
                .containsExactly("Event", 1);
        assertThat(outputs.asProcessed(DOC, 1L, "pipeline-3")).describedAs("another pipeline's run of the same "
                                                                     + "input is another output, not a "
                                                                     + "replacement for it")
                .get().extracting(Bindings::score).isEqualTo(0.99);
        assertThat(outputs.asProcessed(DOC, 1L, "pipeline-9")).isEmpty();
        assertThat(outputs.asProcessed(DOC, 3L, null))
                .describedAs("an output of no pipeline is of no pipeline rather than of any")
                .isPresent();
        assertThat(outputs.asProcessed(DOC, 3L, "pipeline-1")).isEmpty();
        assertThat(outputs.asProcessed(DOC, 99L, "pipeline-1"))
                .describedAs("an input nothing remembers cannot be processed again as it was").isEmpty();

        // A pipeline may hold two supervised stages (design 01 §3), and they record what each made of
        // the same input on the same pipeline. Whose stage is asking is what tells the two apart:
        // without it the extraction stage would be answered with the transformation stage's fragment,
        // which would then be run over raw bytes.
        final DocRef transform = PipelineDoc.buildDocRef().uuid("fragment-t").name("transform").build();
        outputs.emitted(2L, "pipeline-2", new Bindings("doc-2", "rule-t", transform, null, false, 0.97));
        assertThat(outputs.asProcessed(DOC, 2L, "pipeline-2")).get()
                .describedAs("the stage that asked, not the one that recorded last")
                .extracting(bindings -> bindings.fragment().getUuid()).isEqualTo("fragment-1");
        assertThat(outputs.asProcessed("doc-2", 2L, "pipeline-2")).get()
                .extracting(bindings -> bindings.fragment().getUuid()).isEqualTo("fragment-t");

        // Where each record began and ended in the stream it was cut from (§12 item 21), so that a
        // fault found at an event is put to the model with the record that made it.
        outputs.emitted(5L, "pipeline-1", new Bindings(DOC, "rule-1", fragment, null, false, 0.9),
                List.of(new TextRange(DefaultLocation.of(2, 1), DefaultLocation.of(2, 40)),
                        new TextRange(DefaultLocation.of(3, 1), DefaultLocation.of(4, 12))));
        assertThat(outputs.span(DOC, 5L, "pipeline-1", 0)).get()
                .extracting(span -> span.getFrom().getLineNo(), span -> span.getTo().getColNo())
                .containsExactly(2, 40);
        assertThat(outputs.span(DOC, 5L, "pipeline-1", 1)).get()
                .describedAs("a record that spans two lines says both")
                .extracting(span -> span.getFrom().getLineNo(), span -> span.getTo().getLineNo())
                .containsExactly(3, 4);
        assertThat(outputs.span(DOC, 5L, "pipeline-1", 2))
                .describedAs("a record past what was cut has no span").isEmpty();
        assertThat(outputs.span(DOC, 5L, "pipeline-9", 0))
                .describedAs("and another pipeline's run is another output").isEmpty();
        assertThat(outputs.span(DOC, 1L, "pipeline-1", 0))
                .describedAs("an output recorded with no spans has none to give").isEmpty();
        // And a later run with nothing to say about the records — an as-processed reprocess, which has
        // no parser to ask — leaves what was recorded where it is.
        outputs.emitted(5L, "pipeline-1", new Bindings(DOC, "rule-1", fragment, null, false, 0.9));
        assertThat(outputs.span(DOC, 5L, "pipeline-1", 0))
                .describedAs("knowing nothing does not erase what was known").isPresent();

        // Kept for as long as a node is told to keep them, and no longer: a row per output stream is a
        // row per stream (design 01 §12 item 8).
        assertThat(outputs.prune(now() - 60_000L)).describedAs("nothing old enough yet").isZero();
        assertThat(outputs.prune(now() + 60_000L)).isGreaterThanOrEqualTo(3);
        assertThat(outputs.boundBy("rule-1", "fragment-1")).isEmpty();
    }

    @Test
    void theSupervisorSeesEveryDocumentsAttemptsNarrowedByWhatAPersonKnows() {
        // A28: the Supervisor is a view of every document's attempts, not a tab on one, filtered by the
        // things a person looking for an attempt would know.
        final String doc = "doc-" + System.nanoTime();
        final String other = "doc-" + System.nanoTime() + "-other";
        final long first = attempts.opened(new Attempt(doc, SHAPE, "DOOR-ACCESS", "Raw Events", 1L, "node-1",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.closed(first, AttemptStatus.PROMOTED, "Promoted 1.0", "rule-1", 1.0, 10L);
        final long second = attempts.opened(new Attempt(doc, SHAPE + "-b", "OTHER-FEED", "Raw Events", 2L,
                "node-2", ExecutionMode.DEFERRED, PromotionMode.REVIEW, now() + 60_000L), now()).orElseThrow();
        final long elsewhere = attempts.opened(new Attempt(other, SHAPE, "DOOR-ACCESS", "Raw Events", 3L,
                "node-1", ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now())
                .orElseThrow();

        assertThat(attempts.found(new AttemptCriteria(), List.of(doc, other)).attempts())
                .extracting(Recorded::id)
                .describedAs("every document the person may see, newest first")
                .contains(elsewhere, second, first);
        assertThat(attempts.found(new AttemptCriteria(), List.of(other)).attempts())
                .extracting(Recorded::id)
                .describedAs("and only those: an attempt of a document they may not see is not theirs to "
                             + "know about")
                .doesNotContain(first, second);
        assertThat(attempts.found(new AttemptCriteria(), List.of()).total())
                .describedAs("nor counted, or the count says how many exist elsewhere").isZero();
        assertThat(found(new AttemptCriteria(null, null, doc, null, null, null, null, null)))
                .containsExactly(second, first);
        assertThat(found(new AttemptCriteria(null, null, doc, "OTHER-FEED", null, null, null, null)))
                .containsExactly(second);
        assertThat(found(new AttemptCriteria(null, null, doc, null, SHAPE, null, null, null)))
                .describedAs("by shape, whose id has no bound and is matched by its hash")
                .containsExactly(first);
        assertThat(found(new AttemptCriteria(null, null, doc, null, null, ExecutionMode.DEFERRED, null, null)))
                .containsExactly(second);
        assertThat(found(new AttemptCriteria(null, null, doc, null, null, null, PromotionMode.REVIEW, null)))
                .containsExactly(second);
        assertThat(found(new AttemptCriteria(null, null, doc, null, null, null, null,
                List.of(AttemptStatus.PROMOTED))))
                .describedAs("and by what it came to").containsExactly(first);

        // A page, and how many there are to page through.
        final Page page = attempts.found(new AttemptCriteria(new PageRequest(0, 1), null, doc, null, null,
                null, null, null), List.of(doc, other));
        assertThat(page.attempts()).hasSize(1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.attempts().get(0).turns())
                .describedAs("without their turns: a page of transcripts is a page nobody reads").isEmpty();
        attempts.closed(second, AttemptStatus.ABANDONED, "done with", null, null, 0L);
        attempts.closed(elsewhere, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    private List<Long> found(final AttemptCriteria criteria) {
        return attempts.found(criteria, List.of(criteria.getDocUuid())).attempts().stream()
                .map(Recorded::id)
                .toList();
    }

    @Test
    void whatIsOverIsPrunedAndWhatIsNotIsKept() {
        // Design 01 §12 item 8: one row per attempt and one per turn is the fastest-growing thing this
        // feature writes. An attempt still learning, still waiting for the model, or waiting for a person
        // to decide is never pruned however old it is: age is not what says an attempt is over.
        final String doc = "doc-" + System.nanoTime() + "-prune";
        final long finished = attempts.opened(new Attempt(doc, SHAPE + "-a", "F", "T", 1L, "node-1",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.turn(finished, new Turn(1, "chain", 1, QuestionKind.CHAIN, "Chain: choose", "DSParser",
                "local-model", StepOutcome.PASSED));
        attempts.closed(finished, AttemptStatus.PROMOTED, "Promoted", "rule-1", 1.0, 0L);
        final long waiting = attempts.opened(new Attempt(doc, SHAPE + "-b", "F", "T", 2L, "node-1",
                ExecutionMode.DEFERRED, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();
        attempts.parked(waiting, AttemptStatus.AWAITING_MODEL, now() + 60_000L, 0L);
        final long reviewing = attempts.opened(new Attempt(doc, SHAPE + "-c", "F", "T", 3L, "node-1",
                ExecutionMode.INLINE, PromotionMode.REVIEW, now() + 60_000L), now()).orElseThrow();
        attempts.closed(reviewing, AttemptStatus.AWAITING_REVIEW, "Drafted", "rule-2", 0.9, 0L);
        final long learning = attempts.opened(new Attempt(doc, SHAPE + "-d", "F", "T", 4L, "node-1",
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L), now()).orElseThrow();

        assertThat(attempts.prune(now() - 60_000L)).describedAs("nothing is old enough yet").isZero();
        final int pruned = attempts.prune(now() + 60_000L);

        assertThat(pruned).isGreaterThanOrEqualTo(1);
        assertThat(attempts.byId(finished)).describedAs("the finished one is gone").isEmpty();
        assertThat(attempts.byId(waiting)).describedAs("the one waiting for the model is not").isPresent();
        assertThat(attempts.byId(reviewing)).describedAs("nor the one waiting for a person").isPresent();
        assertThat(attempts.byId(learning)).describedAs("nor the one still learning").isPresent();

        attempts.closed(waiting, AttemptStatus.ABANDONED, "done with", null, null, 0L);
        attempts.closed(reviewing, AttemptStatus.ABANDONED, "done with", null, null, 0L);
        attempts.closed(learning, AttemptStatus.ABANDONED, "done with", null, null, 0L);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
