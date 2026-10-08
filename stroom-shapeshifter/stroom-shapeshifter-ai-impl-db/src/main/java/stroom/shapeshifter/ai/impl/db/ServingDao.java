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
import stroom.docref.DocRef;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterDeletedPipeline;
import stroom.shapeshifter.ai.stage.Serving;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.ServingBinding;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.Collection;
import java.util.List;

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterBinding.SHAPESHIFTER_BINDING;
import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterGuidance.SHAPESHIFTER_GUIDANCE;
import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterLearnerState.SHAPESHIFTER_LEARNER_STATE;

/// The serving view of A46 as one statement: the binding rows, the state of the learner each serves, and how
/// much a supervisor has said to that learner.
///
/// The ordering and the paging are here and not above the seam. A document may be used by many pipelines,
/// each with its learner and its binding — ten thousand is not a strange number — and a view that read them
/// all to sort them in Java would read ten thousand rows to show twenty.
///
/// The join to the learner's state is a left join: a binding promoted a minute ago has served nothing yet, and a
/// list that showed it only once it had a rolling score would hide the newest bindings — the ones most
/// worth a look.
@Singleton
public class ServingDao implements Serving {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    ServingDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public Page bindings(final Collection<String> docUuids,
                      final Double below,
                      final boolean showDeleted,
                      final long offset,
                      final int limit) {
        if (NullSafe.isEmptyCollection(docUuids)) {
            return new Page(List.of(), 0L);
        }
        // How much a supervisor has said to each learner, grouped once rather than counted per row.
        final Field<Integer> said = DSL.count().as("said");
        final Table<?> guidance = DSL
                .select(SHAPESHIFTER_GUIDANCE.PIPELINE_UUID, SHAPESHIFTER_GUIDANCE.ELEMENT_ID, said)
                .from(SHAPESHIFTER_GUIDANCE)
                .where(SHAPESHIFTER_GUIDANCE.DOC_UUID.in(docUuids))
                .groupBy(SHAPESHIFTER_GUIDANCE.PIPELINE_UUID, SHAPESHIFTER_GUIDANCE.ELEMENT_ID)
                .asTable("guidance");
        // A draft is decided rather than improved (A25).
        final Condition serving = SHAPESHIFTER_BINDING.DOC_UUID.in(docUuids)
                .and(SHAPESHIFTER_BINDING.DRAFT.isFalse());
        // A learner that has served nothing is not below anything: it has no score to be below one.
        final Condition scored = below == null
                ? serving
                : serving
                        .and(SHAPESHIFTER_LEARNER_STATE.ROLLING_RECORDS.gt(0))
                        .and(SHAPESHIFTER_LEARNER_STATE.ROLLING_SCORE.lt(below));
        // A deleted pipeline's bindings are kept to be read, and shown only when asked for (A137).
        final Condition matching = showDeleted
                ? scored
                : scored.and(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID.isNull());
        // Traffic first, and a binding whose learner has no state yet counts as none, which sorts it last. Ties break
        // on the binding's own uuid so that a page turned twice reads the same twice; without a tiebreaker
        // the order among the many bindings carrying no traffic is whatever the database felt like.
        final Field<Integer> traffic = DSL.coalesce(SHAPESHIFTER_LEARNER_STATE.ROLLING_RECORDS, 0);
        return JooqUtil.contextResult(connProvider, context -> {
            final List<ServingBinding> bindings = context
                    .select(SHAPESHIFTER_BINDING.DOC_UUID,
                            SHAPESHIFTER_BINDING.PIPELINE_UUID,
                            SHAPESHIFTER_BINDING.ELEMENT_ID,
                            SHAPESHIFTER_BINDING.BINDING_UUID,
                            SHAPESHIFTER_BINDING.FRAGMENT_TYPE,
                            SHAPESHIFTER_BINDING.FRAGMENT_UUID,
                            SHAPESHIFTER_BINDING.FRAGMENT_NAME,
                            SHAPESHIFTER_BINDING.PROVISIONAL,
                            SHAPESHIFTER_BINDING.SCORE,
                            SHAPESHIFTER_BINDING.PROMOTED_TIME_MS,
                            SHAPESHIFTER_LEARNER_STATE.ROLLING_SCORE,
                            SHAPESHIFTER_LEARNER_STATE.ROLLING_RECORDS,
                            ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID,
                            guidance.field(said))
                    .from(SHAPESHIFTER_BINDING)
                    .leftJoin(SHAPESHIFTER_LEARNER_STATE)
                    .on(SHAPESHIFTER_LEARNER_STATE.PIPELINE_UUID.eq(SHAPESHIFTER_BINDING.PIPELINE_UUID))
                    .and(SHAPESHIFTER_LEARNER_STATE.ELEMENT_ID.eq(SHAPESHIFTER_BINDING.ELEMENT_ID))
                    .leftJoin(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE)
                    .on(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID
                            .eq(SHAPESHIFTER_BINDING.PIPELINE_UUID))
                    .leftJoin(guidance)
                    .on(guidance.field(SHAPESHIFTER_GUIDANCE.PIPELINE_UUID).eq(SHAPESHIFTER_BINDING.PIPELINE_UUID))
                    .and(guidance.field(SHAPESHIFTER_GUIDANCE.ELEMENT_ID).eq(SHAPESHIFTER_BINDING.ELEMENT_ID))
                    .where(matching)
                    .orderBy(traffic.desc(), SHAPESHIFTER_BINDING.BINDING_UUID.asc())
                    .limit(offset, limit)
                    .fetch(ServingDao::serving);
            final long total = context
                    .select(DSL.count())
                    .from(SHAPESHIFTER_BINDING)
                    .leftJoin(SHAPESHIFTER_LEARNER_STATE)
                    .on(SHAPESHIFTER_LEARNER_STATE.PIPELINE_UUID.eq(SHAPESHIFTER_BINDING.PIPELINE_UUID))
                    .and(SHAPESHIFTER_LEARNER_STATE.ELEMENT_ID.eq(SHAPESHIFTER_BINDING.ELEMENT_ID))
                    .leftJoin(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE)
                    .on(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID
                            .eq(SHAPESHIFTER_BINDING.PIPELINE_UUID))
                    .where(matching)
                    .fetchOptional()
                    .map(Record1::value1)
                    .orElse(0);
            return new Page(bindings, total);
        });
    }

