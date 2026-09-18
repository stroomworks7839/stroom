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
import stroom.docs.shared.Description;
import stroom.docstore.shared.AbstractDoc;
import stroom.docstore.shared.DocumentType;
import stroom.docstore.shared.DocumentTypeRegistry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The configuration of one supervised pipeline stage (design 01 §3, ruling A3): what it learns and
 * binds on, which model it may call and how, how many candidates an attempt gets, how candidates are
 * scored, when one is promoted and whether a person looks first, and the routing table of variants.
 * <p>
 * Every numeric default here is provisional in the sense of §2.1 — a configured default with a recorded
 * justification, to be revised once real feeds have been measured. Each field's javadoc names the section
 * or ruling it serves.
 * <p>
 * Runtime state deliberately does not live here: shape status, the ledger, regression records,
 * per-stream bindings, learning leases and error-mode state are rows (A26) or streams. The routing
 * table is the one part the supervisor rewrites (§7.3 rule 2). The replay unit is not a setting either:
 * it is a property of the fragment a rule binds, fixed by the stage's position (A1 as revised).
 */
@Description(
        "Configures one Shapeshifter AI stage: what it learns and binds on, the model and instructions it may " +
        "use, its candidate and budget limits, the scorers that judge each candidate, the promotion gate and " +
        "the routing table of transform variants.")
@JsonPropertyOrder({
        "type",
        "uuid",
        "name",
        "version",
        "createTimeMs",
        "updateTimeMs",
        "createUser",
        "updateUser",
        "description",
        "executionMode",
        "learningMode",
        "errorModeAfter",
        "model",
        "learningKey",
        "relearnThreshold",
        "allowedElements",
        "instructions",
        "dialogue",
        "maxAttempts",
        "attemptBudgetMs",
        "tokenBudget",
        "sampleRedaction",
        "sampleSizeLimit",
        "promotionMode",
        "promotionFloor",
        "heldOutFraction",
        "minRecordsPerShape",
        "regressionCap",
        "regressionRetentionDays",
        "scorers",
        "routingTable"
})
@JsonInclude(Include.NON_NULL)
public class ShapeshifterAiDoc extends AbstractDoc {

    public static final String TYPE = "ShapeshifterAi";
    public static final DocumentType DOCUMENT_TYPE = DocumentTypeRegistry.SHAPESHIFTER_AI_DOCUMENT_TYPE;

    static final ExecutionMode DEFAULT_EXECUTION_MODE = ExecutionMode.DEFERRED;
    static final LearningMode DEFAULT_LEARNING_MODE = LearningMode.DISABLED;
    /**
     * Abandoned attempts or sentinelled streams in a row before a feed enters error mode (A24). Five is a
     * streak, not a bad afternoon; provisional until real feeds say otherwise (§2.1).
     */
    static final int DEFAULT_ERROR_MODE_AFTER = 5;
    static final List<String> DEFAULT_LEARNING_KEY = RoutingFields.DEFAULT_LEARNING_KEY;
    /**
     * Below the promotion floor, so that a rule promoted at the floor is not relearned at once.
     */
    static final double DEFAULT_RELEARN_THRESHOLD = 0.8;
    static final PromotionMode DEFAULT_PROMOTION_MODE = PromotionMode.AUTOMATIC;
    /**
     * Ruling A10's initial set: what the AI may be asked to write until a Shapeshifter AI document says otherwise.
     */
    static final List<String> DEFAULT_ALLOWED_ELEMENTS =
            List.of("DSParser", "JSONParser", "XMLParser", "XSLTFilter");
    /**
     * Five, not three: the first live run (design 02 §6.2) found three right for a simple feed and one short
     * for a transform that needs a rarer schema branch; the attempt budget is the real bound (A5).
     */
    static final int DEFAULT_MAX_ATTEMPTS = 5;
    /**
     * The direct preset, unchanged: on the feeds measured so far it reached the same scores at half the tokens
     * (design 02 §6.3).
     */
    static final DialogueDefinition DEFAULT_DIALOGUE = DialogueDefinition.of(DialogueShape.DIRECT);
    static final long DEFAULT_ATTEMPT_BUDGET_MS = 60_000L;
    static final SampleRedaction DEFAULT_SAMPLE_REDACTION = SampleRedaction.REDACTED;
    static final int DEFAULT_SAMPLE_SIZE_LIMIT = 8_192;
    static final double DEFAULT_PROMOTION_FLOOR = 0.9;
    static final double DEFAULT_HELD_OUT_FRACTION = 0.2;
    static final int DEFAULT_MIN_RECORDS_PER_SHAPE = 10;
    static final int DEFAULT_REGRESSION_CAP = 100;

