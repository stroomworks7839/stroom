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

package stroom.shapeshifter.shared;

import stroom.docref.DocRef;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.util.shared.SerialisationTestConstructor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One row of the routing table (design §3, §7.3): a selector and the variant it resolves to — a pipeline
 * fragment, referenced by DocRef (proposed ruling A20). The selector is an expression over the stream's
 * metadata and attribute map with the record shape signature as one more field (proposed ruling A22),
 * evaluated as receive rules are. Rules are ordered and the first whose expression matches binds; the
 * table's order is its specificity, so a rule has no ordinal of its own.
 * <p>
 * The routing table is the only part of a Shapeshifter AI document the supervisor rewrites (§7.3
 * rule 2), so a rule also carries the little history that rewriting needs: when the variant was
 * promoted, the score it was promoted on, whether an operator has pinned it against further promotion,
 * and the two states a learned rule passes through before it is simply bound — a <em>draft</em> the
 * router skips until a person approves it (A25), and a <em>provisional</em> binding that serves streams
 * while held-out validation waits for records (A5, §6). A rule has a {@code uuid} so that the runtime
 * state of A26 can name it stably; the store assigns one to any rule saved without.
 */
@JsonPropertyOrder({
        "uuid",
        "expression",
        "pipeline",
        "pinned",
        "draft",
        "provisional",
        "promotedTimeMs",
        "score"})
@JsonInclude(Include.NON_NULL)
public class RoutingRule {

    /**
     * The field a selector matches the record shape signature (§5) against. Not a meta field: the
     * supervisor computes it from the record and supplies it alongside the stream's attributes.
     */
    public static final String SHAPE_SIGNATURE_FIELD = "Shape Signature";

    /**
     * Null until the store or the supervisor assigns one; the client never generates it.
     */
    @JsonProperty
    private final String uuid;
    /**
     * Null matches every stream: the catch-all that ends a table when there is one.
     */
    @JsonProperty
    private final ExpressionOperator expression;
    /**
     * The fragment: a Pipeline document with no destination. Null is a <em>reserved</em> rule: a match
     * gives the shape up by operator decision rather than learning it (§3).
     */
    @JsonProperty
    private final DocRef pipeline;
    @JsonProperty
    private final boolean pinned;
    /**
     * Written by a stage in review mode and skipped by the router until Approve clears it (A25). This
     * flag is authoritative; the shape's own status mirrors it.
     */
    @JsonProperty
    private final boolean draft;
    /**
     * Bound on a candidate that cleared the floor before held-out validation could be met (A14); serves
     * streams, marked as such in their bindings, until enough records arrive to promote or retract it.
     */
    @JsonProperty
    private final boolean provisional;
    @JsonProperty
    private final Long promotedTimeMs;
    @JsonProperty
    private final Double score;

    @JsonCreator
    public RoutingRule(@JsonProperty("uuid") final String uuid,
                       @JsonProperty("expression") final ExpressionOperator expression,
                       @JsonProperty("pipeline") final DocRef pipeline,
                       @JsonProperty("pinned") final Boolean pinned,
                       @JsonProperty("draft") final Boolean draft,
                       @JsonProperty("provisional") final Boolean provisional,
                       @JsonProperty("promotedTimeMs") final Long promotedTimeMs,
                       @JsonProperty("score") final Double score) {
        this.uuid = uuid;
        this.expression = expression;
        this.pipeline = pipeline;
        this.pinned = Objects.requireNonNullElse(pinned, false);
        this.draft = Objects.requireNonNullElse(draft, false);
        this.provisional = Objects.requireNonNullElse(provisional, false);
        this.promotedTimeMs = promotedTimeMs;
        this.score = score;
    }

    @SerialisationTestConstructor
    private RoutingRule() {
        this(null, null, null, null, null, null, null, null);
    }

