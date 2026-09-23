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
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.stage.Shapes;
import stroom.shapeshifter.ai.state.InMemoryRules;
import stroom.shapeshifter.ai.state.InMemoryShapes;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventBus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 01 §12 item 8: what a node holds in front of the rows. The routing table and what a shape has
 * settled into are read for every stream and written only when something is learned, so they are cached
 * and cleared by what writes them — on this node at once, and on every other node by the event that
 * write fires. The rolling scores are not cached at all: they are counted across the cluster.
 */
class TestCachedState {

    private static final String DOC = "doc-1";
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
    void aRoutingTableIsReadOnceAndHeldUntilSomethingWritesIt() {
        rules.append(DOC, RoutingRule.builder().uuid("rule-1").build());
        final int afterWrite = rows.reads;

        rules.forDocument(DOC);
        rules.forDocument(DOC);
        rules.forDocument(DOC);

        assertThat(rows.reads).describedAs("one read of the rows, however many streams route")
                .isEqualTo(afterWrite + 1);
        assertThat(rules.forDocument(DOC)).extracting(RoutingRule::getUuid).containsExactly("rule-1");

        // What this node learns it sees at once, and every other node is told.
        fired.clear();
        rules.append(DOC, RoutingRule.builder().uuid("rule-2").build());

        assertThat(rules.forDocument(DOC)).extracting(RoutingRule::getUuid)
                .describedAs("the node that wrote it is not reading its own stale copy")
                .containsExactly("rule-1", "rule-2");
        assertThat(fired).describedAs("and the others are told to let go")
                .extracting(event -> event.getDocRef().getUuid())
                .containsExactly(DOC);
    }

    @Test
    void anotherNodesWriteReachesThisOneAsAnEvent() {
        rules.append(DOC, RoutingRule.builder().uuid("rule-1").build());
        assertThat(rules.forDocument(DOC)).hasSize(1);
        // Another node appends to the same document: this node sees only the event.
        rows.delegate.append(DOC, RoutingRule.builder().uuid("rule-2").build());

        assertThat(rules.forDocument(DOC)).describedAs("still what it was told").hasSize(1);

        rules.onChange(new EntityEvent(new DocRef(ShapeshifterAiDoc.TYPE, DOC), EntityAction.UPDATE));

        assertThat(rules.forDocument(DOC)).describedAs("and reads again when told to").hasSize(2);
    }

    @Test
    void whatAShapeSettledIntoIsHeldAndTheRollingScoreIsNot() {
        assertThat(shapes.reasonGivenUp(DOC, SHAPE)).isEmpty();
        assertThat(shapes.relearnReason(DOC, SHAPE)).isEmpty();
        assertThat(shapes.draftAwaiting(DOC, SHAPE)).isEmpty();
        final int afterFirst = shapeRows.reads;

        assertThat(shapes.reasonGivenUp(DOC, SHAPE)).isEmpty();
        assertThat(shapes.relearnReason(DOC, SHAPE)).isEmpty();

        assertThat(shapeRows.reads).describedAs("all three answers were read together, once")
                .isEqualTo(afterFirst);

        // Scored on every served stream: never cached, and it does not disturb what is.
        shapes.scored(DOC, SHAPE, 1.0, 6, 10);
        shapes.scored(DOC, SHAPE, 1.0, 6, 10);
        assertThat(shapeRows.scores).isEqualTo(2);
        assertThat(shapeRows.reads).describedAs("and reads nothing back through the cache")
                .isEqualTo(afterFirst);

        // Giving the shape up is a write: this node sees it, and every other is told.
        fired.clear();
        shapes.giveUp(DOC, SHAPE, "Rejected");

        assertThat(shapes.reasonGivenUp(DOC, SHAPE)).contains("Rejected");
        assertThat(fired).extracting(event -> event.getDocRef().getUuid()).containsExactly(DOC);
    }


