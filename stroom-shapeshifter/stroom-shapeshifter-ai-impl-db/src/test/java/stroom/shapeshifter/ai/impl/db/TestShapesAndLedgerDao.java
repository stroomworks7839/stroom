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
    void oneNodeLearnsAShapeAndTheOtherIsToldToGoAway() {
        // A42: the row is the single point of truth for who is learning what, so the database decides the
        // race and not either node.
        final long until = System.currentTimeMillis() + 60_000L;

        assertThat(shapes.lease(DOC, SHAPE, "node-1", now(), until)).isTrue();
        assertThat(shapes.lease(DOC, SHAPE, "node-2", now(), until)).describedAs("one learner per shape").isFalse();
        assertThat(shapes.lease(DOC, SHAPE, "node-1", now(), until + 60_000L))
                .describedAs("the holder may take it again, which is the heartbeat").isTrue();
        assertThat(shapes.lease(DOC, "another-shape", "node-2", now(), until))
                .describedAs("a different shape is a different lease").isTrue();

        shapes.releaseLease(DOC, SHAPE, "node-2");
        assertThat(shapes.lease(DOC, SHAPE, "node-2", now(), until))
                .describedAs("a node cannot release what it does not hold").isFalse();
        shapes.releaseLease(DOC, SHAPE, "node-1");
        assertThat(shapes.lease(DOC, SHAPE, "node-2", now(), until)).isTrue();
        shapes.releaseLease(DOC, SHAPE, "node-2");
        shapes.releaseLease(DOC, "another-shape", "node-2");
    }

    @Test
    void aLeaseWhoseHolderDiedIsFreeWhenItExpires() {
        assertThat(shapes.lease(DOC, SHAPE, "node-1", now(), now() - 1)).isTrue();

        assertThat(shapes.lease(DOC, SHAPE, "node-2", now(), now() + 60_000L))
                .describedAs("an expired lease is free: a node that died lets the next one in")
                .isTrue();
        shapes.releaseLease(DOC, SHAPE, "node-2");
    }

    @Test
    void resettingAShapeLeavesTheLeaseAlone() {
        // Reset is about what was learned, not about who is learning: a promotion resets the shape while
        // the attempt that promoted it still holds its lease, and must not hand it to another node.
        assertThat(shapes.lease(DOC, SHAPE, "node-1", now(), now() + 60_000L)).isTrue();

        shapes.reset(DOC, SHAPE);

        assertThat(shapes.lease(DOC, SHAPE, "node-2", now(), now() + 60_000L)).isFalse();
        shapes.releaseLease(DOC, SHAPE, "node-1");
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
                ExecutionMode.INLINE, PromotionMode.AUTOMATIC, now() + 60_000L));
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
                ExecutionMode.DEFERRED, PromotionMode.REVIEW, now()));
        assertThat(attempts.byId(longId).orElseThrow().attempt().shape()).isEqualTo(long1);
        assertThat(attempts.forDocument(DOC, 10)).extracting(Recorded::id)
                .describedAs("newest first, for the Supervisor view")
                .startsWith(longId, id);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
