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

package stroom.shapeshifter.ai.cache;

import stroom.cache.impl.CacheManagerImpl;
import stroom.docref.DocRef;
import stroom.shapeshifter.ai.ShapeshifterAiConfig;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.stage.Shapes;
import stroom.shapeshifter.ai.state.InMemoryRules;
import stroom.shapeshifter.ai.state.InMemoryShapes;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventBatch;
import stroom.util.entityevent.EntityEventBus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 01 §12 item 8: what a node holds in front of the rows. A learner's bindings and what its shapes
 * have settled into are read for every stream and written only when something is learned, so they are
 * cached and cleared by what writes them — on this node at once, and on every other node by the event
 * that write fires, which names the learner's pipeline (design 06 phase J slice 2b). The rolling scores
 * are not cached at all: they are counted across the cluster.
 */
class TestCachedState {

    private static final String DOC = "doc-1";
    private static final Learner LEARNER = new Learner("pipeline-1", "shapeshifterAi");
    private static final Learner NEIGHBOUR = new Learner("pipeline-2", "shapeshifterAi");
    private static final String SHAPE = "Feed=DOOR-ACCESS|Type=Raw Events";

    private final List<EntityEvent> fired = new ArrayList<>();
    private final EntityEventBus bus = new RecordingBus(fired);
    private final CacheManagerImpl cacheManager = new CacheManagerImpl();
    private final CountingRules rows = new CountingRules();
    private final CountingShapes shapeRows = new CountingShapes();
    private final CachedRules rules = new CachedRules(rows, cacheManager, bus, ShapeshifterAiConfig::new);
    private final CachedShapes shapes = new CachedShapes(shapeRows, cacheManager, bus,
            ShapeshifterAiConfig::new);

    @Test
    void aLearnersBindingsAreReadOnceAndHeldUntilSomethingWritesThem() {
        rules.append(LEARNER, DOC, RoutingRule.builder().uuid("rule-1").build());
        final int afterWrite = rows.reads;

        rules.forLearner(LEARNER);
        rules.forLearner(LEARNER);
        rules.forLearner(LEARNER);

        assertThat(rows.reads).describedAs("one read of the rows, however many streams are served")
                .isEqualTo(afterWrite + 1);
        assertThat(rules.forLearner(LEARNER)).extracting(RoutingRule::getUuid).containsExactly("rule-1");

        // What this node learns it sees at once, and every other node is told.
        fired.clear();
        rules.append(LEARNER, DOC, RoutingRule.builder().uuid("rule-2").build());

        assertThat(rules.forLearner(LEARNER)).extracting(RoutingRule::getUuid)
                .describedAs("the node that wrote it is not reading its own stale copy")
                .containsExactly("rule-1", "rule-2");
        assertThat(fired).describedAs("and the others are told to let go of the learner's pipeline")
                .extracting(event -> event.getDocRef().getUuid())
                .containsExactly(LEARNER.pipelineUuid());
    }

    @Test
    void anotherNodesWriteReachesThisOneAsAnEvent() {
        rules.append(LEARNER, DOC, RoutingRule.builder().uuid("rule-1").build());
        rules.append(NEIGHBOUR, DOC, RoutingRule.builder().uuid("rule-n").build());
        assertThat(rules.forLearner(LEARNER)).hasSize(1);
        assertThat(rules.forLearner(NEIGHBOUR)).hasSize(1);
        // Another node appends to the same learner: this node sees only the event.
        rows.delegate.append(LEARNER, DOC, RoutingRule.builder().uuid("rule-2").build());
        final int beforeEvent = rows.reads;

        assertThat(rules.forLearner(LEARNER)).describedAs("still what it was told").hasSize(1);

        rules.onChange(new EntityEvent(new DocRef(Learner.EVENT_TYPE, LEARNER.pipelineUuid()),
                EntityAction.CLEAR_CACHE));

        assertThat(rules.forLearner(LEARNER)).describedAs("and reads again when told to").hasSize(2);
        assertThat(rules.forLearner(NEIGHBOUR)).describedAs("another pipeline's is left as it was").hasSize(1);
        assertThat(rows.reads).describedAs("only the learner named was read again").isEqualTo(beforeEvent + 1);
    }

    @Test
    void whatAShapeSettledIntoIsHeldAndTheRollingScoreIsNot() {
        assertThat(shapes.reasonGivenUp(LEARNER, SHAPE)).isEmpty();
        assertThat(shapes.relearnReason(LEARNER, SHAPE)).isEmpty();
        assertThat(shapes.draftAwaiting(LEARNER, SHAPE)).isEmpty();
        final int afterFirst = shapeRows.reads;

        assertThat(shapes.reasonGivenUp(LEARNER, SHAPE)).isEmpty();
        assertThat(shapes.relearnReason(LEARNER, SHAPE)).isEmpty();

        assertThat(shapeRows.reads).describedAs("all three answers were read together, once")
                .isEqualTo(afterFirst);

        // Scored on every served stream: never cached, and it does not disturb what is.
        shapes.scored(LEARNER, DOC, SHAPE, 1.0, 6, 10);
        shapes.scored(LEARNER, DOC, SHAPE, 1.0, 6, 10);
        assertThat(shapeRows.scores).isEqualTo(2);
        assertThat(shapeRows.reads).describedAs("and reads nothing back through the cache")
                .isEqualTo(afterFirst);

        // Giving the shape up is a write: this node sees it, and every other is told.
        fired.clear();
        shapes.giveUp(LEARNER, DOC, SHAPE, "Rejected");

        assertThat(shapes.reasonGivenUp(LEARNER, SHAPE)).contains("Rejected");
        assertThat(shapes.reasonGivenUp(NEIGHBOUR, SHAPE)).describedAs("another learner's shape is its own")
                .isEmpty();
        assertThat(fired).extracting(event -> event.getDocRef().getUuid()).containsExactly(LEARNER.pipelineUuid());
    }

