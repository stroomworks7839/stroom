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
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One rule that is serving, as the Supervisor shows it (ruling A46, design 01 §11.6): what it binds,
 * what it scored when it was promoted, what it has been scoring since, and how much traffic it carries.
 * <p>
 * The list this belongs to is not an alert and not a queue. A46 is explicit: a rule serving at 0.93 is
 * above every threshold and nothing is wrong with it, so "good but not perfect" is a question somebody
 * asks when they have time — ordered by traffic, because the rule carrying the most streams is the one
 * worth an hour.
 */
@JsonInclude(Include.NON_NULL)
public class ServingRule {

    @JsonProperty
    private final DocRef doc;
    @JsonProperty
    private final String ruleUuid;
    @JsonProperty
    private final String shapeId;
    @JsonProperty
    private final DocRef fragment;
    @JsonProperty
    private final boolean pinned;
    @JsonProperty
    private final boolean provisional;
    @JsonProperty
    private final Double promotedScore;
    @JsonProperty
    private final Long promotedTimeMs;
    @JsonProperty
    private final Double rollingScore;
    @JsonProperty
    private final int records;
    @JsonProperty
    private final int guidance;

    @JsonCreator
    public ServingRule(@JsonProperty("doc") final DocRef doc,
                       @JsonProperty("ruleUuid") final String ruleUuid,
                       @JsonProperty("shapeId") final String shapeId,
                       @JsonProperty("fragment") final DocRef fragment,
                       @JsonProperty("pinned") final boolean pinned,
                       @JsonProperty("provisional") final boolean provisional,
                       @JsonProperty("promotedScore") final Double promotedScore,
                       @JsonProperty("promotedTimeMs") final Long promotedTimeMs,
                       @JsonProperty("rollingScore") final Double rollingScore,
                       @JsonProperty("records") final int records,
                       @JsonProperty("guidance") final int guidance) {
        this.doc = doc;
        this.ruleUuid = ruleUuid;
        this.shapeId = shapeId;
        this.fragment = fragment;
        this.pinned = pinned;
        this.provisional = provisional;
        this.promotedScore = promotedScore;
        this.promotedTimeMs = promotedTimeMs;
        this.rollingScore = rollingScore;
        this.records = records;
        this.guidance = guidance;
    }

    public DocRef getDoc() {
        return doc;
    }

    public String getRuleUuid() {
        return ruleUuid;
    }

    /**
     * Which shape it was learned for: the learning key's values, e.g. {@code Feed=SYSLOG|Type=Raw Events}.
     */
    public String getShapeId() {
        return shapeId;
    }

    public DocRef getFragment() {
        return fragment;
    }

    /**
     * A pinned rule is frozen (design 01 §7.3 rule 2): served, never rebound. It cannot be improved
     * until somebody unpins it, and the list says so rather than letting the button fail.
     */
    public boolean isPinned() {
        return pinned;
    }

    /**
     * Whether it is serving on a candidate that cleared the floor before enough records arrived to
     * judge it properly (A14, design 01 §6): bound, marked as such, and promoted or retracted the first
     * time a stream brings enough. A person may accept one now rather than wait — what is skipped is
     * the wait for records, not the floor, which it has already cleared.
     */
    public boolean isProvisional() {
        return provisional;
    }

    /**
     * What it scored on the records it was promoted on (A14), or on the stream it was bound
     * provisionally from.
     */
    public Double getPromotedScore() {
        return promotedScore;
    }

    public Long getPromotedTimeMs() {
        return promotedTimeMs;
    }

    /**
     * What it has been scoring since, over the shape's rolling memory (A29); null until the shape has
     * brought enough records to mean it.
     */
    public Double getRollingScore() {
        return rollingScore;
    }

    /**
     * How many records the shape has brought into that rolling score: the traffic this rule carries, and
     * what the list is ordered by.
     */
    public int getRecords() {
        return records;
    }

    /**
     * How many things a supervisor has said about this rule's shape (A46), so that a person can see
     * whether the last hint was acted on before giving another.
     */
    public int getGuidance() {
        return guidance;
    }

    @Override
    public String toString() {
        return shapeId + " -> " + (fragment == null
                ? "nothing"
                : fragment.getName());
    }
}
