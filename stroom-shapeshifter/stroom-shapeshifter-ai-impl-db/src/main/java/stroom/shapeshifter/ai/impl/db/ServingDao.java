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
import stroom.shapeshifter.ai.stage.Serving;
import stroom.shapeshifter.shared.ServingRule;
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

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterGuidance.SHAPESHIFTER_GUIDANCE;
import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterRule.SHAPESHIFTER_RULE;
import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterShape.SHAPESHIFTER_SHAPE;

/// The serving view of A46 as one statement: the rule rows, the shape row each was learned for, and how
/// much a supervisor has said about that shape.
///
/// The ordering and the paging are here and not above the seam, which is the whole reason the rule row
/// carries a `shape_hash` beside its `shape_id`. A document has one rule per learned shape, so a busy
/// one has as many rules as it has shapes — ten thousand is not a strange number — and a view that read
/// them all to sort them in Java would read ten thousand rows to show twenty. The join needs an index,
/// and a `longtext` column cannot be one.
///
/// The join to the shape row is a left join: a rule promoted a minute ago has served nothing yet, and a
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
    public Page rules(final Collection<String> docUuids,
                      final Double below,
                      final long offset,
                      final int limit) {
        if (NullSafe.isEmptyCollection(docUuids)) {
            return new Page(List.of(), 0L);
        }
        // How much a supervisor has said about each shape, grouped once rather than counted per row.
        final Field<Integer> said = DSL.count().as("said");
        final Table<?> guidance = DSL
                .select(SHAPESHIFTER_GUIDANCE.DOC_UUID, SHAPESHIFTER_GUIDANCE.SHAPE_HASH, said)
                .from(SHAPESHIFTER_GUIDANCE)
                .where(SHAPESHIFTER_GUIDANCE.DOC_UUID.in(docUuids))
                .groupBy(SHAPESHIFTER_GUIDANCE.DOC_UUID, SHAPESHIFTER_GUIDANCE.SHAPE_HASH)
                .asTable("guidance");
        // A draft is decided rather than improved (A25); a rule binding nothing is a reserved one; and a
        // rule with no shape was written by hand, so there is nothing to learn again for it.
        final Condition serving = SHAPESHIFTER_RULE.DOC_UUID.in(docUuids)
                .and(SHAPESHIFTER_RULE.DRAFT.isFalse())
                .and(SHAPESHIFTER_RULE.PIPELINE_UUID.isNotNull())
                .and(SHAPESHIFTER_RULE.SHAPE_HASH.isNotNull());
        // A shape that has served nothing is not below anything: it has no score to be below one.
        final Condition matching = below == null
                ? serving
                : serving
                        .and(SHAPESHIFTER_SHAPE.ROLLING_RECORDS.gt(0))
                        .and(SHAPESHIFTER_SHAPE.ROLLING_SCORE.lt(below));
        // Traffic first, and a rule with no shape row yet counts as none, which sorts it last. Ties break
        // on the rule's own uuid so that a page turned twice reads the same twice; without a tiebreaker
        // the order among the many rules carrying no traffic is whatever the database felt like.
        final Field<Integer> traffic = DSL.coalesce(SHAPESHIFTER_SHAPE.ROLLING_RECORDS, 0);
        return JooqUtil.contextResult(connProvider, context -> {
            final List<ServingRule> rules = context
                    .select(SHAPESHIFTER_RULE.DOC_UUID,
                            SHAPESHIFTER_RULE.RULE_UUID,
                            SHAPESHIFTER_RULE.SHAPE_ID,
                            SHAPESHIFTER_RULE.PIPELINE_TYPE,
                            SHAPESHIFTER_RULE.PIPELINE_UUID,
                            SHAPESHIFTER_RULE.PIPELINE_NAME,
                            SHAPESHIFTER_RULE.PINNED,
                            SHAPESHIFTER_RULE.PROVISIONAL,
                            SHAPESHIFTER_RULE.SCORE,
                            SHAPESHIFTER_RULE.PROMOTED_TIME_MS,
                            SHAPESHIFTER_SHAPE.ROLLING_SCORE,
                            SHAPESHIFTER_SHAPE.ROLLING_RECORDS,
                            guidance.field(said))
                    .from(SHAPESHIFTER_RULE)
                    .leftJoin(SHAPESHIFTER_SHAPE)
                    .on(SHAPESHIFTER_SHAPE.DOC_UUID.eq(SHAPESHIFTER_RULE.DOC_UUID))
                    .and(SHAPESHIFTER_SHAPE.SHAPE_HASH.eq(SHAPESHIFTER_RULE.SHAPE_HASH))
                    .leftJoin(guidance)
                    .on(guidance.field(SHAPESHIFTER_GUIDANCE.DOC_UUID).eq(SHAPESHIFTER_RULE.DOC_UUID))
                    .and(guidance.field(SHAPESHIFTER_GUIDANCE.SHAPE_HASH).eq(SHAPESHIFTER_RULE.SHAPE_HASH))
                    .where(matching)
                    .orderBy(traffic.desc(), SHAPESHIFTER_RULE.RULE_UUID.asc())
                    .limit(offset, limit)
                    .fetch(ServingDao::serving);
            final long total = context
                    .select(DSL.count())
                    .from(SHAPESHIFTER_RULE)
                    .leftJoin(SHAPESHIFTER_SHAPE)
                    .on(SHAPESHIFTER_SHAPE.DOC_UUID.eq(SHAPESHIFTER_RULE.DOC_UUID))
                    .and(SHAPESHIFTER_SHAPE.SHAPE_HASH.eq(SHAPESHIFTER_RULE.SHAPE_HASH))
                    .where(matching)
                    .fetchOptional()
                    .map(Record1::value1)
                    .orElse(0);
            return new Page(rules, total);
        });
    }

    /// A row as the view shows it. The document is named by uuid alone; the resource puts the name on it,
    /// since it has the documents this person may read in hand already.
    private static ServingRule serving(final Record row) {
        final Integer records = row.get(SHAPESHIFTER_SHAPE.ROLLING_RECORDS);
        final Integer said = row.get("said", Integer.class);
        return new ServingRule(
                ShapeshifterAiDoc.buildDocRef().uuid(row.get(SHAPESHIFTER_RULE.DOC_UUID)).build(),
                row.get(SHAPESHIFTER_RULE.RULE_UUID),
                row.get(SHAPESHIFTER_RULE.SHAPE_ID),
                new DocRef(row.get(SHAPESHIFTER_RULE.PIPELINE_TYPE),
                        row.get(SHAPESHIFTER_RULE.PIPELINE_UUID),
                        row.get(SHAPESHIFTER_RULE.PIPELINE_NAME)),
                row.get(SHAPESHIFTER_RULE.PINNED),
                row.get(SHAPESHIFTER_RULE.PROVISIONAL),
                row.get(SHAPESHIFTER_RULE.SCORE),
                row.get(SHAPESHIFTER_RULE.PROMOTED_TIME_MS),
                // No rolling score until the shape has served something: shown as nothing rather than as
                // zero, which would read as a rule scoring badly.
                records == null || records == 0
                        ? null
                        : row.get(SHAPESHIFTER_SHAPE.ROLLING_SCORE),
                records == null
                        ? 0
                        : records,
                said == null
                        ? 0
                        : said);
    }
}
