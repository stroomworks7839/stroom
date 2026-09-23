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

import stroom.cache.api.CacheManager;
import stroom.cache.api.LoadingStroomCache;
import stroom.docref.DocRef;
import stroom.shapeshifter.ai.ShapeshifterAiConfig;
import stroom.shapeshifter.ai.stage.Shapes;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventBus;
import stroom.util.entityevent.EntityEventHandler;
import stroom.util.shared.Clearable;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.OptionalDouble;

/// What a shape has settled into, in front of the rows (design 01 §12 item 8): given up, marked for
/// relearning, or awaiting a person's review. Every stream of every shape asks all three before it is
/// routed or learned, and they change only when a shape settles — three queries per stream per node,
/// otherwise, for an answer that is almost always "none of them".
///
/// The rolling scores are deliberately *not* cached. They are a count across the cluster (A44's
/// reasoning applied to A23's scores): every served stream adds to them, so a node's own copy would be
/// wrong the moment another node served a stream, and caching a counter that every stream writes saves
/// nothing.
@Singleton
@EntityEventHandler(type = ShapeshifterAiDoc.TYPE)
public class CachedShapes implements Shapes, Clearable, EntityEvent.Handler {

    private static final String CACHE_NAME = "Shapeshifter AI Shape State";

    private final Shapes rows;
    private final EntityEventBus entityEventBus;
    private final LoadingStroomCache<Key, Settled> cache;

    @Inject
    public CachedShapes(@Rows final Shapes rows,
                        final CacheManager cacheManager,
                        final EntityEventBus entityEventBus,
                        final Provider<ShapeshifterAiConfig> configProvider) {
        this.rows = rows;
        this.entityEventBus = entityEventBus;
        this.cache = cacheManager.createLoadingCache(
                CACHE_NAME,
                () -> configProvider.get().getShapeCache(),
                key -> new Settled(
                        rows.reasonGivenUp(key.docUuid(), key.shape()),
                        rows.relearnReason(key.docUuid(), key.shape()),
                        rows.draftAwaiting(key.docUuid(), key.shape())));
    }

    @Override
    public Optional<String> reasonGivenUp(final String docUuid, final String shape) {
        return cache.get(new Key(docUuid, shape)).givenUp();
    }

    @Override
    public Optional<String> relearnReason(final String docUuid, final String shape) {
        return cache.get(new Key(docUuid, shape)).relearn();
    }

    @Override
    public Optional<String> draftAwaiting(final String docUuid, final String shape) {
        return cache.get(new Key(docUuid, shape)).draft();
    }

    @Override
    public void giveUp(final String docUuid, final String shape, final String reason) {
        rows.giveUp(docUuid, shape, reason);
        changed(docUuid, shape);
    }

    @Override
    public void markForRelearning(final String docUuid, final String shape, final String reason) {
        rows.markForRelearning(docUuid, shape, reason);
        changed(docUuid, shape);
    }

    @Override
    public void awaitReview(final String docUuid, final String shape, final String ruleUuid) {
        rows.awaitReview(docUuid, shape, ruleUuid);
        changed(docUuid, shape);
    }

    @Override
    public void reset(final String docUuid, final String shape) {
        rows.reset(docUuid, shape);
        changed(docUuid, shape);
    }

    /// Straight to the rows: a count every served stream adds to, which no node may hold its own copy
    /// of, and which changes none of what is cached.
    @Override
    public OptionalDouble scored(final String docUuid, final String shape, final double score,
                                 final int records, final int memory) {
        return rows.scored(docUuid, shape, score, records, memory);
    }

    /// Straight to the rows, for the same reason [#scored] is: a count every served stream adds to.
    @Override
    public Optional<Rolling> rolling(final String docUuid, final String shape) {
        return rows.rolling(docUuid, shape);
    }

    /// A reverse lookup for a person approving or rejecting a draft, not for the hot path.
    @Override
    public Optional<String> shapeAwaiting(final String docUuid, final String ruleUuid) {
        return rows.shapeAwaiting(docUuid, ruleUuid);
    }

    @Override
    public void clear() {
        cache.clear();
    }

    @Override
    public void onChange(final EntityEvent event) {
        // One document's worth at a time, not one shape's: a document's shapes are few enough to drop
        // together, and the event that says one settled cannot name which without an event per settling.
        final String docUuid = event.getDocRef() == null
                ? null
                : event.getDocRef().getUuid();
        if (docUuid == null) {
            clear();
        } else {
            cache.invalidateEntries((key, settled) -> key.docUuid().equals(docUuid));
        }
    }

    /// `CLEAR_CACHE` and not `UPDATE`, which means the document itself changed: what a shape has settled
    /// into is a row of the feature's own, and telling the product the document changed would re-index it
    /// on every node each time a shape was marked.
    private void changed(final String docUuid, final String shape) {
        cache.invalidate(new Key(docUuid, shape));
        entityEventBus.fire(new EntityEvent(
                new DocRef(ShapeshifterAiDoc.TYPE, docUuid), EntityAction.CLEAR_CACHE));
    }


    // --------------------------------------------------------------------------------


    private record Key(String docUuid, String shape) {

    }

    /// What a shape has settled into: three answers read together, since every stream asks for all three.
    private record Settled(Optional<String> givenUp, Optional<String> relearn, Optional<String> draft) {

    }
}
