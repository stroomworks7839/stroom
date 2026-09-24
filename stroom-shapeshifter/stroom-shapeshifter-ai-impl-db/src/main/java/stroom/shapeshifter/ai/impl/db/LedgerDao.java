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

import stroom.db.util.JooqUtil;
import stroom.shapeshifter.ai.stage.Ledger;
import stroom.shapeshifter.ai.stage.Ledger.Page;
import stroom.shapeshifter.ai.stage.Replayable;
import stroom.shapeshifter.shared.LedgerShape;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.Collection;
import java.util.List;

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterLedger.SHAPESHIFTER_LEDGER;

/// The ledger of §5.2 as rows: one per sentinelled input, so that promotion can name what to replay.
/// Nothing is held — the row says which stream to reprocess, not what it held.
@Singleton
public class LedgerDao implements Ledger {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    LedgerDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public void sentinelled(final String docUuid,
                            final String shape,
                            final long inputId,
                            final String pipeline,
                            final String reason) {
        JooqUtil.context(connProvider, context -> context
                .insertInto(SHAPESHIFTER_LEDGER)
                .set(SHAPESHIFTER_LEDGER.CREATE_TIME_MS, System.currentTimeMillis())
                .set(SHAPESHIFTER_LEDGER.DOC_UUID, docUuid)
                .set(SHAPESHIFTER_LEDGER.SHAPE_HASH, ShapesDao.hash(shape))
                .set(SHAPESHIFTER_LEDGER.SHAPE_ID, shape)
                .set(SHAPESHIFTER_LEDGER.INPUT_META_ID, inputId)
                .set(SHAPESHIFTER_LEDGER.PIPELINE_UUID, pipeline)
                .set(SHAPESHIFTER_LEDGER.REASON, reason)
                // A stream sentinelled twice for one shape is one row: it must not be replayed twice.
                .onDuplicateKeyIgnore()
                .execute());
    }

    /// Read then delete in one transaction: two nodes promoting the same shape must not both release the
    /// same inputs, or the backlog is reprocessed twice.
    /// One statement over the shape's rows: the reason changes and nothing else does. The time each was
    /// sentinelled is left alone — a stream has been waiting since it arrived, and a new reason does not
    /// make it newly late — which is also what keeps the view's "waiting since" honest.
    @Override
    public void restate(final String docUuid, final String shape, final String reason) {
        JooqUtil.context(connProvider, context -> context
                .update(SHAPESHIFTER_LEDGER)
                .set(SHAPESHIFTER_LEDGER.REASON, reason)
                .where(SHAPESHIFTER_LEDGER.DOC_UUID.eq(docUuid))
                .and(SHAPESHIFTER_LEDGER.SHAPE_HASH.eq(ShapesDao.hash(shape)))
                .execute());
    }

    @Override
    public List<Replayable> release(final String docUuid, final String shape) {
        return JooqUtil.transactionResult(connProvider, context -> {
            final List<Replayable> inputs = context
                    .select(SHAPESHIFTER_LEDGER.INPUT_META_ID, SHAPESHIFTER_LEDGER.PIPELINE_UUID)
                    .from(SHAPESHIFTER_LEDGER)
                    .where(SHAPESHIFTER_LEDGER.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_LEDGER.SHAPE_HASH.eq(ShapesDao.hash(shape)))
                    .orderBy(SHAPESHIFTER_LEDGER.ID)
                    .forUpdate()
                    .fetch(row -> new Replayable(row.get(SHAPESHIFTER_LEDGER.INPUT_META_ID),
                            row.get(SHAPESHIFTER_LEDGER.PIPELINE_UUID)));
            if (!inputs.isEmpty()) {
                context.deleteFrom(SHAPESHIFTER_LEDGER)
                        .where(SHAPESHIFTER_LEDGER.DOC_UUID.eq(docUuid))
                        .and(SHAPESHIFTER_LEDGER.SHAPE_HASH.eq(ShapesDao.hash(shape)))
                        .execute();
            }
            return List.copyOf(inputs);
        });
    }

    /// One row per shape in one round trip: the counts and the times from a grouping, and the reason
    /// from the newest row of each group, which the grouping names by its id.
    ///
    /// A shape with ten thousand waiting streams is one row here rather than ten thousand, which is the
    /// whole reason the view is grouped by shape; and a page of shapes is a page, because a document may
    /// have more of them than anybody wants to scroll.
    @Override
    public Page waiting(final Collection<String> docUuids, final long offset, final int limit) {
        if (NullSafe.isEmptyCollection(docUuids)) {
            return new Page(List.of(), 0L);
        }
        final Field<Integer> waiting = DSL.count().as("waiting");
        final Field<Long> oldest = DSL.min(SHAPESHIFTER_LEDGER.CREATE_TIME_MS).as("oldest");
        final Field<Long> newest = DSL.max(SHAPESHIFTER_LEDGER.CREATE_TIME_MS).as("newest");
        final Field<Long> newestId = DSL.max(SHAPESHIFTER_LEDGER.ID).as("newest_id");
        // Grouped by document as well as shape: the view is over every document (A28), and two
        // documents may have shapes of the same name that settle separately.
        final Table<?> grouped = DSL
                .select(SHAPESHIFTER_LEDGER.DOC_UUID, SHAPESHIFTER_LEDGER.SHAPE_HASH,
                        waiting, oldest, newest, newestId)
                .from(SHAPESHIFTER_LEDGER)
                .where(SHAPESHIFTER_LEDGER.DOC_UUID.in(docUuids))
                .groupBy(SHAPESHIFTER_LEDGER.DOC_UUID, SHAPESHIFTER_LEDGER.SHAPE_HASH)
                .asTable("grouped");
        return JooqUtil.contextResult(connProvider, context -> {
            final List<LedgerShape> shapes = context
                    .select(SHAPESHIFTER_LEDGER.DOC_UUID,
                            SHAPESHIFTER_LEDGER.SHAPE_ID,
                            SHAPESHIFTER_LEDGER.REASON,
                            grouped.field(waiting),
                            grouped.field(oldest),
                            grouped.field(newest))
                    .from(grouped)
                    .join(SHAPESHIFTER_LEDGER).on(SHAPESHIFTER_LEDGER.ID.eq(grouped.field(newestId)))
                    // Newest first, and where two shapes were last added to in the same millisecond —
                    // which is routine — the one whose newest row was written last. Without a
                    // tiebreaker the order is whatever the database felt like, and an order like that
                    // cannot be held to.
                    .orderBy(grouped.field(newest).desc(), grouped.field(newestId).desc())
                    .limit(offset, limit)
                    .fetch(row -> new LedgerShape(
                            ShapeshifterAiDoc.buildDocRef()
                                    .uuid(row.get(SHAPESHIFTER_LEDGER.DOC_UUID))
                                    .build(),
                            row.get(SHAPESHIFTER_LEDGER.SHAPE_ID),
                            row.get(grouped.field(waiting)),
                            row.get(grouped.field(oldest)),
                            row.get(grouped.field(newest)),
                            row.get(SHAPESHIFTER_LEDGER.REASON)));
            // How many shapes in all, so that a pager can say how far there is to go.
            final long total = context
                    .select(DSL.countDistinct(SHAPESHIFTER_LEDGER.DOC_UUID, SHAPESHIFTER_LEDGER.SHAPE_HASH))
                    .from(SHAPESHIFTER_LEDGER)
                    .where(SHAPESHIFTER_LEDGER.DOC_UUID.in(docUuids))
                    .fetchOptional()
                    .map(Record1::value1)
                    .orElse(0);
            return new Page(shapes, total);
        });
    }
}
