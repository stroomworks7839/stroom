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

package stroom.shapeshifter.shared;

import stroom.docref.DocRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One attempt as the Supervisor view shows it (A28): when, where, in what mode, what it was learning
 * from, what it came to and who or what decided. The turns are empty in the list and filled in the
 * detail, which is the same row opened.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SupervisorAttempt {

    @JsonProperty
    private final long id;
    @JsonProperty
    private final DocRef doc;
    @JsonProperty
    private final String shape;
    @JsonProperty
    private final String feed;
    @JsonProperty
    private final String type;
    @JsonProperty
    private final Long inputId;
    @JsonProperty
    private final String node;
    @JsonProperty
    private final ExecutionMode executionMode;
    @JsonProperty
    private final PromotionMode promotionMode;
    @JsonProperty
    private final AttemptStatus status;
    @JsonProperty
    private final String decision;
    @JsonProperty
    private final String ruleUuid;
    @JsonProperty
    private final Double score;
    @JsonProperty
    private final long tokensSpent;
    @JsonProperty
    private final long createTimeMs;
    @JsonProperty
    private final long updateTimeMs;
    @JsonProperty
    private final List<SupervisorTurn> turns;

    @JsonCreator
    public SupervisorAttempt(@JsonProperty("id") final long id,
                             @JsonProperty("doc") final DocRef doc,
                             @JsonProperty("shape") final String shape,
                             @JsonProperty("feed") final String feed,
                             @JsonProperty("type") final String type,
                             @JsonProperty("inputId") final Long inputId,
                             @JsonProperty("node") final String node,
                             @JsonProperty("executionMode") final ExecutionMode executionMode,
                             @JsonProperty("promotionMode") final PromotionMode promotionMode,
                             @JsonProperty("status") final AttemptStatus status,
                             @JsonProperty("decision") final String decision,
                             @JsonProperty("ruleUuid") final String ruleUuid,
                             @JsonProperty("score") final Double score,
                             @JsonProperty("tokensSpent") final long tokensSpent,
                             @JsonProperty("createTimeMs") final long createTimeMs,
                             @JsonProperty("updateTimeMs") final long updateTimeMs,
                             @JsonProperty("turns") final List<SupervisorTurn> turns) {
        this.id = id;
        this.doc = doc;
        this.shape = shape;
        this.feed = feed;
        this.type = type;
        this.inputId = inputId;
        this.node = node;
        this.executionMode = executionMode;
        this.promotionMode = promotionMode;
        this.status = status;
        this.decision = decision;
        this.ruleUuid = ruleUuid;
        this.score = score;
        this.tokensSpent = tokensSpent;
        this.createTimeMs = createTimeMs;
        this.updateTimeMs = updateTimeMs;
        this.turns = turns == null
                ? List.of()
                : List.copyOf(turns);
    }

    public long getId() {
        return id;
    }

    public DocRef getDoc() {
        return doc;
    }

    public String getShape() {
        return shape;
    }

    public String getFeed() {
        return feed;
    }

    public String getType() {
        return type;
    }

    public Long getInputId() {
        return inputId;
    }

    public String getNode() {
        return node;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public PromotionMode getPromotionMode() {
        return promotionMode;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public String getDecision() {
        return decision;
    }

    public String getRuleUuid() {
        return ruleUuid;
    }

    public Double getScore() {
        return score;
    }

    public long getTokensSpent() {
        return tokensSpent;
    }

    public long getCreateTimeMs() {
        return createTimeMs;
    }

    public long getUpdateTimeMs() {
        return updateTimeMs;
    }

    public List<SupervisorTurn> getTurns() {
        return turns;
    }
}