    @JsonProperty
    private final String description;
    /**
     * Learn inside the processing pipeline or as a deferred task. Ruling A5: a document setting, deferred
     * by default, independent of where the model is.
     */
    @JsonProperty
    private final ExecutionMode executionMode;
    /**
     * Whether the model may be called at all. Off by default because §11 makes Shapeshifter AI opt-in per
     * document; off still selects among bound variants and promotes what clears the floor.
     */
    @JsonProperty
    private final LearningMode learningMode;
    /**
     * The streak of abandoned attempts or sentinelled streams on one feed that opens the breaker (A24).
     */
    @JsonProperty
    private final int errorModeAfter;
    /**
     * The OpenAI-compatible model document the stage calls (§10, A13).
     */
    @JsonProperty
    private final DocRef model;
    /**
     * The fields a learned rule binds on and the chain question sees, in order (A29): names from
     * {@link RoutingFields#NAMES}. A shape is one value of this key. Feed and type by default; the shape
     * signature is added where one feed carries several kinds of record.
     */
    @JsonProperty
    private final List<String> learningKey;
    /**
     * The rolling per-record score below which a bound shape is marked for relearning (A29, §5), so that a
     * new kind of record inside a bound shape — which never misses the routing table — is still learned.
     */
    @JsonProperty
    private final double relearnThreshold;
    /**
     * Pipeline element type names the chain question may choose from (A21); A10's initial set by default.
     * With exactly one entry the chain question is not asked.
     */
    @JsonProperty
    private final List<String> allowedElements;
    /**
     * Free-text instructions prepended to the prompt contract of §10.
     */
    @JsonProperty
    private final String instructions;
    /**
     * Candidates an attempt may try before the shape is given up on (§3, §7.4). The name predates the
     * split of <em>attempt</em> from <em>candidate</em> in design 01 §3 and is kept for the stored form.
     */
    @JsonProperty
    private final int maxAttempts;
    /**
     * The dialogue the stage holds with the model (A32, A33; design 01 §10.2).
     */
    @JsonProperty
    private final DialogueDefinition dialogue;
    /**
     * Wall-clock budget for one attempt. Ruling A5 makes the budget mandatory; there is no unlimited value.
     */
    @JsonProperty
    private final long attemptBudgetMs;
    /**
     * Token budget for one attempt (§10); null is unlimited.
     */
    @JsonProperty
    private final Long tokenBudget;
    /**
     * Whether samples sent to the model are reduced to token classes or sent raw. Ruling A17.
     */
    @JsonProperty
    private final SampleRedaction sampleRedaction;
    /**
     * Largest sample, in bytes, sent to the model. Ruling A17's size cap.
     */
    @JsonProperty
    private final int sampleSizeLimit;
    /**
     * Whether a learned rule goes live on promotion or is appended as a draft for a person to approve (A25).
     */
    @JsonProperty
    private final PromotionMode promotionMode;
    /**
     * Absolute weighted score a candidate must reach on the held-out sample before it can be promoted.
     * Ruling A15's floor; the no-regression half of A15 is measured against the incumbent at run time.
     */
    @JsonProperty
    private final double promotionFloor;
    /**
     * Fraction of a shape's records withheld from the model and used only to judge promotion. Ruling A14.
     */
    @JsonProperty
    private final double heldOutFraction;
    /**
     * Records a shape must have accumulated before promotion is judged at all; below this a candidate
     * that clears the floor is bound provisionally and waits for more. Ruling A14, A5.
     */
    @JsonProperty
    private final int minRecordsPerShape;
    /**
     * Most previously-accepted records retained per rule for the regression check. Ruling A18.
     */
    @JsonProperty
    private final int regressionCap;
    /**
     * Days regression records are retained, capped by the source feed's own retention; null is the feed's.
     * Ruling A18.
     */
    @JsonProperty
    private final Integer regressionRetentionDays;
    /**
     * The scorers applied to every attempt, with weights and thresholds. §8.4.
     */
    @JsonProperty
    private final List<ScorerSetting> scorers;
    /**
     * Selector to promoted variant, a pipeline fragment (A20). §3; the one part the supervisor rewrites,
     * §7.3 rule 2.
     */
    @JsonProperty
    private final List<RoutingRule> routingTable;