    /**
     * The selector the supervisor writes at promotion: exactly the document's learning key (A29) — the
     * terms the variant was validated on (A14, A15) — and nothing wider. Operators widen by hand.
     *
     * @param learningKey The field names, in the document's order.
     * @param values      The stream's routing attributes; every key field must be present, since a stream
     *                    that lacks a field the key names has no value to bind on.
     * @throws IllegalArgumentException If a key field is absent from {@code values}.
     */
    public static ExpressionOperator learnedSelector(final List<String> learningKey,
                                                     final Map<String, ?> values) {
        final ExpressionOperator.Builder builder = ExpressionOperator.builder();
        for (final String field : learningKey) {
            final Object value = values.get(field);
            if (value == null) {
                throw new IllegalArgumentException(
                        "The learning key names '" + field + "' but the stream carries no value for it");
            }
            builder.addTerm(field, Condition.EQUALS, value.toString());
        }
        return builder.build();
    }

    public String getUuid() {
        return uuid;
    }

    public ExpressionOperator getExpression() {
        return expression;
    }

    public DocRef getPipeline() {
        return pipeline;
    }

    public boolean isPinned() {
        return pinned;
    }

    public boolean isDraft() {
        return draft;
    }

    public boolean isProvisional() {
        return provisional;
    }

    /**
     * A rule with no fragment: matching it gives the shape up rather than learning it.
     */
    @JsonIgnore
    public boolean isReserved() {
        return pipeline == null;
    }

    public Long getPromotedTimeMs() {
        return promotedTimeMs;
    }

    public Double getScore() {
        return score;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RoutingRule that = (RoutingRule) o;
        return pinned == that.pinned &&
               draft == that.draft &&
               provisional == that.provisional &&
               Objects.equals(uuid, that.uuid) &&
               Objects.equals(expression, that.expression) &&
               Objects.equals(pipeline, that.pipeline) &&
               Objects.equals(promotedTimeMs, that.promotedTimeMs) &&
               Objects.equals(score, that.score);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, expression, pipeline, pinned, draft, provisional, promotedTimeMs, score);
    }

    @Override
    public String toString() {
        return "RoutingRule{" +
               "uuid='" + uuid + '\'' +
               ", expression=" + expression +
               ", pipeline=" + pipeline +
               ", pinned=" + pinned +
               ", draft=" + draft +
               ", provisional=" + provisional +
               ", promotedTimeMs=" + promotedTimeMs +
               ", score=" + score +
               '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder copy() {
        return new Builder(this);
    }


    // --------------------------------------------------------------------------------


    public static final class Builder {

        private String uuid;
        private ExpressionOperator expression;
        private DocRef pipeline;
        private boolean pinned;
        private boolean draft;
        private boolean provisional;
        private Long promotedTimeMs;
        private Double score;

        private Builder() {
        }

        private Builder(final RoutingRule rule) {
            this.uuid = rule.uuid;
            this.expression = rule.expression;
            this.pipeline = rule.pipeline;
            this.pinned = rule.pinned;
            this.draft = rule.draft;
            this.provisional = rule.provisional;
            this.promotedTimeMs = rule.promotedTimeMs;
            this.score = rule.score;
        }

        public Builder uuid(final String uuid) {
            this.uuid = uuid;
            return this;
        }

        public Builder expression(final ExpressionOperator expression) {
            this.expression = expression;
            return this;
        }

        public Builder pipeline(final DocRef pipeline) {
            this.pipeline = pipeline;
            return this;
        }

        public Builder pinned(final boolean pinned) {
            this.pinned = pinned;
            return this;
        }

        public Builder draft(final boolean draft) {
            this.draft = draft;
            return this;
        }

        public Builder provisional(final boolean provisional) {
            this.provisional = provisional;
            return this;
        }

        public Builder promotedTimeMs(final Long promotedTimeMs) {
            this.promotedTimeMs = promotedTimeMs;
            return this;
        }

        public Builder score(final Double score) {
            this.score = score;
            return this;
        }

        public RoutingRule build() {
            return new RoutingRule(uuid, expression, pipeline, pinned, draft, provisional, promotedTimeMs, score);
        }
    }
}
