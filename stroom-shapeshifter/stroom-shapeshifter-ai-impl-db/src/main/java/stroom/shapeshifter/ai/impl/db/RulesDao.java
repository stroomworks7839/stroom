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
import stroom.query.api.ExpressionOperator;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.util.json.JsonUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterRule.SHAPESHIFTER_RULE;

/// The rules of A41 as rows: one per rule, ordered by `sort_order` because the router takes the first
/// match and an operator may move a rule above the learned ones. A promotion is one insert and a
/// rebinding one update, so two nodes promoting two shapes of one document do not contend.
///
/// The selector is kept as the JSON of its [ExpressionOperator], which is what the document carried
/// before: it is read and written whole, never queried into, so a column per term would buy nothing.
@Singleton
public class RulesDao implements Rules {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    RulesDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public List<RoutingRule> forDocument(final String docUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select()
                .from(SHAPESHIFTER_RULE)
                .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                .orderBy(SHAPESHIFTER_RULE.SORT_ORDER, SHAPESHIFTER_RULE.ID)
                .fetch()
                .map(RulesDao::rule));
    }

    @Override
    public Optional<RoutingRule> byUuid(final String docUuid, final String ruleUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select()
                .from(SHAPESHIFTER_RULE)
                .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                .and(SHAPESHIFTER_RULE.RULE_UUID.eq(ruleUuid))
                .fetchOptional()
                .map(RulesDao::rule));
    }

    @Override
    public RoutingRule append(final String docUuid, final RoutingRule rule) {
        return JooqUtil.contextResult(connProvider, context ->
                write(context, docUuid, rule, nextOrder(context, docUuid)));
    }

    @Override
    public RoutingRule insert(final String docUuid, final RoutingRule rule, final int at) {
        // One transaction: a shove that commits without its insert would leave a hole and no rule.
        return JooqUtil.transactionResult(connProvider, context -> {
            final int last = nextOrder(context, docUuid);
            final int order = Math.max(0, Math.min(at, last));
            // The rules at and below the position move down, so that the one being inserted is the one the
            // router reaches first among them.
            context.update(SHAPESHIFTER_RULE)
                    .set(SHAPESHIFTER_RULE.SORT_ORDER, SHAPESHIFTER_RULE.SORT_ORDER.plus(1))
                    .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_RULE.SORT_ORDER.ge(order))
                    .execute();
            return write(context, docUuid, rule, order);
        });
    }

    @Override
    public void move(final String docUuid, final String ruleUuid, final int to) {
        // One transaction: a close-up that commits without the move would leave two rules in one position.
        JooqUtil.transaction(connProvider, context -> {
            final Integer from = context.select(SHAPESHIFTER_RULE.SORT_ORDER)
                    .from(SHAPESHIFTER_RULE)
                    .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_RULE.RULE_UUID.eq(ruleUuid))
                    .fetchOne(SHAPESHIFTER_RULE.SORT_ORDER);
            if (from == null) {
                return;
            }
            final int last = nextOrder(context, docUuid) - 1;
            final int target = Math.max(0, Math.min(to, last));
            if (target == from) {
                return;
            }
            // Everything between the two positions closes behind the rule as it moves.
            if (target < from) {
                context.update(SHAPESHIFTER_RULE)
                        .set(SHAPESHIFTER_RULE.SORT_ORDER, SHAPESHIFTER_RULE.SORT_ORDER.plus(1))
                        .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                        .and(SHAPESHIFTER_RULE.SORT_ORDER.ge(target))
                        .and(SHAPESHIFTER_RULE.SORT_ORDER.lt(from))
                        .execute();
            } else {
                context.update(SHAPESHIFTER_RULE)
                        .set(SHAPESHIFTER_RULE.SORT_ORDER, SHAPESHIFTER_RULE.SORT_ORDER.minus(1))
                        .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                        .and(SHAPESHIFTER_RULE.SORT_ORDER.gt(from))
                        .and(SHAPESHIFTER_RULE.SORT_ORDER.le(target))
                        .execute();
            }
            context.update(SHAPESHIFTER_RULE)
                    .set(SHAPESHIFTER_RULE.SORT_ORDER, target)
                    .set(SHAPESHIFTER_RULE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_RULE.RULE_UUID.eq(ruleUuid))
                    .execute();
        });
    }

    /// A rule the document does not have is appended rather than lost: a promotion must not be dropped
    /// because its row was pruned between the run and the write. Two writers of one rule — a supervisor
    /// rebinding it while an operator edits its selector — are serialised on the row, the second seeing what
    /// the first wrote rather than both succeeding blind.
    @Override
    public void replace(final String docUuid, final RoutingRule rule) {
        JooqUtil.transaction(connProvider, context -> {
            context.select(SHAPESHIFTER_RULE.ID)
                    .from(SHAPESHIFTER_RULE)
                    .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_RULE.RULE_UUID.eq(rule.getUuid()))
                    .forUpdate()
                    .fetchOptional();
            final int updated = context.update(SHAPESHIFTER_RULE)
                    .set(SHAPESHIFTER_RULE.VERSION, SHAPESHIFTER_RULE.VERSION.plus(1))
                    .set(SHAPESHIFTER_RULE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .set(SHAPESHIFTER_RULE.EXPRESSION, expression(rule))
                    .set(SHAPESHIFTER_RULE.PIPELINE_TYPE, type(rule))
                    .set(SHAPESHIFTER_RULE.PIPELINE_UUID, uuid(rule))
                    .set(SHAPESHIFTER_RULE.PIPELINE_NAME, name(rule))
                    .set(SHAPESHIFTER_RULE.PINNED, rule.isPinned())
                    .set(SHAPESHIFTER_RULE.DRAFT, rule.isDraft())
                    .set(SHAPESHIFTER_RULE.PROVISIONAL, rule.isProvisional())
                    .set(SHAPESHIFTER_RULE.PROMOTED_TIME_MS, rule.getPromotedTimeMs())
                    .set(SHAPESHIFTER_RULE.SCORE, rule.getScore())
                    .set(SHAPESHIFTER_RULE.BOUNDARY_ELEMENT, element(rule))
                    .set(SHAPESHIFTER_RULE.BOUNDARY_ARRAY, array(rule))
                    .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_RULE.RULE_UUID.eq(rule.getUuid()))
                    .execute();
            if (updated == 0) {
                write(context, docUuid, rule, nextOrder(context, docUuid));
            }
        });
    }

    /// The rules below close up behind the one removed, so that positions stay dense: `insert` and `move`
    /// read a position as a place in a list, as [stroom.shapeshifter.ai.state.InMemoryRules] does, and a hole
    /// would put an appended rule above the last one.
    @Override
    public void remove(final String docUuid, final String ruleUuid) {
        JooqUtil.transaction(connProvider, context -> {
            final Integer order = context.select(SHAPESHIFTER_RULE.SORT_ORDER)
                    .from(SHAPESHIFTER_RULE)
                    .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_RULE.RULE_UUID.eq(ruleUuid))
                    .fetchOne(SHAPESHIFTER_RULE.SORT_ORDER);
            if (order == null) {
                return;
            }
            context.deleteFrom(SHAPESHIFTER_RULE)
                    .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_RULE.RULE_UUID.eq(ruleUuid))
                    .execute();
            context.update(SHAPESHIFTER_RULE)
                    .set(SHAPESHIFTER_RULE.SORT_ORDER, SHAPESHIFTER_RULE.SORT_ORDER.minus(1))
                    .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_RULE.SORT_ORDER.gt(order))
                    .execute();
        });
    }

    private static RoutingRule write(final DSLContext context,
                                     final String docUuid,
                                     final RoutingRule rule,
                                     final int order) {
        final RoutingRule stored = rule.getUuid() == null
                ? rule.copy().uuid(UUID.randomUUID().toString()).build()
                : rule;
        final long now = System.currentTimeMillis();
        context.insertInto(SHAPESHIFTER_RULE)
                .set(SHAPESHIFTER_RULE.VERSION, 1)
                .set(SHAPESHIFTER_RULE.CREATE_TIME_MS, now)
                .set(SHAPESHIFTER_RULE.UPDATE_TIME_MS, now)
                .set(SHAPESHIFTER_RULE.DOC_UUID, docUuid)
                .set(SHAPESHIFTER_RULE.RULE_UUID, stored.getUuid())
                .set(SHAPESHIFTER_RULE.SORT_ORDER, order)
                .set(SHAPESHIFTER_RULE.EXPRESSION, expression(stored))
                .set(SHAPESHIFTER_RULE.PIPELINE_TYPE, type(stored))
                .set(SHAPESHIFTER_RULE.PIPELINE_UUID, uuid(stored))
                .set(SHAPESHIFTER_RULE.PIPELINE_NAME, name(stored))
                .set(SHAPESHIFTER_RULE.PINNED, stored.isPinned())
                .set(SHAPESHIFTER_RULE.DRAFT, stored.isDraft())
                .set(SHAPESHIFTER_RULE.PROVISIONAL, stored.isProvisional())
                .set(SHAPESHIFTER_RULE.PROMOTED_TIME_MS, stored.getPromotedTimeMs())
                .set(SHAPESHIFTER_RULE.SCORE, stored.getScore())
                .set(SHAPESHIFTER_RULE.BOUNDARY_ELEMENT, element(stored))
                .set(SHAPESHIFTER_RULE.BOUNDARY_ARRAY, array(stored))
                .execute();
        return stored;
    }

    private static int nextOrder(final DSLContext context, final String docUuid) {
        final Integer highest = context.select(SHAPESHIFTER_RULE.SORT_ORDER.max())
                .from(SHAPESHIFTER_RULE)
                .where(SHAPESHIFTER_RULE.DOC_UUID.eq(docUuid))
                .fetchOne(SHAPESHIFTER_RULE.SORT_ORDER.max());
        return highest == null
                ? 0
                : highest + 1;
    }

    private static RoutingRule rule(final Record record) {
        final String expression = record.get(SHAPESHIFTER_RULE.EXPRESSION);
        final String pipelineUuid = record.get(SHAPESHIFTER_RULE.PIPELINE_UUID);
        final String boundaryElement = record.get(SHAPESHIFTER_RULE.BOUNDARY_ELEMENT);
        final String boundaryArray = record.get(SHAPESHIFTER_RULE.BOUNDARY_ARRAY);
        return RoutingRule.builder()
                .uuid(record.get(SHAPESHIFTER_RULE.RULE_UUID))
                .expression(expression == null
                        ? null
                        : JsonUtil.readValue(expression, ExpressionOperator.class))
                .pipeline(pipelineUuid == null
                        ? null
                        : new DocRef(record.get(SHAPESHIFTER_RULE.PIPELINE_TYPE), pipelineUuid,
                                record.get(SHAPESHIFTER_RULE.PIPELINE_NAME)))
                .pinned(record.get(SHAPESHIFTER_RULE.PINNED))
                .draft(record.get(SHAPESHIFTER_RULE.DRAFT))
                .provisional(record.get(SHAPESHIFTER_RULE.PROVISIONAL))
                .promotedTimeMs(record.get(SHAPESHIFTER_RULE.PROMOTED_TIME_MS))
                .score(record.get(SHAPESHIFTER_RULE.SCORE))
                .recordBoundary(boundaryElement != null
                        ? RecordBoundary.ofElement(boundaryElement)
                        : boundaryArray != null
                                ? RecordBoundary.ofArray(boundaryArray)
                                : null)
                .build();
    }

    private static String expression(final RoutingRule rule) {
        return rule.getExpression() == null
                ? null
                : JsonUtil.writeValueAsString(rule.getExpression());
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
}
