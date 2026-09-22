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
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepOutcome;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.IntegrityConstraintViolationException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterAttempt.SHAPESHIFTER_ATTEMPT;
import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterTurn.SHAPESHIFTER_TURN;

/// The attempts of A28 as rows: one per attempt, one per turn, so that what was asked and answered
/// outlives the node that asked it and a person can read it back. A turn is inserted as it is answered
/// rather than at the end, so an attempt still running — or one whose node died — shows what it had got
/// to.
@Singleton
public class AttemptsDao implements Attempts {

    /// The states in which an attempt is still learning, and so still holds its shape (A45). A draft
    /// awaiting review is not one of them: it has written its rule, and the rule is what the router and
    /// the shape's own row answer with until a person decides.
    private static final List<String> OPEN = List.of(AttemptStatus.IN_PROGRESS.name(),
            AttemptStatus.AWAITING_MODEL.name());

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    AttemptsDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    /// One open attempt per shape is what one learner means (A45). The look and the insert are one
    /// transaction over the shape's rows, so two nodes meeting a new shape at once do not both take it;
    /// an attempt whose expiry has passed has lapsed, and the next node in takes the shape.
    @Override
    public Optional<Long> opened(final Attempt attempt, final long nowMs) {
        final long now = System.currentTimeMillis();
        final String claim = ShapesDao.hash(attempt.shape());
        return JooqUtil.transactionResult(connProvider, context -> {
            // An attempt that has lapsed holds nothing: its claim is released before this one asks for it,
            // which is how a node that died lets the next in.
            context.update(SHAPESHIFTER_ATTEMPT)
                    .setNull(SHAPESHIFTER_ATTEMPT.CLAIM_KEY)
                    .set(SHAPESHIFTER_ATTEMPT.STATUS, AttemptStatus.ABANDONED.name())
                    .set(SHAPESHIFTER_ATTEMPT.DECISION, "Lapsed: the node learning it stopped")
                    .set(SHAPESHIFTER_ATTEMPT.UPDATE_TIME_MS, now)
                    .where(SHAPESHIFTER_ATTEMPT.DOC_UUID.eq(attempt.docUuid()))
                    .and(SHAPESHIFTER_ATTEMPT.CLAIM_KEY.eq(claim))
                    .and(SHAPESHIFTER_ATTEMPT.EXPIRY_MS.le(nowMs))
                    .execute();
            try {
                // The unique key on (doc_uuid, claim_key) is what admits one open attempt per shape: two
                // nodes meeting a new shape at once are decided by the database, not by what each read.
                return Optional.of(insert(context, attempt, now, claim));
            } catch (final IntegrityConstraintViolationException e) {
                return Optional.empty();
            }
        });
    }

