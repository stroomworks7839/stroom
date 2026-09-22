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

import stroom.shapeshifter.ai.stage.Attempts.Attempt;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.ai.stage.Spend.Spent;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepOutcome;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

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
    void theLedgerNamesWhatToReplayAndReleasesItOnce() {
        ledger.sentinelled(DOC, SHAPE, 1L, "Unknown shape");
        ledger.sentinelled(DOC, SHAPE, 2L, "Unknown shape");
        ledger.sentinelled(DOC, "another-shape", 3L, "Unknown shape");

        final List<Long> released = ledger.release(DOC, SHAPE);

        assertThat(released).describedAs("oldest first: the backlog is replayed in order").containsExactly(1L, 2L);
        assertThat(ledger.release(DOC, SHAPE))
                .describedAs("a second release finds nothing: two nodes must not both replay it")
                .isEmpty();
        assertThat(ledger.release(DOC, "another-shape")).containsExactly(3L);
    }

    @Test
    void aStreamSentinelledTwiceIsReplayedOnce() {
        ledger.sentinelled(DOC, SHAPE, 1L, "Unknown shape");
        ledger.sentinelled(DOC, SHAPE, 1L, "Unknown shape, again");

        assertThat(ledger.release(DOC, SHAPE)).containsExactly(1L);
    }

    @Test
    void aShapeIdLongerThanAColumnIsStillOneShape() {
        // A learning key may name a sender-supplied header, so an id has no bound; rows are found by its
        // hash and the id is kept as written.
        final String long1 = SHAPE + "|RemoteFile=" + "a".repeat(2000);
        final String long2 = SHAPE + "|RemoteFile=" + "b".repeat(2000);

        shapes.giveUp(DOC, long1, "too long to lose");
        ledger.sentinelled(DOC, long1, 7L, "Unknown shape");

        assertThat(shapes.reasonGivenUp(DOC, long1)).contains("too long to lose");
        assertThat(shapes.reasonGivenUp(DOC, long2)).describedAs("a different long id is a different shape")
                .isEmpty();
        assertThat(ledger.release(DOC, long1)).containsExactly(7L);
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

        assertThat(attempts.claimed(id, "node-2", now(), now() + 60_000L))
                .describedAs("another node's live claim is not free to take").isFalse();
        assertThat(attempts.claimed(id, "node-1", now(), now() + 120_000L))
                .describedAs("its own node carries it on").isTrue();
        assertThat(attempts.byId(id).orElseThrow().status()).isEqualTo(AttemptStatus.IN_PROGRESS);

        attempts.heartbeat(id, now() - 1);
        assertThat(attempts.open(DOC, shape, now())).describedAs("lapsed, and the shape is free").isEmpty();
        assertThat(attempts.claimed(id, "node-2", now(), now() + 60_000L))
                .describedAs("a node that died lets the next one in").isTrue();
        assertThat(attempts.byId(id).orElseThrow().attempt().node()).isEqualTo("node-2");

        // What it spent on each leg is added up, not overwritten, or a resumed attempt's cost is only its
        // last leg's (A5).
        attempts.closed(id, AttemptStatus.PROMOTED, "Promoted 1.0", "rule-3", 1.0, 700L);
        assertThat(attempts.byId(id).orElseThrow().tokensSpent()).isEqualTo(1_200L);
        assertThat(attempts.claimed(id, "node-2", now(), now() + 60_000L))
                .describedAs("a finished attempt is not carried on").isFalse();
        attempts.parked(id, AttemptStatus.AWAITING_MODEL, now() + 60_000L, 1L);
        assertThat(attempts.byId(id).orElseThrow().status())
                .describedAs("nor parked back into life").isEqualTo(AttemptStatus.PROMOTED);
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

    private static long now() {
        return System.currentTimeMillis();
    }
}