    @JsonCreator
    public ShapeshifterAiDoc(
            @JsonProperty("uuid") final String uuid,
            @JsonProperty("name") final String name,
            @JsonProperty("version") final String version,
            @JsonProperty("createTimeMs") final Long createTimeMs,
            @JsonProperty("updateTimeMs") final Long updateTimeMs,
            @JsonProperty("createUser") final String createUser,
            @JsonProperty("updateUser") final String updateUser,
            @JsonProperty("description") final String description,
            @JsonProperty("executionMode") final ExecutionMode executionMode,
            @JsonProperty("learningMode") final LearningMode learningMode,
            @JsonProperty("errorModeAfter") final Integer errorModeAfter,
            @JsonProperty("model") final DocRef model,
            @JsonProperty("learningKey") final List<String> learningKey,
            @JsonProperty("relearnThreshold") final Double relearnThreshold,
            @JsonProperty("allowedElements") final List<String> allowedElements,
            @JsonProperty("instructions") final String instructions,
            @JsonProperty("dialogue") final DialogueDefinition dialogue,
            @JsonProperty("maxAttempts") final Integer maxAttempts,
            @JsonProperty("attemptBudgetMs") final Long attemptBudgetMs,
            @JsonProperty("tokenBudget") final Long tokenBudget,
            @JsonProperty("sampleRedaction") final SampleRedaction sampleRedaction,
            @JsonProperty("sampleSizeLimit") final Integer sampleSizeLimit,
            @JsonProperty("promotionMode") final PromotionMode promotionMode,
            @JsonProperty("promotionFloor") final Double promotionFloor,
            @JsonProperty("heldOutFraction") final Double heldOutFraction,
            @JsonProperty("minRecordsPerShape") final Integer minRecordsPerShape,
            @JsonProperty("regressionCap") final Integer regressionCap,
            @JsonProperty("regressionRetentionDays") final Integer regressionRetentionDays,
            @JsonProperty("scorers") final List<ScorerSetting> scorers,
            @JsonProperty("routingTable") final List<RoutingRule> routingTable) {
        super(TYPE, uuid, name, version, createTimeMs, updateTimeMs, createUser, updateUser);
        this.description = description;
        this.executionMode = Objects.requireNonNullElse(executionMode, DEFAULT_EXECUTION_MODE);
        this.learningMode = Objects.requireNonNullElse(learningMode, DEFAULT_LEARNING_MODE);
        this.errorModeAfter = Objects.requireNonNullElse(errorModeAfter, DEFAULT_ERROR_MODE_AFTER);
        this.model = model;
        this.learningKey = learningKey == null
                ? DEFAULT_LEARNING_KEY
                : List.copyOf(learningKey);
        this.relearnThreshold = Objects.requireNonNullElse(relearnThreshold, DEFAULT_RELEARN_THRESHOLD);
        this.allowedElements = allowedElements == null
                ? DEFAULT_ALLOWED_ELEMENTS
                : List.copyOf(allowedElements);
        this.instructions = instructions;
        this.dialogue = Objects.requireNonNullElse(dialogue, DEFAULT_DIALOGUE);
        this.maxAttempts = Objects.requireNonNullElse(maxAttempts, DEFAULT_MAX_ATTEMPTS);
        this.attemptBudgetMs = Objects.requireNonNullElse(attemptBudgetMs, DEFAULT_ATTEMPT_BUDGET_MS);
        this.tokenBudget = tokenBudget;
        this.sampleRedaction = Objects.requireNonNullElse(sampleRedaction, DEFAULT_SAMPLE_REDACTION);
        this.sampleSizeLimit = Objects.requireNonNullElse(sampleSizeLimit, DEFAULT_SAMPLE_SIZE_LIMIT);
        this.promotionMode = Objects.requireNonNullElse(promotionMode, DEFAULT_PROMOTION_MODE);
        this.promotionFloor = Objects.requireNonNullElse(promotionFloor, DEFAULT_PROMOTION_FLOOR);
        this.heldOutFraction = Objects.requireNonNullElse(heldOutFraction, DEFAULT_HELD_OUT_FRACTION);
        this.minRecordsPerShape = Objects.requireNonNullElse(minRecordsPerShape, DEFAULT_MIN_RECORDS_PER_SHAPE);
        this.regressionCap = Objects.requireNonNullElse(regressionCap, DEFAULT_REGRESSION_CAP);
        this.regressionRetentionDays = regressionRetentionDays;
        this.scorers = copyOrEmpty(scorers);
        this.routingTable = copyOrEmpty(routingTable);
    }