    @Override
    public boolean claimed(final long attemptId, final String node, final long nowMs, final long expiryMs) {
        return JooqUtil.contextResult(connProvider, context -> context
                .update(SHAPESHIFTER_ATTEMPT)
                .set(SHAPESHIFTER_ATTEMPT.NODE_NAME, node)
                .set(SHAPESHIFTER_ATTEMPT.STATUS, AttemptStatus.IN_PROGRESS.name())
                .set(SHAPESHIFTER_ATTEMPT.EXPIRY_MS, expiryMs)
                .set(SHAPESHIFTER_ATTEMPT.VERSION, SHAPESHIFTER_ATTEMPT.VERSION.plus(1))
                .set(SHAPESHIFTER_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(SHAPESHIFTER_ATTEMPT.ID.eq(attemptId))
                .and(SHAPESHIFTER_ATTEMPT.STATUS.in(OPEN))
                // Either it is this node's already, or it has lapsed and is anyone's.
                .and(SHAPESHIFTER_ATTEMPT.NODE_NAME.eq(node)
                        .or(SHAPESHIFTER_ATTEMPT.EXPIRY_MS.le(nowMs)))
                .execute()) > 0;
    }

    @Override
    public void heartbeat(final long attemptId, final long expiryMs) {
        JooqUtil.context(connProvider, context -> context
                .update(SHAPESHIFTER_ATTEMPT)
                .set(SHAPESHIFTER_ATTEMPT.EXPIRY_MS, expiryMs)
                .set(SHAPESHIFTER_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(SHAPESHIFTER_ATTEMPT.ID.eq(attemptId))
                .and(SHAPESHIFTER_ATTEMPT.STATUS.in(OPEN))
                .execute());
    }

    @Override
    public Optional<Recorded> open(final String docUuid, final String shape, final long nowMs) {
        return JooqUtil.contextResult(connProvider, context -> context
                        .select()
                        .from(SHAPESHIFTER_ATTEMPT)
                        .where(SHAPESHIFTER_ATTEMPT.DOC_UUID.eq(docUuid))
                        .and(SHAPESHIFTER_ATTEMPT.SHAPE_HASH.eq(ShapesDao.hash(shape)))
                        .and(SHAPESHIFTER_ATTEMPT.STATUS.in(OPEN))
                        .and(SHAPESHIFTER_ATTEMPT.EXPIRY_MS.gt(nowMs))
                        .orderBy(SHAPESHIFTER_ATTEMPT.ID.desc())
                        .limit(1)
                        .fetchOptional())
                .map(record -> recorded(record, turns(record.get(SHAPESHIFTER_ATTEMPT.ID))));
    }

    /// A parked attempt keeps its shape: it is still learning, and its expiry is pushed out by the same
    /// heartbeat that would have extended it (A45).
    @Override
    public void parked(final long attemptId,
                       final AttemptStatus status,
                       final long expiryMs,
                       final long tokensSpent) {
        JooqUtil.context(connProvider, context -> context
                .update(SHAPESHIFTER_ATTEMPT)
                .set(SHAPESHIFTER_ATTEMPT.STATUS, status.name())
                .set(SHAPESHIFTER_ATTEMPT.EXPIRY_MS, expiryMs)
                .set(SHAPESHIFTER_ATTEMPT.TOKENS_SPENT, SHAPESHIFTER_ATTEMPT.TOKENS_SPENT.plus(tokensSpent))
                .set(SHAPESHIFTER_ATTEMPT.VERSION, SHAPESHIFTER_ATTEMPT.VERSION.plus(1))
                .set(SHAPESHIFTER_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(SHAPESHIFTER_ATTEMPT.ID.eq(attemptId))
                // A finished attempt is not parked back into life: it holds no shape and asks nothing.
                .and(SHAPESHIFTER_ATTEMPT.STATUS.in(OPEN))
                .execute());
    }

    private static long insert(final DSLContext context, final Attempt attempt, final long now,
                               final String claim) {
        return context
                .insertInto(SHAPESHIFTER_ATTEMPT)
                .set(SHAPESHIFTER_ATTEMPT.CLAIM_KEY, claim)
                .set(SHAPESHIFTER_ATTEMPT.VERSION, 1)
                .set(SHAPESHIFTER_ATTEMPT.CREATE_TIME_MS, now)
                .set(SHAPESHIFTER_ATTEMPT.UPDATE_TIME_MS, now)
                .set(SHAPESHIFTER_ATTEMPT.DOC_UUID, attempt.docUuid())
                .set(SHAPESHIFTER_ATTEMPT.SHAPE_HASH, ShapesDao.hash(attempt.shape()))
                .set(SHAPESHIFTER_ATTEMPT.SHAPE_ID, attempt.shape())
                .set(SHAPESHIFTER_ATTEMPT.FEED_NAME, attempt.feed())
                .set(SHAPESHIFTER_ATTEMPT.TYPE_NAME, attempt.type())
                .set(SHAPESHIFTER_ATTEMPT.INPUT_META_ID, attempt.inputId())
                .set(SHAPESHIFTER_ATTEMPT.NODE_NAME, attempt.node())
                .set(SHAPESHIFTER_ATTEMPT.EXECUTION_MODE, attempt.executionMode().name())
                .set(SHAPESHIFTER_ATTEMPT.PROMOTION_MODE, attempt.promotionMode().name())
                .set(SHAPESHIFTER_ATTEMPT.STATUS, AttemptStatus.IN_PROGRESS.name())
                .set(SHAPESHIFTER_ATTEMPT.EXPIRY_MS, attempt.expiryMs())
                .returning(SHAPESHIFTER_ATTEMPT.ID)
                .fetchOne(SHAPESHIFTER_ATTEMPT.ID);
    }

    /// By number: a turn written as it is asked and again when it is judged is one row, so an attempt
    /// still running shows what it had got to.
    @Override
    public void turn(final long attemptId, final Turn turn) {
        JooqUtil.context(connProvider, context -> context
                .insertInto(SHAPESHIFTER_TURN)
                .set(SHAPESHIFTER_TURN.CREATE_TIME_MS, System.currentTimeMillis())
                .set(SHAPESHIFTER_TURN.FK_ATTEMPT_ID, attemptId)
                .set(SHAPESHIFTER_TURN.TURN_NUMBER, turn.number())
                .set(SHAPESHIFTER_TURN.STEP_ID, turn.stepId())
                .set(SHAPESHIFTER_TURN.CANDIDATE, turn.candidate())
                .set(SHAPESHIFTER_TURN.QUESTION_KIND, turn.kind().name())
                .set(SHAPESHIFTER_TURN.QUESTION, turn.question())
                .set(SHAPESHIFTER_TURN.ANSWER, turn.answer())
                .set(SHAPESHIFTER_TURN.ANSWERED_BY, turn.answeredBy())
                .set(SHAPESHIFTER_TURN.OUTCOME, turn.outcome() == null
                        ? null
                        : turn.outcome().name())
                .onDuplicateKeyUpdate()
                .set(SHAPESHIFTER_TURN.ANSWER, turn.answer())
                .set(SHAPESHIFTER_TURN.ANSWERED_BY, turn.answeredBy())
                .set(SHAPESHIFTER_TURN.OUTCOME, turn.outcome() == null
                        ? null
                        : turn.outcome().name())
                .execute());
    }

    @Override
    public void closed(final long attemptId,
                       final AttemptStatus status,
                       final String decision,
                       final String ruleUuid,
                       final Double score,
                       final long tokensSpent) {
        JooqUtil.context(connProvider, context -> context
                .update(SHAPESHIFTER_ATTEMPT)
                .set(SHAPESHIFTER_ATTEMPT.STATUS, status.name())
                .set(SHAPESHIFTER_ATTEMPT.DECISION, decision)
                .set(SHAPESHIFTER_ATTEMPT.RULE_UUID, ruleUuid)
                .set(SHAPESHIFTER_ATTEMPT.SCORE, score)
                .set(SHAPESHIFTER_ATTEMPT.TOKENS_SPENT, SHAPESHIFTER_ATTEMPT.TOKENS_SPENT.plus(tokensSpent))
                .set(SHAPESHIFTER_ATTEMPT.VERSION, SHAPESHIFTER_ATTEMPT.VERSION.plus(1))
                .set(SHAPESHIFTER_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                // A claim that has ended holds nothing (A45), and frees the shape for the next attempt.
                .setNull(SHAPESHIFTER_ATTEMPT.EXPIRY_MS)
                .setNull(SHAPESHIFTER_ATTEMPT.CLAIM_KEY)
                .where(SHAPESHIFTER_ATTEMPT.ID.eq(attemptId))
                .execute());
    }

    @Override
    public void decided(final String docUuid,
                        final String ruleUuid,
                        final AttemptStatus status,
                        final String decision) {
        JooqUtil.context(connProvider, context -> context
                .update(SHAPESHIFTER_ATTEMPT)
                .set(SHAPESHIFTER_ATTEMPT.STATUS, status.name())
                .set(SHAPESHIFTER_ATTEMPT.DECISION, decision)
                .set(SHAPESHIFTER_ATTEMPT.VERSION, SHAPESHIFTER_ATTEMPT.VERSION.plus(1))
                .set(SHAPESHIFTER_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(SHAPESHIFTER_ATTEMPT.DOC_UUID.eq(docUuid))
                .and(SHAPESHIFTER_ATTEMPT.RULE_UUID.eq(ruleUuid))
                .and(SHAPESHIFTER_ATTEMPT.STATUS.eq(AttemptStatus.AWAITING_REVIEW.name()))
                .execute());
    }

    @Override
    public Optional<Recorded> byId(final long attemptId) {
        return JooqUtil.contextResult(connProvider, context -> context
                        .select()
                        .from(SHAPESHIFTER_ATTEMPT)
                        .where(SHAPESHIFTER_ATTEMPT.ID.eq(attemptId))
                        .fetchOptional())
                .map(record -> recorded(record, turns(attemptId)));
    }

    @Override
    public List<Recorded> forDocument(final String docUuid, final int limit) {
        return JooqUtil.contextResult(connProvider, context -> {
            final List<? extends Record> rows = context
                    .select()
                    .from(SHAPESHIFTER_ATTEMPT)
                    .where(SHAPESHIFTER_ATTEMPT.DOC_UUID.eq(docUuid))
                    .orderBy(SHAPESHIFTER_ATTEMPT.ID.desc())
                    .limit(limit)
                    .fetch();
            final List<Long> ids = rows.stream().map(row -> row.get(SHAPESHIFTER_ATTEMPT.ID)).toList();
            // One query for every attempt's turns, not one each: the Supervisor view pages these.
            final Map<Long, List<Turn>> byAttempt = ids.isEmpty()
                    ? Map.of()
                    : context.select()
                            .from(SHAPESHIFTER_TURN)
                            .where(SHAPESHIFTER_TURN.FK_ATTEMPT_ID.in(ids))
                            .orderBy(SHAPESHIFTER_TURN.FK_ATTEMPT_ID, SHAPESHIFTER_TURN.TURN_NUMBER)
                            .fetch()
                            .stream()
                            .collect(Collectors.groupingBy(row -> row.get(SHAPESHIFTER_TURN.FK_ATTEMPT_ID),
                                    Collectors.mapping(AttemptsDao::turn, Collectors.toList())));
            return rows.stream()
                    .map(row -> recorded(row, byAttempt.getOrDefault(row.get(SHAPESHIFTER_ATTEMPT.ID), List.of())))
                    .toList();
        });
    }

    private List<Turn> turns(final long attemptId) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select()
                .from(SHAPESHIFTER_TURN)
                .where(SHAPESHIFTER_TURN.FK_ATTEMPT_ID.eq(attemptId))
                .orderBy(SHAPESHIFTER_TURN.TURN_NUMBER)
                .fetch()
                .map(AttemptsDao::turn));
    }

    private static Recorded recorded(final Record record, final List<Turn> turns) {
        final Attempt attempt = new Attempt(
                record.get(SHAPESHIFTER_ATTEMPT.DOC_UUID),
                record.get(SHAPESHIFTER_ATTEMPT.SHAPE_ID),
                record.get(SHAPESHIFTER_ATTEMPT.FEED_NAME),
                record.get(SHAPESHIFTER_ATTEMPT.TYPE_NAME),
                record.get(SHAPESHIFTER_ATTEMPT.INPUT_META_ID),
                record.get(SHAPESHIFTER_ATTEMPT.NODE_NAME),
                ExecutionMode.valueOf(record.get(SHAPESHIFTER_ATTEMPT.EXECUTION_MODE)),
                PromotionMode.valueOf(record.get(SHAPESHIFTER_ATTEMPT.PROMOTION_MODE)),
                record.get(SHAPESHIFTER_ATTEMPT.EXPIRY_MS) == null
                        ? 0L
                        : record.get(SHAPESHIFTER_ATTEMPT.EXPIRY_MS));
        return new Recorded(
                record.get(SHAPESHIFTER_ATTEMPT.ID),
                attempt,
                AttemptStatus.valueOf(record.get(SHAPESHIFTER_ATTEMPT.STATUS)),
                record.get(SHAPESHIFTER_ATTEMPT.DECISION),
                record.get(SHAPESHIFTER_ATTEMPT.RULE_UUID),
                record.get(SHAPESHIFTER_ATTEMPT.SCORE),
                record.get(SHAPESHIFTER_ATTEMPT.TOKENS_SPENT),
                record.get(SHAPESHIFTER_ATTEMPT.CREATE_TIME_MS),
                record.get(SHAPESHIFTER_ATTEMPT.UPDATE_TIME_MS),
                turns);
    }

    private static Turn turn(final Record record) {
        final String outcome = record.get(SHAPESHIFTER_TURN.OUTCOME);
        return new Turn(
                record.get(SHAPESHIFTER_TURN.TURN_NUMBER),
                record.get(SHAPESHIFTER_TURN.STEP_ID),
                record.get(SHAPESHIFTER_TURN.CANDIDATE),
                QuestionKind.valueOf(record.get(SHAPESHIFTER_TURN.QUESTION_KIND)),
                record.get(SHAPESHIFTER_TURN.QUESTION),
                record.get(SHAPESHIFTER_TURN.ANSWER),
                record.get(SHAPESHIFTER_TURN.ANSWERED_BY),
                outcome == null
                        ? null
                        : StepOutcome.valueOf(outcome));
    }
}