    @Test
    void anEventIsOfTheLearnersOwnTypeAndOnlySaysToLetGoOfACopy() {
        // Of the learner's own type: an event on the pipeline would have every node drop its merged
        // pipeline data. And CLEAR_CACHE, since nothing a person authored has changed.
        rules.append(LEARNER, DOC, RoutingRule.builder().uuid("rule-1").build());
        shapes.giveUp(LEARNER, DOC, SHAPE, "Rejected");

        assertThat(fired).extracting(EntityEvent::getAction)
                .containsExactly(EntityAction.CLEAR_CACHE, EntityAction.CLEAR_CACHE);
        assertThat(fired).extracting(event -> event.getDocRef().getType())
                .containsOnly(Learner.EVENT_TYPE);
    }

    @Test
    void whatTheCacheHandsOutIsNotTheRowsOwnList() {
        // One list, shared by every serving thread on the node until something invalidates it: a caller
        // that sorted or added to what it was given would corrupt the serving of every stream.
        rules.append(LEARNER, DOC, RoutingRule.builder().uuid("rule-1").build());

        final List<RoutingRule> bindings = rules.forLearner(LEARNER);

        assertThatThrownBy(() -> bindings.add(RoutingRule.builder().uuid("rule-2").build()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(rules.forLearner(LEARNER)).hasSize(1);
    }


    // --------------------------------------------------------------------------------


    private static final class RecordingBus implements EntityEventBus {

        private final List<EntityEvent> fired;

        private RecordingBus(final List<EntityEvent> fired) {
            this.fired = fired;
        }

        @Override
        public void fire(final EntityEvent event) {
            fired.add(event);
        }

        @Override
        public void fire(final EntityEventBatch events) {
            fired.addAll(events.getEntityEvents());
        }
    }


    // --------------------------------------------------------------------------------


    /// The rows, counting what is asked of them: what a cache is for is that this number does not grow
    /// with the number of streams.
    private static final class CountingRules implements Rules {

        private final InMemoryRules delegate = new InMemoryRules();
        private int reads;

        @Override
        public List<RoutingRule> forLearner(final Learner learner) {
            reads++;
            return delegate.forLearner(learner);
        }

        @Override
        public Optional<Held> byUuid(final String ruleUuid) {
            return delegate.byUuid(ruleUuid);
        }

        @Override
        public RoutingRule append(final Learner learner, final String docUuid, final RoutingRule rule) {
            return delegate.append(learner, docUuid, rule);
        }

        @Override
        public void replace(final Learner learner, final String docUuid, final RoutingRule rule) {
            delegate.replace(learner, docUuid, rule);
        }

        @Override
        public void remove(final Learner learner, final String ruleUuid) {
            delegate.remove(learner, ruleUuid);
        }
    }


    // --------------------------------------------------------------------------------


    private static final class CountingShapes implements Shapes {

        private final InMemoryShapes delegate = new InMemoryShapes();
        private int reads;
        private int scores;

        @Override
        public Optional<String> reasonGivenUp(final Learner learner, final String shape) {
            reads++;
            return delegate.reasonGivenUp(learner, shape);
        }

        @Override
        public OptionalDouble scored(final Learner learner, final String docUuid, final String shape,
                                     final double score, final int records, final int memory) {
            scores++;
            return delegate.scored(learner, docUuid, shape, score, records, memory);
        }

        @Override
        public void giveUp(final Learner learner, final String docUuid, final String shape, final String reason) {
            delegate.giveUp(learner, docUuid, shape, reason);
        }

        @Override
        public Optional<String> relearnReason(final Learner learner, final String shape) {
            return delegate.relearnReason(learner, shape);
        }

        @Override
        public void markForRelearning(final Learner learner, final String docUuid, final String shape,
                                      final String reason) {
            delegate.markForRelearning(learner, docUuid, shape, reason);
        }

        @Override
        public void awaitReview(final Learner learner, final String docUuid, final String shape,
                                final String ruleUuid) {
            delegate.awaitReview(learner, docUuid, shape, ruleUuid);
        }

        @Override
        public Optional<String> draftAwaiting(final Learner learner, final String shape) {
            return delegate.draftAwaiting(learner, shape);
        }

        @Override
        public Optional<String> shapeAwaiting(final Learner learner, final String ruleUuid) {
            return delegate.shapeAwaiting(learner, ruleUuid);
        }

        @Override
        public void reset(final Learner learner, final String shape) {
            delegate.reset(learner, shape);
        }

        @Override
        public Optional<Rolling> rolling(final Learner learner, final String shape) {
            return delegate.rolling(learner, shape);
        }
    }
}
