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

package stroom.shapeshifter.ai.impl.db;

import stroom.db.util.JooqUtil;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterOutput;
import stroom.shapeshifter.ai.stage.Bindings;
import stroom.shapeshifter.ai.stage.Outputs;
import stroom.shapeshifter.ai.stage.Replayable;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.impl.DSL;

import java.util.List;


/// What each rule produced, as rows (design 01 §7.3 rule 3, A26): retracting a rule means finding the
/// inputs whose outputs it produced, and the node that retracts is rarely the node that produced them.
///
/// The bindings are on the output stream's attributes too, where a person reads them; a custom stream
/// attribute is not a field stroom can query, so what a retraction must find is kept here as well.
@Singleton
public class OutputsDao implements Outputs {

    /// How many rows one pass removes: a delete of every old row at once would hold locks across the
    /// table, and the job runs again.
    private static final int PRUNE_BATCH = 1000;

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    OutputsDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public void emitted(final long inputId, final String pipeline, final Bindings bindings) {
        JooqUtil.context(connProvider, context -> context
                .insertInto(ShapeshifterOutput.SHAPESHIFTER_OUTPUT)
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.CREATE_TIME_MS, System.currentTimeMillis())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.DOC_UUID, bindings.docUuid())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.RULE_UUID, bindings.ruleUuid())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.INPUT_META_ID, inputId)
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PIPELINE_UUID, pipeline)
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.FRAGMENT_UUID,
                        bindings.fragment().getUuid())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PROVISIONAL, bindings.provisional())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.SCORE, bindings.score())
                // A stream processed twice under one rule is one thing to replay; the later run is what
                // its output is, so the row says the later one.
                .onDuplicateKeyUpdate()
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PIPELINE_UUID, pipeline)
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.FRAGMENT_UUID,
                        bindings.fragment().getUuid())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PROVISIONAL, bindings.provisional())
                .set(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.SCORE, bindings.score())
                .execute());
    }

    @Override
    public List<Replayable> boundBy(final String ruleUuid, final String fragmentUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.INPUT_META_ID,
                        ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PIPELINE_UUID)
                .from(ShapeshifterOutput.SHAPESHIFTER_OUTPUT)
                .where(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.RULE_UUID.eq(ruleUuid))
                .and(fragmentUuid == null
                        ? DSL.noCondition()
                        : ShapeshifterOutput.SHAPESHIFTER_OUTPUT.FRAGMENT_UUID.eq(fragmentUuid))
                .orderBy(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.ID)
                .fetch(row -> new Replayable(
                        row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.INPUT_META_ID),
                        row.get(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.PIPELINE_UUID))));
    }

    /// In batches, like the attempts': a delete of every old row at once would hold locks across the
    /// table, and the job runs again until there is nothing left to forget.
    @Override
    public int prune(final long producedBeforeMs) {
        return JooqUtil.contextResult(connProvider, context -> {
            final List<Long> old = context
                    .select(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.ID)
                    .from(ShapeshifterOutput.SHAPESHIFTER_OUTPUT)
                    .where(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.CREATE_TIME_MS.lt(producedBeforeMs))
                    .limit(PRUNE_BATCH)
                    .fetch(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.ID);
            if (old.isEmpty()) {
                return 0;
            }
            return context.deleteFrom(ShapeshifterOutput.SHAPESHIFTER_OUTPUT)
                    .where(ShapeshifterOutput.SHAPESHIFTER_OUTPUT.ID.in(old))
                    .execute();
        });
    }
}
