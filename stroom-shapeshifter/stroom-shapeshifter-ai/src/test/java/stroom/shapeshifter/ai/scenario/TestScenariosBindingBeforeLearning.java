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
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PlanExample;
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
 * Design 02 §5, scenarios 14, 25, 26 and 29, and the draft-rule sentinel of 22: what the stage does
 * with a stream before — or instead of — asking the model. Every scenario here runs with an empty
 * script: a question asked is the failure.
 */
class TestScenariosBindingBeforeLearning {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String EXPECTED_EVENTS = Scenarios.resource("csv-logon.events.xml");

    /**
     * Keyed on {@code Format} as well, so that the same CSV under another format label is a new shape
     * the existing splitter happens to consume.
     */
    private static ShapeshifterAiDoc keyedOnFormat() {
        return ShapeshifterAiDoc.builder()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .plan(PlanExample.TARGET_FIRST)
                .learningKey(List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE, RoutingFields.FORMAT))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(5)
                .promotionFloor(0.85)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream(final String format) {
        return new Input(1L, "DOOR-ACCESS", "Raw Events", Map.of("Format", format), CSV.input());
    }

    /**
     * Learns shape X (Format=CSV) with a scripted model, so that a rule is bound for the feed and type.
     */
    private static StageRun learnShapeX(final Scenarios scenarios, final ShapeshifterAiDoc doc) {
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun run = scenarios.stage(script).run(doc, stream("CSV"));
        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        return run;
    }

    @Test
    void scenario25AnExistingBindingFitsANewShapeWithoutAQuestion() {
        final Scenarios scenarios = new Scenarios();
        final StageRun learned = learnShapeX(scenarios, keyedOnFormat());
        final RoutingRule v1 = ((Promoted) learned.decision()).rule();

        final Script silent = Script.of();
        final StageRun run = scenarios.stage(silent).run(learned.doc(), stream("CSV-v2"));

        assertThat(silent.asked()).isEmpty();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        final RoutingRule bound = ((Promoted) run.decision()).rule();
        assertThat(bound.getPipeline())
                .describedAs("v1's fragment, not a new one: no document was written")
                .isEqualTo(v1.getPipeline());
        assertThat(bound.getUuid()).isNotEqualTo(v1.getUuid());
        assertThat(bound.getExpression().toString()).contains("Format = CSV-v2");
        assertThat(run.shape().id()).isEqualTo("Feed=DOOR-ACCESS|Type=Raw Events|Format=CSV-v2");
        assertThat(run.output()).isEqualTo(EXPECTED_EVENTS);
        assertThat(run.doc().getRoutingTable()).hasSize(2);
        assertThat(scenarios.stores.pipelines.list()).hasSize(1);
        assertThat(scenarios.regressionSet.accepted(bound.getUuid()))
                .describedAs("held out by construction: promoted, so accepted")
                .hasSize(1);
    }