    /**
     * Lists default to empty rather than null so that a doc written before a list existed and the same doc
     * read back compare equal, and so that nothing walking them has to null-check.
     */
    private static <T> List<T> copyOrEmpty(final List<T> list) {
        return list == null
                ? Collections.emptyList()
                : List.copyOf(list);
    }

    /**
     * @return A new builder for creating a {@link DocRef} for this document's type.
     */
    public static DocRef.TypedBuilder buildDocRef() {
        return DocRef.builder(TYPE);
    }

    public String getDescription() {
        return description;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public LearningMode getLearningMode() {
        return learningMode;
    }

    public int getErrorModeAfter() {
        return errorModeAfter;
    }

    public DocRef getModel() {
        return model;
    }

    public List<String> getLearningKey() {
        return learningKey;
    }

    public double getRelearnThreshold() {
        return relearnThreshold;
    }

    public List<String> getAllowedElements() {
        return allowedElements;
    }

    public String getInstructions() {
        return instructions;
    }

    public DialogueDefinition getDialogue() {
        return dialogue;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getAttemptBudgetMs() {
        return attemptBudgetMs;
    }

    public Long getTokenBudget() {
        return tokenBudget;
    }

    public SampleRedaction getSampleRedaction() {
        return sampleRedaction;
    }

    public int getSampleSizeLimit() {
        return sampleSizeLimit;
    }

    public PromotionMode getPromotionMode() {
        return promotionMode;
    }

    public double getPromotionFloor() {
        return promotionFloor;
    }

    public double getHeldOutFraction() {
        return heldOutFraction;
    }

    public int getMinRecordsPerShape() {
        return minRecordsPerShape;
    }

    public int getRegressionCap() {
        return regressionCap;
    }

    public Integer getRegressionRetentionDays() {
        return regressionRetentionDays;
    }

    public List<ScorerSetting> getScorers() {
        return scorers;
    }

    public List<RoutingRule> getRoutingTable() {
        return routingTable;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final ShapeshifterAiDoc that = (ShapeshifterAiDoc) o;
        return maxAttempts == that.maxAttempts &&
               attemptBudgetMs == that.attemptBudgetMs &&
               sampleSizeLimit == that.sampleSizeLimit &&
               errorModeAfter == that.errorModeAfter &&
               Double.compare(relearnThreshold, that.relearnThreshold) == 0 &&
               Double.compare(promotionFloor, that.promotionFloor) == 0 &&
               Double.compare(heldOutFraction, that.heldOutFraction) == 0 &&
               minRecordsPerShape == that.minRecordsPerShape &&
               regressionCap == that.regressionCap &&
               Objects.equals(description, that.description) &&
               executionMode == that.executionMode &&
               learningMode == that.learningMode &&
               Objects.equals(dialogue, that.dialogue) &&
               promotionMode == that.promotionMode &&
               Objects.equals(model, that.model) &&
               Objects.equals(learningKey, that.learningKey) &&
               Objects.equals(allowedElements, that.allowedElements) &&
               Objects.equals(instructions, that.instructions) &&
               Objects.equals(tokenBudget, that.tokenBudget) &&
               sampleRedaction == that.sampleRedaction &&
               Objects.equals(regressionRetentionDays, that.regressionRetentionDays) &&
               Objects.equals(scorers, that.scorers) &&
               Objects.equals(routingTable, that.routingTable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(),
                description,
                executionMode,
                learningMode,
                errorModeAfter,
                model,
                learningKey,
                relearnThreshold,
                allowedElements,
                instructions,
                dialogue,
                maxAttempts,
                attemptBudgetMs,
                tokenBudget,
                sampleRedaction,
                sampleSizeLimit,
                promotionMode,
                promotionFloor,
                heldOutFraction,
                minRecordsPerShape,
                regressionCap,
                regressionRetentionDays,
                scorers,
                routingTable);
    }

    @Override
    public String toString() {
        return "ShapeshifterAiDoc{" +
               "name='" + getName() + '\'' +
               ", executionMode=" + executionMode +
               ", learningMode=" + learningMode +
               ", errorModeAfter=" + errorModeAfter +
               ", model=" + model +
               ", learningKey=" + learningKey +
               ", relearnThreshold=" + relearnThreshold +
               ", allowedElements=" + allowedElements +
               ", dialogue=" + dialogue +
               ", maxAttempts=" + maxAttempts +
               ", attemptBudgetMs=" + attemptBudgetMs +
               ", tokenBudget=" + tokenBudget +
               ", sampleRedaction=" + sampleRedaction +
               ", sampleSizeLimit=" + sampleSizeLimit +
               ", promotionMode=" + promotionMode +
               ", promotionFloor=" + promotionFloor +
               ", heldOutFraction=" + heldOutFraction +
               ", minRecordsPerShape=" + minRecordsPerShape +
               ", regressionCap=" + regressionCap +
               ", regressionRetentionDays=" + regressionRetentionDays +
               ", scorers=" + scorers +
               ", routingTable=" + routingTable +
               '}';
    }

    public Builder copy() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }


    // --------------------------------------------------------------------------------


    public static final class Builder extends AbstractBuilder<ShapeshifterAiDoc, Builder> {

        private String description;
        private ExecutionMode executionMode = DEFAULT_EXECUTION_MODE;
        private LearningMode learningMode = DEFAULT_LEARNING_MODE;
        private int errorModeAfter = DEFAULT_ERROR_MODE_AFTER;
        private DocRef model;
        private List<String> learningKey = DEFAULT_LEARNING_KEY;
        private double relearnThreshold = DEFAULT_RELEARN_THRESHOLD;
        private List<String> allowedElements = DEFAULT_ALLOWED_ELEMENTS;
        private String instructions;
        private DialogueDefinition dialogue = DEFAULT_DIALOGUE;
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private long attemptBudgetMs = DEFAULT_ATTEMPT_BUDGET_MS;
        private Long tokenBudget;
        private SampleRedaction sampleRedaction = DEFAULT_SAMPLE_REDACTION;
        private int sampleSizeLimit = DEFAULT_SAMPLE_SIZE_LIMIT;
        private PromotionMode promotionMode = DEFAULT_PROMOTION_MODE;
        private double promotionFloor = DEFAULT_PROMOTION_FLOOR;
        private double heldOutFraction = DEFAULT_HELD_OUT_FRACTION;
        private int minRecordsPerShape = DEFAULT_MIN_RECORDS_PER_SHAPE;
        private int regressionCap = DEFAULT_REGRESSION_CAP;
        private Integer regressionRetentionDays;
        private List<ScorerSetting> scorers = Collections.emptyList();
        private List<RoutingRule> routingTable = Collections.emptyList();

        private Builder() {
        }

        private Builder(final ShapeshifterAiDoc doc) {
            super(doc);
            this.description = doc.description;
            this.executionMode = doc.executionMode;
            this.learningMode = doc.learningMode;
            this.errorModeAfter = doc.errorModeAfter;
            this.model = doc.model;
            this.learningKey = doc.learningKey;
            this.relearnThreshold = doc.relearnThreshold;
            this.allowedElements = doc.allowedElements;
            this.instructions = doc.instructions;
            this.dialogue = doc.dialogue;
            this.maxAttempts = doc.maxAttempts;
            this.attemptBudgetMs = doc.attemptBudgetMs;
            this.tokenBudget = doc.tokenBudget;
            this.sampleRedaction = doc.sampleRedaction;
            this.sampleSizeLimit = doc.sampleSizeLimit;
            this.promotionMode = doc.promotionMode;
            this.promotionFloor = doc.promotionFloor;
            this.heldOutFraction = doc.heldOutFraction;
            this.minRecordsPerShape = doc.minRecordsPerShape;
            this.regressionCap = doc.regressionCap;
            this.regressionRetentionDays = doc.regressionRetentionDays;
            this.scorers = doc.scorers;
            this.routingTable = doc.routingTable;
        }

        public Builder description(final String description) {
            this.description = description;
            return self();
        }

        public Builder executionMode(final ExecutionMode executionMode) {
            this.executionMode = executionMode;
            return self();
        }

        public Builder learningMode(final LearningMode learningMode) {
            this.learningMode = learningMode;
            return self();
        }

        public Builder errorModeAfter(final int errorModeAfter) {
            this.errorModeAfter = errorModeAfter;
            return self();
        }

        public Builder model(final DocRef model) {
            this.model = model;
            return self();
        }

        public Builder learningKey(final List<String> learningKey) {
            this.learningKey = learningKey;
            return self();
        }

        public Builder relearnThreshold(final double relearnThreshold) {
            this.relearnThreshold = relearnThreshold;
            return self();
        }

        public Builder allowedElements(final List<String> allowedElements) {
            this.allowedElements = allowedElements;
            return self();
        }

        public Builder instructions(final String instructions) {
            this.instructions = instructions;
            return self();
        }

        public Builder dialogue(final DialogueDefinition dialogue) {
            this.dialogue = dialogue;
            return self();
        }

        /**
         * The preset alone, with its own steps and the built-in text.
         */
        public Builder dialogueShape(final DialogueShape preset) {
            this.dialogue = DialogueDefinition.of(preset);
            return self();
        }

        public Builder maxAttempts(final int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return self();
        }

        public Builder attemptBudgetMs(final long attemptBudgetMs) {
            this.attemptBudgetMs = attemptBudgetMs;
            return self();
        }

        public Builder tokenBudget(final Long tokenBudget) {
            this.tokenBudget = tokenBudget;
            return self();
        }

        public Builder sampleRedaction(final SampleRedaction sampleRedaction) {
            this.sampleRedaction = sampleRedaction;
            return self();
        }

        public Builder sampleSizeLimit(final int sampleSizeLimit) {
            this.sampleSizeLimit = sampleSizeLimit;
            return self();
        }

        public Builder promotionMode(final PromotionMode promotionMode) {
            this.promotionMode = promotionMode;
            return self();
        }

        public Builder promotionFloor(final double promotionFloor) {
            this.promotionFloor = promotionFloor;
            return self();
        }

        public Builder heldOutFraction(final double heldOutFraction) {
            this.heldOutFraction = heldOutFraction;
            return self();
        }

        public Builder minRecordsPerShape(final int minRecordsPerShape) {
            this.minRecordsPerShape = minRecordsPerShape;
            return self();
        }

        public Builder regressionCap(final int regressionCap) {
            this.regressionCap = regressionCap;
            return self();
        }

        public Builder regressionRetentionDays(final Integer regressionRetentionDays) {
            this.regressionRetentionDays = regressionRetentionDays;
            return self();
        }

        public Builder scorers(final List<ScorerSetting> scorers) {
            this.scorers = scorers;
            return self();
        }

        public Builder routingTable(final List<RoutingRule> routingTable) {
            this.routingTable = routingTable;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        public ShapeshifterAiDoc build() {
            return new ShapeshifterAiDoc(
                    uuid,
                    name,
                    version,
                    createTimeMs,
                    updateTimeMs,
                    createUser,
                    updateUser,
                    description,
                    executionMode,
                    learningMode,
                    errorModeAfter,
                    model,
                    learningKey,
                    relearnThreshold,
                    allowedElements,
                    instructions,
                    dialogue,
                    maxAttempts,
                    attemptBudgetMs,
                    tokenBudget,
                    sampleRedaction,
                    sampleSizeLimit,
                    promotionMode,
                    promotionFloor,
                    heldOutFraction,
                    minRecordsPerShape,
                    regressionCap,
                    regressionRetentionDays,
                    scorers,
                    routingTable);
        }
    }
}
