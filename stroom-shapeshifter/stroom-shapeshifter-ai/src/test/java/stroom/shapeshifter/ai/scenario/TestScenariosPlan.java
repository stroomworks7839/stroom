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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.LearningPlan;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.PlanStep;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 02 §5, scenarios 41 and 42 (ruling A37): the plan is a graph. A plan that starts direct asks for a
 * target only when the transform stays short; a shortfall the transform cannot fix — a value its input
 * lacks — goes back to the parser by the transition the plan declares, and a transition is taken once.
 */
class TestScenariosPlan {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String TWO_FIELDS = Scenarios.resource("csv-two-fields.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DEGENERATE = Scenarios.resource("csv-degenerate.xsl");
    private static final String DOC = "doc-1";

    private static ShapeshifterAiDoc doc(final LearningPlan plan) {
        return ShapeshifterAiDoc.builder()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .plan(plan)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(5)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.9, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "interactive events name the user",
                                        "not(EventDetail/Authenticate) "
                                        + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                        true))))
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), data);
    }

    private static List<String> steps(final StageRun run) {
        return run.transcript().stream().map(Exchange::step).toList();
    }

    private static List<StepOutcome> outcomes(final StageRun run) {
        return run.transcript().stream().map(Exchange::outcome).toList();
    }

    @Test
    void scenario41ACleanFeedIsLearnedDirectUnderTheEscalatingPlan() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withTargets(0)).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(0)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.stage(script).run(doc(LearningPlan.of(PlanExample.ESCALATING)),
                stream(1, CsvLines.lines(6)));

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // Direct's cost: three questions, no split and no target.
        assertThat(script.asked()).hasSize(3);
        assertThat(script.asked()).noneMatch(question -> question instanceof Split || question instanceof TargetFor);
        assertThat(steps(run)).containsExactly("chain", "parser", "first");
        assertThat(outcomes(run)).containsOnly(StepOutcome.PASSED);
    }

    @Test
    void scenario41ATargetIsAskedForOnlyWhenTheTransformStaysShort() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withTargets(0)).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(DEGENERATE))
                .expect(QuestionMatcher.configuration("XSLTFilter").withFeedbackMentioning("Extraction quality scored"))
                .reply(Scenarios.fenced(DEGENERATE))
                // The transform's two candidates are spent: 'on spent goto target' fires, a target is proposed
                // per kind (the structure answers), and the parser is asked again, now against the targets.
                .expect(QuestionMatcher.configuration("DSParser").withTargets(1)).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(1)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.stage(script).run(doc(LearningPlan.of(PlanExample.ESCALATING)),
                stream(1, CsvLines.lines(6)));

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        assertThat(script.asked()).filteredOn(TargetFor.class::isInstance).hasSize(1);
        assertThat(script.asked()).noneMatch(Split.class::isInstance);
        assertThat(steps(run)).containsExactly("chain", "parser", "first", "first", "target", "again", "transform");
        assertThat(outcomes(run)).containsExactly(StepOutcome.PASSED, StepOutcome.PASSED, StepOutcome.QUALITY_SHORT,
                StepOutcome.QUALITY_SHORT, StepOutcome.PASSED, StepOutcome.PASSED, StepOutcome.PASSED);
        assertThat(run.transcript().get(3).candidate()).isEqualTo(2);
    }

    /**
     * Target-first, with preservation not judged at the parser so that the transform meets the gap: the
     * transform's checks are fidelity alone, so the stream-level scorers do not fail it first on the fields
     * it cannot find.
     */
    private static LearningPlan preservationAtTheTransform() {
        return LearningPlan.of(PlanExample.TARGET_FIRST).withSteps(List.of(
                PlanStep.parse("CHAIN"),
                PlanStep.parse("SPLIT"),
                PlanStep.parse("TARGET kinds 3"),
                PlanStep.parse("CONFIGURE parser checks coverage,yield"),
                PlanStep.parse("CONFIGURE transform checks fidelity on preservation-short goto parser")));
    }

    @Test
    void scenario42AShortfallAtTheTransformSendsTheParserBack() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback()).reply(Scenarios.fenced(TWO_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback()).reply(Scenarios.fenced(XSLT))
                .expect(QuestionMatcher.configuration("DSParser")
                        .withFeedbackMentioning("the records carry no value for [logon, office]"))
                .reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.stage(script).run(doc(preservationAtTheTransform()),
                stream(1, CsvLines.lines(6)));

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // The parser passed its own checks; the transform's fidelity check found the value absent from its
        // input and said so as the parser's shortfall, which the plan routed at once.
        final List<Question> scripted = script.scripted();
        assertThat(scripted).hasSize(5);
        final List<Exchange> configured = run.transcript().stream()
                .filter(turn -> turn.question() instanceof Question.Configuration)
                .toList();
        assertThat(configured).extracting(Exchange::step)
                .containsExactly("parser", "transform", "parser", "transform");
        assertThat(configured).extracting(Exchange::outcome).containsExactly(StepOutcome.PASSED,
                StepOutcome.PRESERVATION_SHORT, StepOutcome.PASSED, StepOutcome.PASSED);
    }

    @Test
    void scenario42ATransitionIsTakenOnce() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(TWO_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT))
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(TWO_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.stage(script).run(doc(preservationAtTheTransform()),
                stream(1, CsvLines.lines(6)));

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(GivenUp.class);
        assertThat(((GivenUp) run.decision()).reason())
                .contains("'on preservation-short goto parser' from step 'transform' a second time");
    }

    @Test
    void feedbackCarriedByATransitionReachesTheNextQuestionAskedWhenTheStepItNamesIsSkipped() {
        // 'goto split' over raw text where the split step is guarded 'when xml': the step is skipped, and the
        // preservation shortfall the jump carried reaches the parser, which is what is asked next. The targets
        // are asked before the split so that the parser is the first question after it.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback()).reply(Scenarios.fenced(TWO_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback()).reply(Scenarios.fenced(XSLT))
                .expect(QuestionMatcher.configuration("DSParser")
                        .withFeedbackMentioning("the records carry no value for [logon, office]"))
                .reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun run = scenarios.stage(script).run(
                doc(LearningPlan.of(PlanExample.TARGET_FIRST).withSteps(List.of(
                PlanStep.parse("CHAIN"),
                PlanStep.parse("TARGET kinds 3"),
                PlanStep.parse("SPLIT when xml"),
                PlanStep.parse("CONFIGURE parser checks coverage,yield"),
                PlanStep.parse("CONFIGURE transform checks fidelity on preservation-short goto split")))),
                stream(1, CsvLines.lines(6)));
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(script.asked()).noneMatch(Split.class::isInstance);
    }

    @Test
    void onPassedGoesWhereTheStepSaysOnceTheWholeStepHasPassed() {
        // A CONFIGURE over the whole chain passes when every element has, not at its first; a pass may also
        // abandon, where the plan says so.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun run = scenarios.stage(script).run(doc(LearningPlan.of(PlanExample.DIRECT).withSteps(List.of(
                PlanStep.parse("CHAIN"), PlanStep.parse("CONFIGURE on passed goto end"),
                PlanStep.parse("never: CONFIGURE transform")))), stream(1, CsvLines.lines(6)));
        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        assertThat(steps(run)).containsExactly("chain", "configure", "configure");

        final Script refused = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS));
        final StageRun abandoned = scenarios.stage(refused).run(doc(LearningPlan.of(PlanExample.DIRECT).withSteps(
                List.of(PlanStep.parse("CHAIN"), PlanStep.parse("CONFIGURE parser on passed abandon"),
                        PlanStep.parse("CONFIGURE transform")))), stream(2, CsvLines.lines(6)));
        refused.verifyExhausted();
        assertThat(abandoned.decision()).isInstanceOf(GivenUp.class);
        assertThat(((GivenUp) abandoned.decision()).reason()).contains("on a pass of step 'parser'");
    }
}
