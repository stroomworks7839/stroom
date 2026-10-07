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
import stroom.docref.DocRef;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterRule.SHAPESHIFTER_RULE;

/// The bindings of A41 and A124 as rows: a learner's binding, and any draft awaiting review to take its
/// place. A promotion is one insert and a rebinding one update, so two nodes promoting for two learners
/// do not contend.
@Singleton
public class RulesDao implements Rules {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    RulesDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public List<RoutingRule> forLearner(final Learner learner) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select()
                .from(SHAPESHIFTER_RULE)
                .where(SHAPESHIFTER_RULE.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(SHAPESHIFTER_RULE.ELEMENT_ID.eq(learner.elementId()))
                .orderBy(SHAPESHIFTER_RULE.ID)
                .fetch()
                .map(RulesDao::rule));
    }

    @Override
    public Optional<Held> byUuid(final String ruleUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select()
                .from(SHAPESHIFTER_RULE)
                .where(SHAPESHIFTER_RULE.RULE_UUID.eq(ruleUuid))
                .fetchOptional()
                .map(record -> new Held(
                        new Learner(record.get(SHAPESHIFTER_RULE.PIPELINE_UUID),
                                record.get(SHAPESHIFTER_RULE.ELEMENT_ID)),
                        record.get(SHAPESHIFTER_RULE.DOC_UUID),
                        rule(record))));
    }

    @Override
    public RoutingRule append(final Learner learner, final String docUuid, final RoutingRule rule) {
        return JooqUtil.contextResult(connProvider, context -> write(context, learner, docUuid, rule));
    }

    /// A rule the learner does not have is appended rather than lost: a promotion must not be dropped
    /// because its row was pruned between the run and the write. Two writers of one rule — a supervisor
    /// rebinding it while a person pins it — are serialised on the row, the second seeing what the first
    /// wrote rather than both succeeding blind.
    @Override
    public void replace(final Learner learner, final String docUuid, final RoutingRule rule) {
        JooqUtil.transaction(connProvider, context -> {
            context.select(SHAPESHIFTER_RULE.ID)
                    .from(SHAPESHIFTER_RULE)
                    .where(SHAPESHIFTER_RULE.RULE_UUID.eq(rule.getUuid()))
                    .forUpdate()
                    .fetchOptional();
            final int updated = context.update(SHAPESHIFTER_RULE)
                    .set(SHAPESHIFTER_RULE.VERSION, SHAPESHIFTER_RULE.VERSION.plus(1))
                    .set(SHAPESHIFTER_RULE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .set(SHAPESHIFTER_RULE.DOC_UUID, docUuid)
                    .set(SHAPESHIFTER_RULE.FRAGMENT_TYPE, type(rule))
                    .set(SHAPESHIFTER_RULE.FRAGMENT_UUID, uuid(rule))
                    .set(SHAPESHIFTER_RULE.FRAGMENT_NAME, name(rule))
                    .set(SHAPESHIFTER_RULE.PINNED, rule.isPinned())
                    .set(SHAPESHIFTER_RULE.DRAFT, rule.isDraft())
                    .set(SHAPESHIFTER_RULE.PROVISIONAL, rule.isProvisional())
                    .set(SHAPESHIFTER_RULE.PROMOTED_TIME_MS, rule.getPromotedTimeMs())
                    .set(SHAPESHIFTER_RULE.SCORE, rule.getScore())
                    .set(SHAPESHIFTER_RULE.BOUNDARY_ELEMENT, element(rule))
                    .set(SHAPESHIFTER_RULE.BOUNDARY_ARRAY, array(rule))
                    .set(SHAPESHIFTER_RULE.BOUNDARY_DEPTH, depth(rule))
                    .set(SHAPESHIFTER_RULE.SNAPSHOT_ID, rule.getSnapshot())
                    .where(SHAPESHIFTER_RULE.PIPELINE_UUID.eq(learner.pipelineUuid()))
                    .and(SHAPESHIFTER_RULE.ELEMENT_ID.eq(learner.elementId()))
                    .and(SHAPESHIFTER_RULE.RULE_UUID.eq(rule.getUuid()))
                    .execute();
            if (updated == 0) {
                write(context, learner, docUuid, rule);
            }
        });
    }

    @Override
    public void remove(final Learner learner, final String ruleUuid) {
        JooqUtil.context(connProvider, context -> context
                .deleteFrom(SHAPESHIFTER_RULE)
                .where(SHAPESHIFTER_RULE.PIPELINE_UUID.eq(learner.pipelineUuid()))
                .and(SHAPESHIFTER_RULE.ELEMENT_ID.eq(learner.elementId()))
                .and(SHAPESHIFTER_RULE.RULE_UUID.eq(ruleUuid))
                .execute());
    }

    private static RoutingRule write(final DSLContext context,
                                     final Learner learner,
                                     final String docUuid,
                                     final RoutingRule rule) {
        final RoutingRule stored = rule.getUuid() == null
                ? rule.copy().uuid(UUID.randomUUID().toString()).build()
                : rule;
        final long now = System.currentTimeMillis();
        context.insertInto(SHAPESHIFTER_RULE)
                .set(SHAPESHIFTER_RULE.VERSION, 1)
                .set(SHAPESHIFTER_RULE.CREATE_TIME_MS, now)
                .set(SHAPESHIFTER_RULE.UPDATE_TIME_MS, now)
                .set(SHAPESHIFTER_RULE.PIPELINE_UUID, learner.pipelineUuid())
                .set(SHAPESHIFTER_RULE.ELEMENT_ID, learner.elementId())
                .set(SHAPESHIFTER_RULE.DOC_UUID, docUuid)
                .set(SHAPESHIFTER_RULE.RULE_UUID, stored.getUuid())
                .set(SHAPESHIFTER_RULE.FRAGMENT_TYPE, type(stored))
                .set(SHAPESHIFTER_RULE.FRAGMENT_UUID, uuid(stored))
                .set(SHAPESHIFTER_RULE.FRAGMENT_NAME, name(stored))
                .set(SHAPESHIFTER_RULE.PINNED, stored.isPinned())
                .set(SHAPESHIFTER_RULE.DRAFT, stored.isDraft())
                .set(SHAPESHIFTER_RULE.PROVISIONAL, stored.isProvisional())
                .set(SHAPESHIFTER_RULE.PROMOTED_TIME_MS, stored.getPromotedTimeMs())
                .set(SHAPESHIFTER_RULE.SCORE, stored.getScore())
                .set(SHAPESHIFTER_RULE.BOUNDARY_ELEMENT, element(stored))
                .set(SHAPESHIFTER_RULE.BOUNDARY_ARRAY, array(stored))
                .set(SHAPESHIFTER_RULE.BOUNDARY_DEPTH, depth(stored))
                .set(SHAPESHIFTER_RULE.SNAPSHOT_ID, stored.getSnapshot())
                .execute();
        return stored;
    }

    private static RoutingRule rule(final Record record) {
        final String fragmentUuid = record.get(SHAPESHIFTER_RULE.FRAGMENT_UUID);
        final String boundaryElement = record.get(SHAPESHIFTER_RULE.BOUNDARY_ELEMENT);
        final String boundaryArray = record.get(SHAPESHIFTER_RULE.BOUNDARY_ARRAY);
        final Integer boundaryDepth = record.get(SHAPESHIFTER_RULE.BOUNDARY_DEPTH);
        return RoutingRule.builder()
                .uuid(record.get(SHAPESHIFTER_RULE.RULE_UUID))
                .pipeline(fragmentUuid == null
                        ? null
                        : new DocRef(record.get(SHAPESHIFTER_RULE.FRAGMENT_TYPE), fragmentUuid,
                                record.get(SHAPESHIFTER_RULE.FRAGMENT_NAME)))
                .pinned(record.get(SHAPESHIFTER_RULE.PINNED))
                .draft(record.get(SHAPESHIFTER_RULE.DRAFT))
                .provisional(record.get(SHAPESHIFTER_RULE.PROVISIONAL))
                .promotedTimeMs(record.get(SHAPESHIFTER_RULE.PROMOTED_TIME_MS))
                .score(record.get(SHAPESHIFTER_RULE.SCORE))
                .recordBoundary(boundaryElement != null
                        ? RecordBoundary.ofElement(boundaryElement).atDepth(boundaryDepth)
                        : boundaryArray != null
                                ? RecordBoundary.ofArray(boundaryArray).atDepth(boundaryDepth)
                                : null)
                .snapshot(record.get(SHAPESHIFTER_RULE.SNAPSHOT_ID))
                .build();
    }

    private static String type(final RoutingRule rule) {
        return rule.getPipeline() == null
                ? null
                : rule.getPipeline().getType();
    }

    private static String uuid(final RoutingRule rule) {
        return rule.getPipeline() == null
                ? null
                : rule.getPipeline().getUuid();
    }

    private static String name(final RoutingRule rule) {
        return rule.getPipeline() == null
                ? null
                : rule.getPipeline().getName();
    }

    private static String element(final RoutingRule rule) {
        return rule.getRecordBoundary() == null
                ? null
                : rule.getRecordBoundary().getElement();
    }

    private static String array(final RoutingRule rule) {
        return rule.getRecordBoundary() == null
                ? null
                : rule.getRecordBoundary().getArray();
    }

    /// How deep the records the boundary names sit, which is part of the boundary and not derivable from
    /// it: a rule read back without it would write a fragment that splits in the wrong place (§12 item
    /// 25).
    private static Integer depth(final RoutingRule rule) {
        return rule.getRecordBoundary() == null
                ? null
                : rule.getRecordBoundary().getDepth();
    }
}
