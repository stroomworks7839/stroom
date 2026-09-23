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
import stroom.pipeline.shared.stepping.ElementStepDetails;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * What a supervised stage did with the stream at the cursor (A30, design 01 §11.7): the stage pane's
 * contents, built from the {@code StageRun} the element made.
 * <p>
 * A supervisor has no single document to show as code — the Shapeshifter AI document is edited in its
 * own editor — and what a person selecting it needs to see is not text but the decision: which shape
 * this stream has, which rule matched, what was bound, what the scorers made of it, and what was said
 * to the model where it was learned. The fragment and the document are carried as {@link DocRef}s so
 * that the pane can offer them as links rather than as names.
 */
@JsonInclude(Include.NON_NULL)
public class ShapeshifterAiStepDetails extends ElementStepDetails {

    @JsonProperty
    private final boolean dryRun;
    @JsonProperty
    private final DocRef document;
    @JsonProperty
    private final String shapeId;
    @JsonProperty
    private final Map<String, String> shape;
    @JsonProperty
    private final String decision;
    @JsonProperty
    private final String reason;
    @JsonProperty
    private final String ruleUuid;
    @JsonProperty
    private final DocRef fragment;
    @JsonProperty
    private final boolean provisional;
    @JsonProperty
    private final Double score;
    @JsonProperty
    private final String recordBoundary;
    @JsonProperty
    private final List<StageVerdict> verdicts;
    @JsonProperty
    private final List<SupervisorTurn> transcript;

    @JsonCreator
    public ShapeshifterAiStepDetails(@JsonProperty("dryRun") final boolean dryRun,
                                     @JsonProperty("document") final DocRef document,
                                     @JsonProperty("shapeId") final String shapeId,
                                     @JsonProperty("shape") final Map<String, String> shape,
                                     @JsonProperty("decision") final String decision,
                                     @JsonProperty("reason") final String reason,
                                     @JsonProperty("ruleUuid") final String ruleUuid,
                                     @JsonProperty("fragment") final DocRef fragment,
                                     @JsonProperty("provisional") final boolean provisional,
                                     @JsonProperty("score") final Double score,
                                     @JsonProperty("recordBoundary") final String recordBoundary,
                                     @JsonProperty("verdicts") final List<StageVerdict> verdicts,
                                     @JsonProperty("transcript") final List<SupervisorTurn> transcript) {
        this.dryRun = dryRun;
        this.document = document;
        this.shapeId = shapeId;
        this.shape = shape;
        this.decision = decision;
        this.reason = reason;
        this.ruleUuid = ruleUuid;
        this.fragment = fragment;
        this.provisional = provisional;
        this.score = score;
        this.recordBoundary = recordBoundary;
        this.verdicts = verdicts;
        this.transcript = transcript;
    }

    /**
     * Whether this was a step rather than a run (A30). Stepping routes and serves and does nothing else,
     * so what a stage pane shows for an unbound shape is what the stage <em>would</em> have done.
     */
    public boolean isDryRun() {
        return dryRun;
    }

    public DocRef getDocument() {
        return document;
    }

    public String getShapeId() {
        return shapeId;
    }

    /**
     * The learning key's values for this stream (design 01 §3): what makes it the shape it is.
     */
    public Map<String, String> getShape() {
        return shape;
    }

    /**
     * What was decided, named as design 02 §4 names it: bound, promoted, drafted, sentinelled, given up,
     * retracted, kept, rebound, provisional — or <em>would</em>, where this was a step.
     */
    public String getDecision() {
        return decision;
    }

    public String getReason() {
        return reason;
    }

    public String getRuleUuid() {
        return ruleUuid;
    }

    /**
     * The fragment that produced the output, or the one a step says it would have used; null where
     * nothing was bound and nothing would have been.
     */
    public DocRef getFragment() {
        return fragment;
    }

    public boolean isProvisional() {
        return provisional;
    }

    public Double getScore() {
        return score;
    }

    /**
     * What one record was in this stream (A35), as the rule carried it; null for a chain over raw text,
     * where the parser's own configuration cuts the records.
     */
    public String getRecordBoundary() {
        return recordBoundary;
    }

    public List<StageVerdict> getVerdicts() {
        return verdicts;
    }

    /**
     * Every exchange with the model, oldest first; empty where the stream was served rather than learned.
     */
    public List<SupervisorTurn> getTranscript() {
        return transcript;
    }

    @Override
    public String toString() {
        return decision + ": " + reason;
    }
}