    /// A row as the view shows it. The document is named by uuid alone; the resource puts the name on it,
    /// since it has the documents this person may read in hand already.
    private static ServingBinding serving(final Record row) {
        final Integer records = row.get(SHAPESHIFTER_LEARNER_STATE.ROLLING_RECORDS);
        final Integer said = row.get("said", Integer.class);
        return new ServingBinding(
                ShapeshifterAiDoc.buildDocRef().uuid(row.get(SHAPESHIFTER_BINDING.DOC_UUID)).build(),
                new LearnerRef(row.get(SHAPESHIFTER_BINDING.PIPELINE_UUID), row.get(SHAPESHIFTER_BINDING.ELEMENT_ID)),
                row.get(SHAPESHIFTER_BINDING.BINDING_UUID),
                new DocRef(row.get(SHAPESHIFTER_BINDING.FRAGMENT_TYPE),
                        row.get(SHAPESHIFTER_BINDING.FRAGMENT_UUID),
                        row.get(SHAPESHIFTER_BINDING.FRAGMENT_NAME)),
                row.get(SHAPESHIFTER_BINDING.PROVISIONAL),
                row.get(SHAPESHIFTER_BINDING.SCORE),
                row.get(SHAPESHIFTER_BINDING.PROMOTED_TIME_MS),
                // No rolling score until the learner has served something: shown as nothing rather than as
                // zero, which would read as a binding scoring badly.
                records == null || records == 0
                        ? null
                        : row.get(SHAPESHIFTER_LEARNER_STATE.ROLLING_SCORE),
                records == null
                        ? 0
                        : records,
                said == null
                        ? 0
                        : said,
                // Whether it is below its document's threshold is the resource's to say, which has the document.
                false,
                row.get(ShapeshifterDeletedPipeline.SHAPESHIFTER_DELETED_PIPELINE.PIPELINE_UUID) != null);
    }
}
