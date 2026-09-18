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

package stroom.shapeshifter.ai.scenario;

import stroom.meta.shared.MetaFields;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.scoring.Judgement;
import stroom.shapeshifter.ai.stage.Bindings;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.RoutingFields;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 02 §5, scenario 1: a CSV feed with nothing in the routing table. The stage learns a
 * splitter and a transform in one dialogue, judges them over the whole stream, promotes, and the
 * routing table gains the learned rule.
 * <p>
 * The corpus case has six records under a header. The coverage threshold sits at 0.8 because the
 * golden splitter reads the header into a variable, which coverage counts as discarded (design 01
 * §9.1 found exactly this), and the minimum records per shape is lowered to fit the case.
 */
class TestScenario01LearnsACsvFeed {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String EXPECTED_EVENTS = Scenarios.resource("csv-logon.events.xml");

    private static ShapeshifterAiDoc policy() {
        return ShapeshifterAiDoc.builder()
                .uuid("policy-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(5)
                // The golden splitter reads the header line into a variable, which coverage counts as
                // discarded — design 01 §9.1's finding — so the split scores 6/7 and the floor sits below it.
                .promotionFloor(0.85)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input input() {
        return new Input(
                1L, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV", "System", "Door Access"), CSV.input());
    }

    @Test
    void learnsPromotesAndRoutes() {
        final Scenarios scenarios = new Scenarios();
        final Script script = Script.of()
                .expect(QuestionMatcher.chain()
                        .allowing("DSParser", "XSLTFilter")
                        .withKey("Feed", "DOOR-ACCESS")
                        .withKey("Type", "Raw Events")
                        .withoutFeedback())
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback())
                .reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.stage(script).run(policy(), input());

        script.verifyExhausted();
        // The transform question carried the parser's real output over the learning prefix (A21 step 2).
        final Configuration transform = (Configuration) script.asked().get(2);
        assertThat(transform.input()).startsWith("<?xml").contains("<records").contains("<record>");
        assertThat(((Chain) script.asked().get(0)).sample().text())
                .describedAs("the model learns from a prefix, header + 5 of 6 records; the whole stream is held out")
                .hasLineCount(6);

        assertThat(run.decision()).isInstanceOf(Promoted.class);
        final Promoted promoted = (Promoted) run.decision();
        assertThat(run.output()).isEqualTo(EXPECTED_EVENTS);
        assertThat(run.transcript()).hasSize(3);
        assertThat(scenarios.shapes.reasonGivenUp(run.doc().getUuid(), run.shape().id())).isEmpty();
        assertThat(scenarios.ledger.isEmpty()).isTrue();
        assertThat(run.bindings())
                .describedAs("design 01 §7.3 rule 3: the output records what produced it")
                .isEqualTo(new Bindings(run.doc().getUuid(), promoted.rule().getUuid(),
                        promoted.rule().getPipeline(), false, promoted.score()));

        // Scored over the whole stream: coverage and yield on the split, yield on the transform.
        assertThat(run.verdicts()).hasSize(2);
        assertThat(run.verdicts().get(0).judgements()).extracting(j -> j.setting().getType())
                .containsExactly(ScorerType.COMPILE, ScorerType.INPUT_COVERAGE);
        assertThat(run.verdicts().get(1).judgements()).extracting(j -> j.setting().getType())
                .containsExactly(ScorerType.COMPILE, ScorerType.YIELD);
        assertThat(run.verdicts()).allMatch(verdict -> verdict.passed() && verdict.gatesPassed());
        assertThat(run.verdicts().get(1).judgements()).extracting(Judgement::score)
                .extracting(score -> score.value())
                .containsExactly(1.0, 1.0);
        assertThat(promoted.score()).isGreaterThanOrEqualTo(policy().getPromotionFloor());

        // The routing table gained exactly the learned rule (A22, A29): the default learning key, Feed AND
        // Type, and nothing wider — the signature is not in the key, so it is not in the rule.
        assertThat(run.doc().getRoutingTable()).hasSize(1);
        final RoutingRule rule = run.doc().getRoutingTable().get(0);
        assertThat(rule).isEqualTo(promoted.rule());
        assertThat(rule.getExpression())
                .isEqualTo(RoutingRule.learnedSelector(RoutingFields.DEFAULT_LEARNING_KEY,
                        Map.of(MetaFields.FIELD_FEED, "DOOR-ACCESS", MetaFields.FIELD_TYPE, "Raw Events")));
        assertThat(rule.getUuid()).describedAs("A26: a learned rule is named stably").isNotNull();
        assertThat(rule.isDraft()).isFalse();
        assertThat(rule.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(rule.isPinned()).isFalse();

        // The fragment is real content: a Source → DSParser → XSLTFilter pipeline over two new documents.
        assertThat(rule.getPipeline().getName()).startsWith("DOOR-ACCESS-Raw-Events-");
        assertThat(run.shape().id()).isEqualTo("Feed=DOOR-ACCESS|Type=Raw Events");
        assertThat(scenarios.stores.pipelines.readDocument(rule.getPipeline()).getPipelineData()
                .getAddedElements())
                .extracting(element -> element.getType())
                .containsExactly("Source", "DSParser", "XSLTFilter");
        assertThat(scenarios.stores.textConverters.list()).hasSize(1);
        assertThat(scenarios.stores.xslts.list()).hasSize(1);
        assertThat(scenarios.regressionSet.accepted(rule.getUuid()))
                .describedAs("A18: the regression set is per rule")
                .hasSize(1);
    }

    @Test
    void aStreamLackingAKeyFieldIsSentinelledBeforeAnyQuestionOrDocument() {
        // Design 01 §3: a stream with no value for a key field has no shape under that key. It is refused
        // up front — no model call, nothing written — rather than bound wider than the key.
        final Scenarios scenarios = new Scenarios();
        final Script silent = Script.of();
        final ShapeshifterAiDoc keyedOnSystem = policy().copy()
                .learningKey(List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE, RoutingFields.SYSTEM))
                .build();
        final Input noSystemHeader = new Input(1L, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"),
                CSV.input());

        final StageRun run = scenarios.stage(silent).run(keyedOnSystem, noSystemHeader);

        assertThat(run.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) run.decision()).reason()).contains(RoutingFields.SYSTEM);
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.stores.pipelines.list()).isEmpty();
        assertThat(run.doc().getRoutingTable()).isEmpty();
    }

    @Test
    void theSecondStreamOfTheShapeIsBoundWithoutLearning() {
        final Scenarios scenarios = new Scenarios();
        final Script script = Script.of()
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun first = scenarios.stage(script).run(policy(), input());
        script.verifyExhausted();

        // Same shape again, this time with a script that must not be consulted.
        final Script silent = Script.of();
        final StageRun second = scenarios.stage(silent).run(first.doc(), input());

        assertThat(second.decision()).isInstanceOf(Bound.class);
        assertThat(second.transcript()).isEmpty();
        assertThat(silent.asked()).isEmpty();
        assertThat(second.output())
                .describedAs("the written fragment, read back and run, produces the same translation")
                .isEqualTo(EXPECTED_EVENTS);
        assertThat(second.doc()).isEqualTo(first.doc());
    }
}
