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
 * One binding that is serving, as the Supervisor shows it (ruling A46, design 01 §11.6): what it binds,
 * what it scored when it was promoted, what it has been scoring since, and how much traffic it carries.
 * <p>
 * The list this belongs to is not an alert and not a queue. A46 is explicit: a binding serving at 0.93 is
 * above every threshold and nothing is wrong with it, so "good but not perfect" is a question somebody
 * asks when they have time — ordered by traffic, because the binding carrying the most streams is the one
 * worth an hour.
 */
@JsonInclude(Include.NON_NULL)
public class ServingBinding {

    @JsonProperty
    private final DocRef doc;
    @JsonProperty
    private final LearnerRef learner;
    @JsonProperty
    private final String bindingUuid;
    @JsonProperty
    private final DocRef fragment;
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
    /// Whether what it has been scoring lately is below the document's relearn threshold: what A46's list
    /// is for, now that the threshold triggers nothing by itself (design 04 §6).
    @JsonProperty
    private final boolean belowThreshold;
    /// Whether the pipeline it served has been deleted (design 06 A137): kept to be read, with nothing
    /// offered that would change it.
    @JsonProperty
    private final boolean pipelineDeleted;

    @JsonCreator
    public ServingBinding(@JsonProperty("doc") final DocRef doc,
                       @JsonProperty("learner") final LearnerRef learner,
                       @JsonProperty("bindingUuid") final String bindingUuid,
                       @JsonProperty("fragment") final DocRef fragment,
                       @JsonProperty("provisional") final boolean provisional,
                       @JsonProperty("promotedScore") final Double promotedScore,
                       @JsonProperty("promotedTimeMs") final Long promotedTimeMs,
                       @JsonProperty("rollingScore") final Double rollingScore,
                       @JsonProperty("records") final int records,
                       @JsonProperty("guidance") final int guidance,
                       @JsonProperty("belowThreshold") final boolean belowThreshold,
                       @JsonProperty("pipelineDeleted") final boolean pipelineDeleted) {
        this.doc = doc;
        this.learner = learner;
        this.bindingUuid = bindingUuid;
        this.fragment = fragment;
        this.provisional = provisional;
        this.promotedScore = promotedScore;
        this.promotedTimeMs = promotedTimeMs;
        this.rollingScore = rollingScore;
        this.records = records;
        this.guidance = guidance;
        this.belowThreshold = belowThreshold;
        this.pipelineDeleted = pipelineDeleted;
    }

    public DocRef getDoc() {
        return doc;
    }

    /// The learner it serves (A128): what a request about it sends back.
    public LearnerRef getLearner() {
        return learner;
    }

    public String getBindingUuid() {
        return bindingUuid;
    }

    public DocRef getFragment() {
        return fragment;
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
     * What it has been scoring since, over the learner's rolling memory (A29); null until the learner has
     * brought enough records to mean it.
     */
    public Double getRollingScore() {
        return rollingScore;
    }

    /**
     * How many records the learner has brought into that rolling score: the traffic this binding carries, and
     * what the list is ordered by.
     */
    public int getRecords() {
        return records;
    }

    /**
     * How many things a supervisor has said about what this binding's learner learns (A46), so that a person can see
     * whether the last hint was acted on before giving another.
     */
    public int getGuidance() {
        return guidance;
    }

    /// Whether its rolling score is below the document's relearn threshold: a binding a person might improve.
    public boolean isBelowThreshold() {
        return belowThreshold;
    }

    public boolean isPipelineDeleted() {
        return pipelineDeleted;
    }

    @Override
    public String toString() {
        return learner + " -> " + (fragment == null
                ? "nothing"
                : fragment.getName());
    }
}