    @Test
    void anEventSaysToLetGoOfACopyAndNotThatTheDocumentChanged() {
        // The document did not change — its rules are rows of their own (A41) — and saying it did would
        // have every node re-index it and drop its name caches for a rule nobody authored.
        rules.append(DOC, RoutingRule.builder().uuid("rule-1").build());
        shapes.giveUp(DOC, SHAPE, "Rejected");

        assertThat(fired).extracting(EntityEvent::getAction)
                .containsExactly(EntityAction.CLEAR_CACHE, EntityAction.CLEAR_CACHE);
    }

    @Test
    void whatTheCacheHandsOutIsNotTheRowsOwnList() {
        // One list, shared by every routing thread on the node until something invalidates it: a caller
        // that sorted or added to what it was given would corrupt the routing of every stream.
        rules.append(DOC, RoutingRule.builder().uuid("rule-1").build());

        final List<RoutingRule> table = rules.forDocument(DOC);

        assertThatThrownBy(() -> table.add(RoutingRule.builder().uuid("rule-2").build()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(rules.forDocument(DOC)).hasSize(1);
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
        public void fire(final stroom.util.entityevent.EntityEventBatch events) {
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
        public List<RoutingRule> forDocument(final String docUuid) {
            reads++;
            return delegate.forDocument(docUuid);
        }

        @Override
        public java.util.Optional<RoutingRule> byUuid(final String docUuid, final String ruleUuid) {
            return delegate.byUuid(docUuid, ruleUuid);
        }

        @Override
        public RoutingRule append(final String docUuid, final RoutingRule rule) {
            return delegate.append(docUuid, rule);
        }

        @Override
        public RoutingRule insert(final String docUuid, final RoutingRule rule, final int at) {
            return delegate.insert(docUuid, rule, at);
        }

        @Override
        public void move(final String docUuid, final String ruleUuid, final int to) {
            delegate.move(docUuid, ruleUuid, to);
        }

        @Override
        public void replace(final String docUuid, final RoutingRule rule) {
            delegate.replace(docUuid, rule);
        }

        @Override
        public void remove(final String docUuid, final String ruleUuid) {
            delegate.remove(docUuid, ruleUuid);
        }
    }


    // --------------------------------------------------------------------------------


    private static final class CountingShapes implements Shapes {

        private final InMemoryShapes delegate = new InMemoryShapes();
        private int reads;
        private int scores;

        @Override
        public java.util.Optional<String> reasonGivenUp(final String docUuid, final String shape) {
            reads++;
            return delegate.reasonGivenUp(docUuid, shape);
        }

        @Override
        public java.util.OptionalDouble scored(final String docUuid, final String shape, final double score,
                                               final int records, final int memory) {
            scores++;
            return delegate.scored(docUuid, shape, score, records, memory);
        }

        @Override
        public void giveUp(final String docUuid, final String shape, final String reason) {
            delegate.giveUp(docUuid, shape, reason);
        }

        @Override
        public java.util.Optional<String> relearnReason(final String docUuid, final String shape) {
            return delegate.relearnReason(docUuid, shape);
        }

        @Override
        public void markForRelearning(final String docUuid, final String shape, final String reason) {
            delegate.markForRelearning(docUuid, shape, reason);
        }

        @Override
        public void awaitReview(final String docUuid, final String shape, final String ruleUuid) {
            delegate.awaitReview(docUuid, shape, ruleUuid);
        }

        @Override
        public java.util.Optional<String> draftAwaiting(final String docUuid, final String shape) {
            return delegate.draftAwaiting(docUuid, shape);
        }

        @Override
        public java.util.Optional<String> shapeAwaiting(final String docUuid, final String ruleUuid) {
            return delegate.shapeAwaiting(docUuid, ruleUuid);
        }

        @Override
        public void reset(final String docUuid, final String shape) {
            delegate.reset(docUuid, shape);
        }

        @Override
        public java.util.Optional<Rolling> rolling(final String docUuid, final String shape) {
            return delegate.rolling(docUuid, shape);
        }
    }
}