    @Test
    void scenario29DisabledStillSelectsAmongBoundVariants() {
        final Scenarios scenarios = new Scenarios();
        final StageRun learned = learnShapeX(scenarios, keyedOnFormat());
        final ShapeshifterAiDoc disabled = learned.doc().copy().learningMode(LearningMode.DISABLED).build();

        final Script silent = Script.of();
        final StageRun fits = scenarios.stage(silent).run(disabled, stream("CSV-v2"));
        assertThat(fits.decision()).isInstanceOf(Promoted.class);
        assertThat(silent.asked()).isEmpty();

        // A stream nothing bound can handle is a sentinel: the model is never asked.
        final Input unlike = new Input(2L, "DOOR-ACCESS", "Raw Events", Map.of("Format", "JSON"),
                "{\"not\": \"csv\"}\n");
        final StageRun sentinel = scenarios.stage(silent).run(fits.doc(), unlike);
        assertThat(sentinel.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) sentinel.decision()).reason()).contains("disabled");
        assertThat(silent.asked()).isEmpty();
    }

    @Test
    void scenario26AReservedRuleGivesTheShapeUp() {
        final Scenarios scenarios = new Scenarios();
        final RoutingRule reserved = RoutingRule.builder()
                .uuid("reserved-1")
                .expression(ExpressionOperator.builder()
                        .addTerm(MetaFields.FIELD_FEED, Condition.EQUALS, "DOOR-ACCESS")
                        .build())
                .build();
        final ShapeshifterAiDoc doc = keyedOnFormat().copy().routingTable(List.of(reserved)).build();

        final Script silent = Script.of();
        final StageRun run = scenarios.stage(silent).run(doc, stream("CSV"));

        assertThat(run.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) run.decision()).reason()).startsWith("Reserved: rule 1");
        assertThat(silent.asked()).isEmpty();
        assertThat(run.doc().getRoutingTable()).containsExactly(reserved);
        assertThat(scenarios.stores.pipelines.list()).isEmpty();
    }

    @Test
    void scenario14TooFewRecordsBindsProvisionallyThenPromotesWhenEnoughArrive() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = keyedOnFormat().copy().minRecordsPerShape(10).build();
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        // Seven lines — six records and a header — against a minimum of ten: the candidate clears the
        // floor, so it is bound and serves this stream, but provisionally — A14 cannot yet be met.
        final StageRun first = scenarios.stage(script).run(doc, stream("CSV"));
        script.verifyExhausted();
        assertThat(first.decision()).isInstanceOf(Provisional.class);
        final Provisional provisional = (Provisional) first.decision();
        assertThat(provisional.records()).isEqualTo(7);
        assertThat(provisional.required()).isEqualTo(10);
        assertThat(provisional.rule().isProvisional()).isTrue();
        assertThat(provisional.rule().getPromotedTimeMs()).isNull();
        assertThat(first.output()).isEqualTo(EXPECTED_EVENTS);
        assertThat(scenarios.regressionSet.accepted(provisional.rule().getUuid()))
                .describedAs("nothing is accepted until promotion")
                .isEmpty();

        // The same shape again with twelve records: the provisional rule serves it and, now that the
        // shape has enough records and clears the floor, is promoted in place.
        final String[] lines = CSV.input().split("\n");
        final StringBuilder twelve = new StringBuilder(lines[0]).append('\n');
        for (int i = 0; i < 2; i++) {
            for (int line = 1; line < lines.length; line++) {
                twelve.append(lines[line]).append('\n');
            }
        }
        final Script silent = Script.of();
        final StageRun second = scenarios.stage(silent).run(first.doc(),
                new Input(2L, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), twelve.toString()));

        assertThat(silent.asked()).isEmpty();
        assertThat(second.decision()).isInstanceOf(Promoted.class);
        final RoutingRule promoted = ((Promoted) second.decision()).rule();
        assertThat(promoted.getUuid()).isEqualTo(provisional.rule().getUuid());
        assertThat(promoted.isProvisional()).isFalse();
        assertThat(promoted.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(second.doc().getRoutingTable()).containsExactly(promoted);
        assertThat(scenarios.regressionSet.accepted(promoted.getUuid())).hasSize(1);

        // And a third stream is simply bound.
        final StageRun third = scenarios.stage(silent).run(second.doc(), stream("CSV"));
        assertThat(third.decision()).isInstanceOf(Bound.class);
    }

    @Test
    void aDraftRuleIsNotBoundAndItsShapeIsSentinelled() {
        // The routing half of scenario 22 (A25): the router does not bind a draft; a stream of its shape
        // is sentinelled naming the draft, and the model is not asked again.
        final Scenarios scenarios = new Scenarios();
        final StageRun learned = learnShapeX(scenarios, keyedOnFormat());
        final RoutingRule live = ((Promoted) learned.decision()).rule();
        final RoutingRule draft = live.copy().draft(true).build();
        final ShapeshifterAiDoc doc = learned.doc().copy().routingTable(List.of(draft)).build();

        final Script silent = Script.of();
        final StageRun run = scenarios.stage(silent).run(doc, stream("CSV"));

        assertThat(run.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) run.decision()).reason())
                .startsWith("Awaiting review: draft rule " + draft.getUuid());
        assertThat(silent.asked()).isEmpty();
        assertThat(run.output()).isNull();
    }
}
