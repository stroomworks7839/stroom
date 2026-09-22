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
import stroom.shapeshifter.ai.stage.Shapes;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalDouble;

import static stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterShape.SHAPESHIFTER_SHAPE;

/// The shape state of A26 as rows: one per `(doc, shape)`, holding what the stage knows about it — given
/// up, marked for relearning, awaiting review — and its rolling score. The row is made on first mention
/// and kept until a prune job removes what nothing references.
@Singleton
public class ShapesDao implements Shapes {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    ShapesDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public Optional<String> reasonGivenUp(final String docUuid, final String shape) {
        return text(docUuid, shape, SHAPESHIFTER_SHAPE.GIVEN_UP_REASON);
    }

    @Override
    public void giveUp(final String docUuid, final String shape, final String reason) {
        JooqUtil.context(connProvider, context -> {
            row(context, docUuid, shape);
            context.update(SHAPESHIFTER_SHAPE)
                    .set(SHAPESHIFTER_SHAPE.GIVEN_UP_REASON, reason)
                    .set(SHAPESHIFTER_SHAPE.VERSION, SHAPESHIFTER_SHAPE.VERSION.plus(1))
                    .set(SHAPESHIFTER_SHAPE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .where(SHAPESHIFTER_SHAPE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_SHAPE.SHAPE_HASH.eq(hash(shape)))
                    .execute();
        });
    }

    /// The rolling score of §5, folded in one transaction so that two nodes serving the same shape at once
    /// do not each read the old mean and write over the other's.
    @Override
    public OptionalDouble scored(final String docUuid,
                                 final String shape,
                                 final double score,
                                 final int records,
                                 final int memory) {
        return JooqUtil.transactionResult(connProvider, context -> {
            row(context, docUuid, shape);
            final var current = context
                    .select(SHAPESHIFTER_SHAPE.ROLLING_SCORE, SHAPESHIFTER_SHAPE.ROLLING_RECORDS)
                    .from(SHAPESHIFTER_SHAPE)
                    .where(SHAPESHIFTER_SHAPE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_SHAPE.SHAPE_HASH.eq(hash(shape)))
                    .forUpdate()
                    .fetchOne();
            final double was = current.get(SHAPESHIFTER_SHAPE.ROLLING_SCORE) == null
                    ? 0.0
                    : current.get(SHAPESHIFTER_SHAPE.ROLLING_SCORE);
            final int remembered = Math.min(current.get(SHAPESHIFTER_SHAPE.ROLLING_RECORDS), memory);
            final double now = remembered + records > 0
                    ? (was * remembered + score * records) / (remembered + records)
                    : was;
            final int held = remembered + records;
            context.update(SHAPESHIFTER_SHAPE)
                    .set(SHAPESHIFTER_SHAPE.ROLLING_SCORE, now)
                    .set(SHAPESHIFTER_SHAPE.ROLLING_RECORDS, held)
                    .set(SHAPESHIFTER_SHAPE.VERSION, SHAPESHIFTER_SHAPE.VERSION.plus(1))
                    .set(SHAPESHIFTER_SHAPE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .where(SHAPESHIFTER_SHAPE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_SHAPE.SHAPE_HASH.eq(hash(shape)))
                    .execute();
            return held >= memory
                    ? OptionalDouble.of(now)
                    : OptionalDouble.empty();
        });
    }

    @Override
    public Optional<String> relearnReason(final String docUuid, final String shape) {
        return text(docUuid, shape, SHAPESHIFTER_SHAPE.RELEARN_REASON);
    }

    @Override
    public void markForRelearning(final String docUuid, final String shape, final String reason) {
        set(docUuid, shape, SHAPESHIFTER_SHAPE.RELEARN_REASON, reason);
    }

    @Override
    public void awaitReview(final String docUuid, final String shape, final String ruleUuid) {
        set(docUuid, shape, SHAPESHIFTER_SHAPE.AWAITING_RULE_UUID, ruleUuid);
    }

    @Override
    public Optional<String> draftAwaiting(final String docUuid, final String shape) {
        return text(docUuid, shape, SHAPESHIFTER_SHAPE.AWAITING_RULE_UUID);
    }

    @Override
    public Optional<String> shapeAwaiting(final String docUuid, final String ruleUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select(SHAPESHIFTER_SHAPE.SHAPE_ID)
                .from(SHAPESHIFTER_SHAPE)
                .where(SHAPESHIFTER_SHAPE.DOC_UUID.eq(docUuid))
                .and(SHAPESHIFTER_SHAPE.AWAITING_RULE_UUID.eq(ruleUuid))
                .fetchOptional(SHAPESHIFTER_SHAPE.SHAPE_ID));
    }

    /// One conditional update: the row is the single point of truth for who is learning what, so two nodes
    /// racing for a new shape are decided by the database and not by either of them (A42). A lease whose
    /// expiry has passed is free — that is how a node that died mid-attempt lets the next one in — and the
    /// holder may take it again, which is the heartbeat.
    @Override
    public boolean lease(final String docUuid, final String shape, final String node, final long untilMs) {
        return JooqUtil.transactionResult(connProvider, context -> {
            row(context, docUuid, shape);
            final int taken = context.update(SHAPESHIFTER_SHAPE)
                    .set(SHAPESHIFTER_SHAPE.LEASE_NODE, node)
                    .set(SHAPESHIFTER_SHAPE.LEASE_EXPIRY_MS, untilMs)
                    .set(SHAPESHIFTER_SHAPE.VERSION, SHAPESHIFTER_SHAPE.VERSION.plus(1))
                    .set(SHAPESHIFTER_SHAPE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .where(SHAPESHIFTER_SHAPE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_SHAPE.SHAPE_HASH.eq(hash(shape)))
                    .and(SHAPESHIFTER_SHAPE.LEASE_NODE.isNull()
                            .or(SHAPESHIFTER_SHAPE.LEASE_NODE.eq(node))
                            .or(SHAPESHIFTER_SHAPE.LEASE_EXPIRY_MS.isNull())
                            .or(SHAPESHIFTER_SHAPE.LEASE_EXPIRY_MS.le(System.currentTimeMillis())))
                    .execute();
            return taken > 0;
        });
    }

    @Override
    public void releaseLease(final String docUuid, final String shape, final String node) {
        JooqUtil.context(connProvider, context -> context
                .update(SHAPESHIFTER_SHAPE)
                .setNull(SHAPESHIFTER_SHAPE.LEASE_NODE)
                .setNull(SHAPESHIFTER_SHAPE.LEASE_EXPIRY_MS)
                .set(SHAPESHIFTER_SHAPE.VERSION, SHAPESHIFTER_SHAPE.VERSION.plus(1))
                .set(SHAPESHIFTER_SHAPE.UPDATE_TIME_MS, System.currentTimeMillis())
                .where(SHAPESHIFTER_SHAPE.DOC_UUID.eq(docUuid))
                .and(SHAPESHIFTER_SHAPE.SHAPE_HASH.eq(hash(shape)))
                .and(SHAPESHIFTER_SHAPE.LEASE_NODE.eq(node))
                .execute());
    }

    @Override
    public void reset(final String docUuid, final String shape) {
        JooqUtil.context(connProvider, context -> {
            row(context, docUuid, shape);
            context.update(SHAPESHIFTER_SHAPE)
                    .setNull(SHAPESHIFTER_SHAPE.GIVEN_UP_REASON)
                    .setNull(SHAPESHIFTER_SHAPE.RELEARN_REASON)
                    .setNull(SHAPESHIFTER_SHAPE.AWAITING_RULE_UUID)
                    .setNull(SHAPESHIFTER_SHAPE.ROLLING_SCORE)
                    .set(SHAPESHIFTER_SHAPE.ROLLING_RECORDS, 0)
                    .set(SHAPESHIFTER_SHAPE.VERSION, SHAPESHIFTER_SHAPE.VERSION.plus(1))
                    .set(SHAPESHIFTER_SHAPE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .where(SHAPESHIFTER_SHAPE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_SHAPE.SHAPE_HASH.eq(hash(shape)))
                    .execute();
        });
    }

    private Optional<String> text(final String docUuid,
                                  final String shape,
                                  final org.jooq.TableField<?, String> column) {
        return JooqUtil.contextResult(connProvider, context -> context
                .select(column)
                .from(SHAPESHIFTER_SHAPE)
                .where(SHAPESHIFTER_SHAPE.DOC_UUID.eq(docUuid))
                .and(SHAPESHIFTER_SHAPE.SHAPE_HASH.eq(hash(shape)))
                .fetchOptional(column)
                .filter(value -> value != null));
    }

    private void set(final String docUuid,
                     final String shape,
                     final org.jooq.TableField<?, String> column,
                     final String value) {
        JooqUtil.context(connProvider, context -> {
            row(context, docUuid, shape);
            context.update(SHAPESHIFTER_SHAPE)
                    .set((org.jooq.Field<String>) column, value)
                    .set(SHAPESHIFTER_SHAPE.VERSION, SHAPESHIFTER_SHAPE.VERSION.plus(1))
                    .set(SHAPESHIFTER_SHAPE.UPDATE_TIME_MS, System.currentTimeMillis())
                    .where(SHAPESHIFTER_SHAPE.DOC_UUID.eq(docUuid))
                    .and(SHAPESHIFTER_SHAPE.SHAPE_HASH.eq(hash(shape)))
                    .execute();
        });
    }

    /// A shape's id is as long as its learning key makes it — a key may name a sender-supplied header — so
    /// rows are found by its hash, and the id is kept beside it for a person reading the row.
    static String hash(final String shape) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(shape.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /// The row for a shape, made where this node is the first to mention it. Two nodes meeting a new shape
    /// at once both insert; the unique key decides, and the loser reads what the winner wrote.
    private static void row(final DSLContext context, final String docUuid, final String shape) {
        final long now = System.currentTimeMillis();
        context.insertInto(SHAPESHIFTER_SHAPE)
                .set(SHAPESHIFTER_SHAPE.VERSION, 1)
                .set(SHAPESHIFTER_SHAPE.CREATE_TIME_MS, now)
                .set(SHAPESHIFTER_SHAPE.UPDATE_TIME_MS, now)
                .set(SHAPESHIFTER_SHAPE.DOC_UUID, docUuid)
                .set(SHAPESHIFTER_SHAPE.SHAPE_HASH, hash(shape))
                .set(SHAPESHIFTER_SHAPE.SHAPE_ID, shape)
                .onDuplicateKeyIgnore()
                .execute();
    }
}
