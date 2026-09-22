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
import stroom.meta.shared.MetaFields;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.pipeline.shared.PipelineDoc;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.util.json.JsonUtil;
import stroom.util.shared.Severity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class TestShapeshifterAiDoc {

    @Test
    void fullyPopulatedDocRoundTrips() {
        final ShapeshifterAiDoc doc = populated();

        final String json = JsonUtil.writeValueAsString(doc);
        final ShapeshifterAiDoc read = JsonUtil.readValue(json, ShapeshifterAiDoc.class);

        assertThat(read).isEqualTo(doc);
        assertThat(read.getScorers()).containsExactlyElementsOf(doc.getScorers());
        assertThat(read.getRoutingTable()).containsExactlyElementsOf(doc.getRoutingTable());
        assertThat(read.getPlan().getSteps()).extracting(PlanStep::format)
                .containsExactly("CHAIN", "TARGET when text kinds 1", "CONFIGURE candidates 2");
    }

    @Test
    void aLearningPlanKnowsWhatIsWrongWithItsSteps() {
        assertThat(LearningPlan.of(PlanExample.TARGET_FIRST).problems()).isEmpty();
        assertThat(new LearningPlan(null, null, null).getSteps()).isEqualTo(PlanExample.DIRECT.steps());
        assertThat(LearningPlan.of(PlanExample.DIRECT).withSteps(List.of(
                PlanStep.of(QuestionKind.CONFIGURE), PlanStep.of(QuestionKind.CHAIN))).problems())
                .containsExactly("The first step must be CHAIN", "The last step must be CONFIGURE");
        assertThat(LearningPlan.of(PlanExample.DIRECT).withSteps(List.of(
                PlanStep.of(QuestionKind.CHAIN), PlanStep.of(QuestionKind.SPLIT),
                PlanStep.of(QuestionKind.SPLIT), PlanStep.of(QuestionKind.CONFIGURE))).problems())
                .containsExactly("SPLIT may appear once, not 2 times");
        assertThat(LearningPlan.of(PlanExample.DIRECT).withSteps(List.of()).problems())
                .containsExactly("The plan has no steps");
        // A transition out of CHAIN would leave the plan with no chain to run: refused at the door, not at a
        // null chain in the interpreter.
        assertThat(LearningPlan.of(PlanExample.DIRECT).withSteps(List.of(
                PlanStep.parse("CHAIN on refused goto end"), PlanStep.of(QuestionKind.CONFIGURE))).problems())
                .containsExactly("CHAIN takes no transition; nothing can run until the chain is settled");
        // Two transitions on one outcome, or two on spent, contradict each other: refused rather than the
        // first silently winning.
        assertThat(LearningPlan.of(PlanExample.DIRECT).withSteps(List.of(
                PlanStep.parse("CHAIN"), PlanStep.parse("CONFIGURE on spent goto end on spent abandon"))).problems())
                .containsExactly("Step 'configure' says 'on spent' twice; the first would be taken and the second "
                                 + "never");
        assertThat(LearningPlan.of(PlanExample.DIRECT).withSteps(List.of(
                PlanStep.parse("CHAIN"),
                PlanStep.parse("CONFIGURE on refused goto end on refused abandon on spent abandon"))).problems())
                .containsExactly("Step 'configure' says 'on refused' twice; the first would be taken and the "
                                 + "second never");
    }

    @Test
    void anIdIsAWordHoweverItArrives() {
        // The line form checks the id; the JSON form must too, or a saved id the line form cannot read back
        // breaks the Learning tab's round trip.
        assertThatThrownBy(() -> new PlanStep("my step", QuestionKind.CONFIGURE, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'my step' is not a step id");
        assertThat(new PlanStep(" Again ", QuestionKind.CONFIGURE, null, null, null, null, null, null).getId())
                .isEqualTo("again");
    }

    @Test
    void aPlanIsAGraphOverTypedOutcomes() {
        // A37: every example holds; ids default to the kind or the role; a goto must name a step, once.
        for (final PlanExample example : PlanExample.values()) {
            assertThat(LearningPlan.of(example).problems()).describedAs(example.name()).isEmpty();
        }
        assertThat(LearningPlan.of(PlanExample.ESCALATING).getSteps()).extracting(PlanStep::effectiveId)
                .containsExactly("chain", "split", "parser", "first", "target", "again", "transform");
        assertThat(LearningPlan.of(PlanExample.ESCALATING).step("split").getWhen()).isEqualTo(StepGuard.JSON);
        assertThat(LearningPlan.of(PlanExample.ESCALATING).step("first").getTransitions())
                .extracting(Transition::format).containsExactly("on passed goto end", "on spent goto target");
        assertThat(LearningPlan.of(PlanExample.DIRECT).withSteps(List.of(
                PlanStep.parse("CHAIN"), PlanStep.parse("end: CONFIGURE"))).problems())
                .containsExactly("'end' is the end of the plan and cannot name a step");
        assertThat(LearningPlan.of(PlanExample.DIRECT).withSteps(List.of(
                PlanStep.parse("CHAIN"), PlanStep.parse("CONFIGURE parser"), PlanStep.parse("CONFIGURE parser")))
                .problems())
                .containsExactly("Two steps are named 'parser'; give one an id");
        assertThat(LearningPlan.of(PlanExample.DIRECT).withSteps(List.of(
                PlanStep.parse("CHAIN"), PlanStep.parse("CONFIGURE on refused goto nowhere on spent abandon")))
                .problems())
                .containsExactly("'on refused goto nowhere' in step 'configure' names no step");
        // A step's name is matched without regard to case, as everything else on the line is.
        assertThat(LearningPlan.of(PlanExample.DIRECT).withSteps(List.of(
                PlanStep.parse("CHAIN"),
                PlanStep.parse("First: CONFIGURE parser on spent goto Target on passed goto END"),
                PlanStep.parse("TARGET"), PlanStep.parse("CONFIGURE transform"))).problems()).isEmpty();
        assertThat(PlanStep.parse("First: CONFIGURE parser on spent goto Target").format())
                .isEqualTo("first: CONFIGURE parser on spent goto target");
        // An id given on purpose that shadows another step's default name is a collision too.
        assertThat(LearningPlan.of(PlanExample.DIRECT).withSteps(List.of(
                PlanStep.parse("CHAIN"), PlanStep.parse("target: CONFIGURE parser"), PlanStep.parse("TARGET"),
                PlanStep.parse("CONFIGURE transform"))).problems())
                .containsExactly("Two steps are named 'target'; give one an id");
    }

    @Test
    void aStepLineCarriesItsIdRoleChecksAndTransitions() {
        final String line = "first: CONFIGURE transform candidates 2 checks conformance,fidelity "
                            + "on preservation-short goto parser on spent abandon";
        final PlanStep step = PlanStep.parse(line);
        assertThat(step.format()).isEqualTo(line);
        assertThat(step.getId()).isEqualTo("first");
        assertThat(step.getRole()).isEqualTo(ConfigureRole.TRANSFORM);
        assertThat(step.getChecks()).containsExactly(Check.CONFORMANCE, Check.FIDELITY);
        assertThat(step.transitionOn(StepOutcome.PRESERVATION_SHORT).getGoTo()).isEqualTo("parser");
        assertThat(step.transitionOn(StepOutcome.REFUSED)).isNull();
        assertThat(step.transitionOnSpent().abandons()).isTrue();
        assertThat(PlanStep.parse("CONFIGURE parser").effectiveId()).isEqualTo("parser");
        assertThat(PlanStep.parse("split").effectiveId()).isEqualTo("split");
        assertThatThrownBy(() -> PlanStep.parse("SPLIT parser")).hasMessageContaining("Only CONFIGURE takes a role");
        assertThatThrownBy(() -> PlanStep.parse("CONFIGURE checks bogus"))
                .hasMessageContaining("'bogus' is not a check");
        assertThatThrownBy(() -> PlanStep.parse("CONFIGURE on flimsy goto parser"))
                .hasMessageContaining("'on flimsy' is not an outcome");
        assertThatThrownBy(() -> PlanStep.parse("CONFIGURE on spent"))
                .hasMessageContaining("'on' needs an outcome");
        assertThatThrownBy(() -> PlanStep.parse("CONFIGURE on spent goto"))
                .hasMessageContaining("must be followed by 'goto <step>' or 'abandon'");
        assertThatThrownBy(() -> PlanStep.parse("1st: CHAIN")).hasMessageContaining("is not a step id");
        // The check's shortfall is its outcome, and the outcome reads back from its word.
        assertThat(Check.FIDELITY.shortfall()).isEqualTo(StepOutcome.FIDELITY_SHORT);
        assertThat(StepOutcome.parse("coverage-short")).isEqualTo(StepOutcome.COVERAGE_SHORT);
        assertThat(Check.of(ScorerType.COMPILE)).isNull();
    }

    @Test
    void aStepReadsBackFromItsLine() {
        assertThat(PlanStep.parse("target when xml candidates 4 kinds 2").format())
                .isEqualTo("TARGET when xml candidates 4 kinds 2");
        assertThat(PlanStep.parse("  CHAIN ").format()).isEqualTo("CHAIN");
        assertThat(PlanStep.parse("SPLIT when json").getWhen()).isEqualTo(StepGuard.JSON);
        assertThatThrownBy(() -> PlanStep.parse("SPLIT when sometimes"))
                .hasMessageContaining("'when sometimes' is not one of always, text, xml or json");
        assertThatThrownBy(() -> PlanStep.parse("SPLIT candidates"))
                .hasMessageContaining("'candidates' needs a value");
        assertThatThrownBy(() -> PlanStep.parse("SPLIT kinds 0"))
                .hasMessageContaining("'kinds' must be at least 1");
        assertThatThrownBy(() -> PlanStep.parse("ASK")).hasMessageContaining("'ASK' is not a question kind");
        // The limits hold however the step is made, not only from its line.
        assertThatThrownBy(() -> new PlanStep(QuestionKind.TARGET, null, null, 0))
                .hasMessageContaining("'kinds' must be at least 1");
    }

    @Test
    void aBlankTemplateOverrideIsNoOverride() {
        final LearningPlan plan = LearningPlan.of(PlanExample.DIRECT)
                .withTemplates(Map.of(Template.CHAIN, "  \n", Template.SPLIT, "Cut it: ${sample}"));
        assertThat(plan.getTemplates()).containsOnlyKeys(Template.SPLIT);
    }

    @Test
    void creatorAppliesDefaultsToNullArguments() {
        // The creator sees nulls for every field a stored doc predates, so the defaults must come from it
        // rather than only from the builder.
        final ShapeshifterAiDoc doc = new ShapeshifterAiDoc(
                "uuid", "name", "1", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        assertThat(doc.getExecutionMode()).isEqualTo(ExecutionMode.DEFERRED);
        assertThat(doc.getLearningMode()).isEqualTo(LearningMode.DISABLED);
        assertThat(doc.getErrorModeAfter()).isEqualTo(5);
        assertThat(doc.getLearningKey())
                .describedAs("A29: most bindings switch on feed and type alone")
                .containsExactly(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE);
        assertThat(doc.getRelearnThreshold()).isEqualTo(0.8);
        assertThat(doc.getAllowedElements())
                .describedAs("A10's initial set until a document narrows it")
                .containsExactly("DSParser", "JSONParser", "XMLParser", "XSLTFilter");
        assertThat(doc.getPlan()).isEqualTo(LearningPlan.of(PlanExample.DIRECT));
        assertThat(doc.getMaxAttempts()).describedAs("five since the first live run, design 02 §6.2").isEqualTo(5);
        assertThat(doc.getAttemptBudgetMs()).isEqualTo(60_000L);
        assertThat(doc.getTokenBudget()).isNull();
        assertThat(doc.getSampleRedaction()).isEqualTo(SampleRedaction.REDACTED);
        assertThat(doc.getSampleSizeLimit()).isEqualTo(8_192);
        assertThat(doc.getPromotionMode()).describedAs("A9").isEqualTo(PromotionMode.AUTOMATIC);
        assertThat(doc.getPromotionFloor()).isEqualTo(0.9);
        assertThat(doc.getHeldOutFraction()).isEqualTo(0.2);
        assertThat(doc.getMinRecordsPerShape()).isEqualTo(10);
        assertThat(doc.getRegressionCap()).isEqualTo(100);
        assertThat(doc.getRegressionRetentionDays()).isNull();
        assertThat(doc.getScorers()).isEmpty();
        assertThat(doc.getRoutingTable()).isEmpty();
    }

    @Test
    void builderDefaultsMatchCreatorDefaults() {
        // Client dirty-tracking compares the doc as read with the doc as written, so the two sources of
        // defaults must agree.
        final ShapeshifterAiDoc built = ShapeshifterAiDoc.builder().uuid("uuid").name("name").build();
        final ShapeshifterAiDoc created = new ShapeshifterAiDoc(
                "uuid", "name", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        assertThat(built).isEqualTo(created);
    }

    @Test
    void emptyDocOmitsNullKeys() {
        final String json = JsonUtil.writeValueAsString(ShapeshifterAiDoc.builder().uuid("uuid").build());

        assertThat(json).doesNotContain("\"model\"", "\"instructions\"", "\"tokenBudget\"", "\"description\"");
        assertThat(json).contains("\"type\" : \"ShapeshifterAi\"");
    }

    @Test
    void aScorerTakesItsTypesDefaultsWhenParametersAreAbsent() {
        // A doc written before a scorer had parameters, or a hand-written one, reads back with them.
        assertThat(new ScorerSetting(ScorerType.YIELD, null, null, null, null).getParameters())
                .isEqualTo(new YieldParameters(1.0, YieldBasis.RECORDS));
        assertThat(new ScorerSetting(ScorerType.COMPILE, null, null, null, null).getParameters()).isNull();
    }

    @Test
    void aScorerRefusesAnotherTypesParameters() {
        assertThatThrownBy(() -> new ScorerSetting(ScorerType.YIELD, null, null, null,
                new SchemaConformanceParameters("EVENTS")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SCHEMA_CONFORMANCE")
                .hasMessageContaining("YIELD");
    }

    @Test
    void aLearnedSelectorIsExactlyTheLearningKey() {
        // A22, A29: the supervisor writes the narrowest selector the variant was validated on — the
        // document's learning key, in its order — and nothing wider, whatever else the stream carries.
        final Map<String, Object> stream = Map.of(
                MetaFields.FIELD_FEED, "SYSLOG",
                MetaFields.FIELD_TYPE, "Raw Events",
                RoutingFields.SYSTEM, "Door Access",
                RoutingRule.SHAPE_SIGNATURE_FIELD, "rfc5424");
        final ExpressionOperator selector = RoutingRule.learnedSelector(
                List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE, RoutingRule.SHAPE_SIGNATURE_FIELD), stream);

        assertThat(selector.getChildren()).hasSize(3);
        assertThat(selector.getChildren())
                .extracting(child -> ((ExpressionTerm) child).getField(), child -> ((ExpressionTerm) child).getValue())
                .containsExactly(
                        tuple(MetaFields.FIELD_FEED, "SYSLOG"),
                        tuple(MetaFields.FIELD_TYPE, "Raw Events"),
                        tuple(RoutingRule.SHAPE_SIGNATURE_FIELD, "rfc5424"));
        assertThat(selector.getChildren())
                .allMatch(child -> ((ExpressionTerm) child).getCondition() == Condition.EQUALS);
        assertThat(selector.op()).isEqualTo(Op.AND);
    }

    @Test
    void aLearnedSelectorRefusesAKeyFieldTheStreamLacks() {
        // A stream without a value for a key field has no shape under that key; binding on the fields it
        // does carry would widen the rule to every value of the missing one, which §7.4 never validated.
        assertThatThrownBy(() -> RoutingRule.learnedSelector(
                List.of(MetaFields.FIELD_FEED, RoutingFields.SYSTEM),
                Map.of(MetaFields.FIELD_FEED, "SYSLOG")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RoutingFields.SYSTEM);
    }

    @Test
    void aRuleDefaultsToActiveWithNoUuid() {
        // The store assigns the uuid on save and the supervisor on creation; the client never does, so a
        // rule built without one is not yet identified and is neither a draft nor provisional.
        final RoutingRule rule = RoutingRule.builder().build();

        assertThat(rule.getUuid()).isNull();
        assertThat(rule.isDraft()).isFalse();
        assertThat(rule.isProvisional()).isFalse();
        assertThat(rule.isReserved()).describedAs("no fragment: a reserved rule").isTrue();
    }

    static ShapeshifterAiDoc populated() {
        final DocRef model = OpenAIModelDoc.buildDocRef().uuid("model-uuid").name("local-model").build();
        final DocRef fragment = PipelineDoc.buildDocRef().uuid("fragment-uuid").name("syslog-v3").build();
        final Map<String, Object> stream = Map.of(
                MetaFields.FIELD_FEED, "SYSLOG",
                MetaFields.FIELD_TYPE, "Raw Events",
                RoutingRule.SHAPE_SIGNATURE_FIELD, "rfc5424");
        return ShapeshifterAiDoc.builder()
                .uuid("doc-uuid")
                .name("syslog-ai")
                .version("1")
                .createTimeMs(1L)
                .updateTimeMs(2L)
                .createUser("creator")
                .updateUser("updater")
                .description("A document")
                .executionMode(ExecutionMode.INLINE)
                .learningMode(LearningMode.AUTOMATIC)
                .errorModeAfter(2)
                .model(model)
                .learningKey(List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE, RoutingRule.SHAPE_SIGNATURE_FIELD))
                .relearnThreshold(0.75)
                .allowedElements(List.of("XSLTFilter"))
                .instructions("Prefer named fields.")
                .plan(new LearningPlan(
                        List.of(PlanStep.parse("CHAIN"), PlanStep.parse("TARGET when text kinds 1"),
                                PlanStep.parse("CONFIGURE candidates 2")),
                        Map.of(Template.CHAIN, "Pick: ${elements}\n${sample}"), 3))
                .maxAttempts(5)
                .attemptBudgetMs(120_000L)
                .tokenBudget(4_096L)
                .sampleRedaction(SampleRedaction.RAW)
                .sampleSizeLimit(1_024)
                .promotionMode(PromotionMode.REVIEW)
                .promotionFloor(0.95)
                .heldOutFraction(0.25)
                .minRecordsPerShape(20)
                .regressionCap(50)
                .regressionRetentionDays(30)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 1.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 2.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(0.9, YieldBasis.LINES)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 0.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 2.0, 0.7, true,
                                new ExtractionQualityParameters(true,
                                        List.of("/Event/EventTime", "/Event/EventSource/User/Id"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
                                new BusinessRulesParameters(List.of(
                                        new XPathAssertion("interactive events name the user",
                                                "not(EventDetail/Authenticate) or EventDetail/Authenticate/User/Id")),
                                        false)),
                        new ScorerSetting(ScorerType.ERROR_LOAD, 1.0, 0.9, false,
                                new ErrorLoadParameters(Severity.WARNING)),
                        new ScorerSetting(ScorerType.EVENT_CLASSIFICATION, 1.0, 0.8, false,
                                new EventClassificationParameters(List.of("logon", "logoff")))))
                .routingTable(List.of(
                        RoutingRule.builder()
                                .recordBoundary(RecordBoundary.ofArray("events"))
                                .uuid("rule-1")
                                .expression(RoutingRule.learnedSelector(
                                        List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE,
                                                RoutingRule.SHAPE_SIGNATURE_FIELD),
                                        stream))
                                .pipeline(fragment)
                                .pinned(true)
                                .promotedTimeMs(3L)
                                .score(0.97)
                                .build(),
                        RoutingRule.builder()
                                .uuid("rule-2")
                                .expression(RoutingRule.learnedSelector(RoutingFields.DEFAULT_LEARNING_KEY, stream))
                                .pipeline(fragment)
                                .draft(true)
                                .provisional(true)
                                .build(),
                        RoutingRule.builder()
                                .build()))
                .build();
    }
}
