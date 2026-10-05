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
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterCorpus;
import stroom.shapeshifter.ai.stage.CorpusIndex;
import stroom.shapeshifter.ai.stage.RegressionSet.Kind;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.List;

/// The corpus index of design 04 §4.1 as a table: a row per entry, keyed by rule and ordered by id, which
/// is the order the entries were accepted in.
@Singleton
public class CorpusIndexDao implements CorpusIndex {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    CorpusIndexDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public List<Row> rows(final String ruleUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectFrom(ShapeshifterCorpus.SHAPESHIFTER_CORPUS)
                .where(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.RULE_UUID.eq(ruleUuid))
                .orderBy(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.ID.asc())
                .fetch(CorpusIndexDao::row));
    }

    @Override
    public void add(final List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        JooqUtil.context(connProvider, context -> {
            // One statement per row, in order, so that the ids say the order they were accepted in.
            for (final Row row : rows) {
                context.insertInto(ShapeshifterCorpus.SHAPESHIFTER_CORPUS)
                        .set(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.DOC_UUID, row.docUuid())
                        .set(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.RULE_UUID, row.ruleUuid())
                        .set(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.META_ID, row.metaId())
                        .set(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.SEGMENT, row.segment())
                        .set(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.KIND, row.kind().name())
                        .set(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.SCORE, row.score())
                        .set(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.ATTEMPT_ID, row.attemptId())
                        .set(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.ACCEPT_TIME_MS, row.acceptTimeMs())
                        .execute();
            }
        });
    }

    @Override
    public List<Long> trim(final String ruleUuid, final int cap) {
        return JooqUtil.transactionResult(connProvider, context -> {
            final List<Long> ids = context
                    .select(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.ID)
                    .from(ShapeshifterCorpus.SHAPESHIFTER_CORPUS)
                    .where(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.RULE_UUID.eq(ruleUuid))
                    .orderBy(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.ID.asc())
                    .fetch(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.ID);
            if (ids.size() <= cap) {
                return List.of();
            }
            final List<Long> dropping = ids.subList(0, ids.size() - cap);
            final List<Long> streams = context
                    .selectDistinct(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.META_ID)
                    .from(ShapeshifterCorpus.SHAPESHIFTER_CORPUS)
                    .where(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.ID.in(dropping))
                    .fetch(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.META_ID);
            context.deleteFrom(ShapeshifterCorpus.SHAPESHIFTER_CORPUS)
                    .where(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.ID.in(dropping))
                    .execute();
            return orphaned(context, streams);
        });
    }

    @Override
    public List<Long> discard(final String ruleUuid) {
        return JooqUtil.transactionResult(connProvider, context -> {
            final List<Long> streams = context
                    .selectDistinct(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.META_ID)
                    .from(ShapeshifterCorpus.SHAPESHIFTER_CORPUS)
                    .where(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.RULE_UUID.eq(ruleUuid))
                    .fetch(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.META_ID);
            context.deleteFrom(ShapeshifterCorpus.SHAPESHIFTER_CORPUS)
                    .where(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.RULE_UUID.eq(ruleUuid))
                    .execute();
            return orphaned(context, streams);
        });
    }

    @Override
    public void move(final String fromRuleUuid, final String toRuleUuid) {
        JooqUtil.context(connProvider, context -> context
                .update(ShapeshifterCorpus.SHAPESHIFTER_CORPUS)
                .set(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.RULE_UUID, toRuleUuid)
                .where(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.RULE_UUID.eq(fromRuleUuid))
                .execute());
    }

    /// Of the streams just dropped from, the ones no row names any more.
    private static List<Long> orphaned(final DSLContext context, final List<Long> streams) {
        if (streams.isEmpty()) {
            return List.of();
        }
        final List<Long> named = context
                .selectDistinct(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.META_ID)
                .from(ShapeshifterCorpus.SHAPESHIFTER_CORPUS)
                .where(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.META_ID.in(streams))
                .fetch(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.META_ID);
        return streams.stream().filter(id -> !named.contains(id)).toList();
    }

    private static Row row(final Record row) {
        return new Row(
                row.get(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.DOC_UUID),
                row.get(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.RULE_UUID),
                row.get(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.META_ID),
                row.get(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.SEGMENT),
                Kind.valueOf(row.get(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.KIND)),
                row.get(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.SCORE),
                row.get(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.ATTEMPT_ID),
                row.get(ShapeshifterCorpus.SHAPESHIFTER_CORPUS.ACCEPT_TIME_MS));
    }
}
