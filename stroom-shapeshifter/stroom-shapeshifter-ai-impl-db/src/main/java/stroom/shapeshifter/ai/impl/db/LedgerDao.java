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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

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
    public void sentinelled(final String docUuid, final String shape, final long inputId, final String reason) {
        JooqUtil.context(connProvider, context -> context
                .insertInto(SHAPESHIFTER_LEDGER)
                .set(SHAPESHIFTER_LEDGER.CREATE_TIME_MS, System.currentTimeMillis())
                .set(SHAPESHIFTER_LEDGER.DOC_UUID, docUuid)
                .set(SHAPESHIFTER_LEDGER.SHAPE_HASH, ShapesDao.hash(shape))
                .set(SHAPESHIFTER_LEDGER.SHAPE_ID, shape)
                .set(SHAPESHIFTER_LEDGER.INPUT_META_ID, inputId)
                .set(SHAPESHIFTER_LEDGER.REASON, reason)
                // A stream sentinelled twice for one shape is one row: it must not be replayed twice.
                .onDuplicateKeyIgnore()
                .execute());
    }

    /// Read then delete in one transaction: two nodes promoting the same shape must not both release the
    /// same inputs, or the backlog is reprocessed twice.
    @Override
    public List<Long> release(final String docUuid, final String shape) {
        return JooqUtil.transactionResult(connProvider, context -> {
            final List<Long> inputs = context
                    .select(SHAPESHIFTER_LEDGER.INPUT_META_ID)
                    .from(SHAPESHIFTER_LEDGER)
                    .where(SHAPESHIFTER_LEDGER.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_LEDGER.SHAPE_HASH.eq(ShapesDao.hash(shape)))
                    .orderBy(SHAPESHIFTER_LEDGER.ID)
                    .forUpdate()
                    .fetch(SHAPESHIFTER_LEDGER.INPUT_META_ID);
            if (!inputs.isEmpty()) {
                context.deleteFrom(SHAPESHIFTER_LEDGER)
                        .where(SHAPESHIFTER_LEDGER.DOC_UUID.eq(docUuid))
                        .and(SHAPESHIFTER_LEDGER.SHAPE_HASH.eq(ShapesDao.hash(shape)))
                        .execute();
            }
            return List.copyOf(inputs);
        });
    }
}
