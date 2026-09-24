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
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.IntegrityConstraintViolationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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

    /// The states an attempt is never pruned in, however old: it is still learning, still waiting for
    /// the model, or waiting for a person to decide, and none of those is over.
    private static final List<String> KEPT = List.of(AttemptStatus.IN_PROGRESS.name(),
            AttemptStatus.AWAITING_MODEL.name(), AttemptStatus.AWAITING_REVIEW.name());

    /// The most attempts one page may ask for, whatever it asks for: a view that asked for everything
    /// would read every attempt every document has ever made.
    private static final int PAGE_LIMIT = 1000;

    /// How many attempts one pass removes: a delete of every old row at once would hold locks across the
    /// table, and the job runs again.
    /// The states in which an attempt's rule is one that was, or was about to be, serving: the ones a
    /// retraction is about.
    private static final List<String> BOUND = List.of(AttemptStatus.PROMOTED.name(),
            AttemptStatus.PROVISIONAL.name(), AttemptStatus.AWAITING_REVIEW.name());

    private static final int PRUNE_BATCH = 1000;

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
                // A parked attempt is nobody's to carry, since no thread is behind it; a running one is
                // its own node's until it lapses.
                .and(SHAPESHIFTER_ATTEMPT.STATUS.eq(AttemptStatus.AWAITING_MODEL.name())
                        .or(SHAPESHIFTER_ATTEMPT.STATUS.eq(AttemptStatus.IN_PROGRESS.name())
                                .and(SHAPESHIFTER_ATTEMPT.NODE_NAME.eq(node)
                                        .or(SHAPESHIFTER_ATTEMPT.EXPIRY_MS.le(nowMs)))))
                .execute()) > 0;
    }

    /// Oldest first: an attempt that has waited longest is advanced first, so a busy document cannot
    /// starve one behind it.
    @Override
    public List<Recorded> awaiting(final int limit) {
        return JooqUtil.contextResult(connProvider, context -> context
                        .select()
                        .from(SHAPESHIFTER_ATTEMPT)
                        .where(SHAPESHIFTER_ATTEMPT.STATUS.eq(AttemptStatus.AWAITING_MODEL.name()))
                        .orderBy(SHAPESHIFTER_ATTEMPT.ID)
                        .limit(limit)
                        .fetch())
                .stream()
                .map(record -> recorded(record, turns(record.get(SHAPESHIFTER_ATTEMPT.ID))))
                .toList();
    }

    @Override
    public void amended(final long attemptId, final int turnNumber, final String answer,
                        final String answeredBy) {
        JooqUtil.transaction(connProvider, context -> {
            // What came after this turn is a consequence of the answer that has changed, and the walk will
            // derive it again.
            context.deleteFrom(SHAPESHIFTER_TURN)
                    .where(SHAPESHIFTER_TURN.FK_ATTEMPT_ID.eq(attemptId))
                    .and(SHAPESHIFTER_TURN.TURN_NUMBER.gt(turnNumber))
                    .execute();
            final int amended = context.update(SHAPESHIFTER_TURN)
                    .set(SHAPESHIFTER_TURN.ANSWER, answer)
                    .set(SHAPESHIFTER_TURN.ANSWERED_BY, answeredBy)
                    .setNull(SHAPESHIFTER_TURN.OUTCOME)
                    .where(SHAPESHIFTER_TURN.FK_ATTEMPT_ID.eq(attemptId))
                    .and(SHAPESHIFTER_TURN.TURN_NUMBER.eq(turnNumber))
                    .execute();
            if (amended == 0) {
                throw new IllegalArgumentException("Attempt " + attemptId + " has no turn " + turnNumber);
            }
        });
    }

    /// One open attempt per shape is what one learner means (A45), whether it is opened for the first
    /// time or opened again: the claim is taken back under the same unique key, and the database refuses
    /// it where another attempt has the shape.
    @Override
    public boolean reopened(final long attemptId, final long nowMs, final long expiryMs) {
        final long now = System.currentTimeMillis();
        return JooqUtil.transactionResult(connProvider, context -> {
            final Record row = context
                    .select(SHAPESHIFTER_ATTEMPT.STATUS,
                            SHAPESHIFTER_ATTEMPT.EXPIRY_MS,
                            SHAPESHIFTER_ATTEMPT.DOC_UUID,
                            SHAPESHIFTER_ATTEMPT.SHAPE_HASH)
                    .from(SHAPESHIFTER_ATTEMPT)
                    .where(SHAPESHIFTER_ATTEMPT.ID.eq(attemptId))
                    .fetchOne();
            if (row == null) {
                return false;
            }
            final Long expiry = row.get(SHAPESHIFTER_ATTEMPT.EXPIRY_MS);
            // A node is walking it at this moment: its answers are that walk's to give.
            if (AttemptStatus.IN_PROGRESS.name().equals(row.get(SHAPESHIFTER_ATTEMPT.STATUS))
                && expiry != null
                && expiry > nowMs) {
                return false;
            }
            // Whatever else holds this shape and has lapsed holds nothing (A45), the same release that
            // opening an attempt performs: an attempt whose node died must not block the shape from ever
            // being run again.
            context.update(SHAPESHIFTER_ATTEMPT)
                    .setNull(SHAPESHIFTER_ATTEMPT.CLAIM_KEY)
                    .set(SHAPESHIFTER_ATTEMPT.STATUS, AttemptStatus.ABANDONED.name())
                    .set(SHAPESHIFTER_ATTEMPT.DECISION, "Lapsed: the node learning it stopped")
                    .set(SHAPESHIFTER_ATTEMPT.UPDATE_TIME_MS, now)
                    .where(SHAPESHIFTER_ATTEMPT.DOC_UUID.eq(row.get(SHAPESHIFTER_ATTEMPT.DOC_UUID)))
                    .and(SHAPESHIFTER_ATTEMPT.CLAIM_KEY.eq(row.get(SHAPESHIFTER_ATTEMPT.SHAPE_HASH)))
                    .and(SHAPESHIFTER_ATTEMPT.EXPIRY_MS.le(nowMs))
                    .and(SHAPESHIFTER_ATTEMPT.ID.ne(attemptId))
                    .execute();
            try {
                // Its claim taken back, and pushed out: an attempt a person has just answered must not be
                // swept away by the next stream of its shape before the worker reaches it.
                return context.update(SHAPESHIFTER_ATTEMPT)
                        .set(SHAPESHIFTER_ATTEMPT.STATUS, AttemptStatus.AWAITING_MODEL.name())
                        .set(SHAPESHIFTER_ATTEMPT.CLAIM_KEY, SHAPESHIFTER_ATTEMPT.SHAPE_HASH)
                        .set(SHAPESHIFTER_ATTEMPT.EXPIRY_MS, expiryMs)
                        .setNull(SHAPESHIFTER_ATTEMPT.DECISION)
                        .setNull(SHAPESHIFTER_ATTEMPT.RULE_UUID)
                        .setNull(SHAPESHIFTER_ATTEMPT.SCORE)
                        .set(SHAPESHIFTER_ATTEMPT.VERSION, SHAPESHIFTER_ATTEMPT.VERSION.plus(1))
                        .set(SHAPESHIFTER_ATTEMPT.UPDATE_TIME_MS, now)
                        .where(SHAPESHIFTER_ATTEMPT.ID.eq(attemptId))
                        .execute() > 0;
            } catch (final IntegrityConstraintViolationException e) {
                // Another attempt holds the shape: a person is told rather than two learning it at once.
                return false;
            }
        });
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
                // Which guidance this question carried (A46), so that a re-walk replays what was used
                // rather than what has since been added.
                .set(SHAPESHIFTER_TURN.CARRIED_GUIDANCE, carried(turn.carried()))
                .onDuplicateKeyUpdate()
                .set(SHAPESHIFTER_TURN.ANSWER, turn.answer())
                .set(SHAPESHIFTER_TURN.ANSWERED_BY, turn.answeredBy())
                .set(SHAPESHIFTER_TURN.OUTCOME, turn.outcome() == null
                        ? null
                        : turn.outcome().name())
                // On the update as well as the insert: a question the attempt parked at is written
                // before it is asked, with nothing carried, and answered on the pass that resumes it.
                // Left off here the column would stay empty for exactly the turn that carried something.
                .set(SHAPESHIFTER_TURN.CARRIED_GUIDANCE, carried(turn.carried()))
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

    /// The attempts that bound this rule, whatever state they ended in — promoted, provisional, or
    /// awaiting a review that will now never happen. Not [#decided]'s filter, which is A25's decision
    /// about a draft *awaiting review*: these are about a rule that was serving.
    @Override
    public void settled(final String docUuid, final String ruleUuid, final AttemptStatus status,
                        final String decision) {
        JooqUtil.context(connProvider, context -> context
                .update(SHAPESHIFTER_ATTEMPT)
                .set(SHAPESHIFTER_ATTEMPT.STATUS, status.name())
                .set(SHAPESHIFTER_ATTEMPT.DECISION, decision)
                .set(SHAPESHIFTER_ATTEMPT.VERSION, SHAPESHIFTER_ATTEMPT.VERSION.plus(1))
                .set(SHAPESHIFTER_ATTEMPT.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(SHAPESHIFTER_ATTEMPT.DOC_UUID.eq(docUuid))
                .and(SHAPESHIFTER_ATTEMPT.RULE_UUID.eq(ruleUuid))
                .and(SHAPESHIFTER_ATTEMPT.STATUS.in(BOUND))
                .execute());
    }

    /// Their turns first, then the attempts: the turn rows point at the attempt rows, and a delete in the
    /// other order is a delete the foreign key refuses. In one transaction, so a pruned attempt never
    /// reads as an attempt with no turns.
    @Override
    public int prune(final long finishedBeforeMs) {
        return JooqUtil.transactionResult(connProvider, context -> {
            final List<Long> old = context
                    .select(SHAPESHIFTER_ATTEMPT.ID)
                    .from(SHAPESHIFTER_ATTEMPT)
                    .where(SHAPESHIFTER_ATTEMPT.STATUS.notIn(KEPT))
                    .and(SHAPESHIFTER_ATTEMPT.UPDATE_TIME_MS.lt(finishedBeforeMs))
                    .limit(PRUNE_BATCH)
                    .fetch(SHAPESHIFTER_ATTEMPT.ID);
            if (old.isEmpty()) {
                return 0;
            }
            context.deleteFrom(SHAPESHIFTER_TURN)
                    .where(SHAPESHIFTER_TURN.FK_ATTEMPT_ID.in(old))
                    .execute();
            return context.deleteFrom(SHAPESHIFTER_ATTEMPT)
                    .where(SHAPESHIFTER_ATTEMPT.ID.in(old))
                    .execute();
        });
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

    /// Across every document, newest first (A28): what the Supervisor view lists. Without their turns —
    /// a page of transcripts is a page nobody reads — which the detail reads for one attempt.
    @Override
    public Page found(final AttemptCriteria criteria, final Collection<String> docUuids) {
        if (docUuids.isEmpty()) {
            // Nothing this person may see: not every attempt, and not a count of them either.
            return new Page(List.of(), 0L);
        }
        final List<Condition> conditions = new ArrayList<>();
        conditions.add(SHAPESHIFTER_ATTEMPT.DOC_UUID.in(docUuids));
        if (criteria.getDocUuid() != null) {
            conditions.add(SHAPESHIFTER_ATTEMPT.DOC_UUID.eq(criteria.getDocUuid()));
        }
        if (criteria.getFeed() != null) {
            conditions.add(SHAPESHIFTER_ATTEMPT.FEED_NAME.eq(criteria.getFeed()));
        }
        if (criteria.getShape() != null) {
            // By hash, as every other lookup of a shape is: the id has no bound.
            conditions.add(SHAPESHIFTER_ATTEMPT.SHAPE_HASH.eq(ShapesDao.hash(criteria.getShape())));
        }
        if (criteria.getExecutionMode() != null) {
            conditions.add(SHAPESHIFTER_ATTEMPT.EXECUTION_MODE.eq(criteria.getExecutionMode().name()));
        }
        if (criteria.getPromotionMode() != null) {
            conditions.add(SHAPESHIFTER_ATTEMPT.PROMOTION_MODE.eq(criteria.getPromotionMode().name()));
        }
        if (!criteria.getStatuses().isEmpty()) {
            conditions.add(SHAPESHIFTER_ATTEMPT.STATUS.in(criteria.getStatuses().stream()
                    .map(AttemptStatus::name)
                    .toList()));
        }
        // A request that says nothing about paging carries nulls, which are the caller's to survive.
        final int offset = JooqUtil.getOffset(criteria.getPageRequest());
        final int length = Math.min(JooqUtil.getLimit(criteria.getPageRequest(), false, PAGE_LIMIT),
                PAGE_LIMIT);
        return JooqUtil.contextResult(connProvider, context -> {
            final List<Recorded> page = context
                    .select()
                    .from(SHAPESHIFTER_ATTEMPT)
                    .where(conditions)
                    .orderBy(SHAPESHIFTER_ATTEMPT.ID.desc())
                    .limit(offset, length)
                    .fetch()
                    .stream()
                    .map(record -> recorded(record, List.of()))
                    .toList();
            final long total = context
                    .selectCount()
                    .from(SHAPESHIFTER_ATTEMPT)
                    .where(conditions)
                    .fetchOptional(0, Long.class)
                    .orElse(0L);
            return new Page(page, total);
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

    /// The guidance a turn carried, as a list of ids: small, fixed-width and never queried on, so a
    /// column of its own would be a table of its own for nothing.
    private static String carried(final List<Long> carried) {
        return NullSafe.isEmptyCollection(carried)
                ? null
                : carried.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static List<Long> carried(final String written) {
        if (NullSafe.isBlankString(written)) {
            return List.of();
        }
        return Arrays.stream(written.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(Long::valueOf)
                .toList();
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
                        : StepOutcome.valueOf(outcome),
                carried(record.get(SHAPESHIFTER_TURN.CARRIED_GUIDANCE)));
    }
}
