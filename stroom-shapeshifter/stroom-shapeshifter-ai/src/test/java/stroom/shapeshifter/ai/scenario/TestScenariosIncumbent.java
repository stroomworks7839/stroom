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

import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 02 §5, scenarios 8, 9 and 10: a bound shape relearned against an incumbent. A candidate that
 * looks better on the learning sample but is worse on the held-out stream is not promoted (A15); one
 * that is worse on a record the rule was accepted on is not promoted either (A18); one that is better on
 * both replaces the incumbent's fragment under the same rule; and a pinned rule is never relearned.
 * <p>
 * The incumbent is the golden transform with an empty device name, which the document requires, so it
 * scores well but not perfectly and the relearn threshold, set above its score, marks the shape on the
 * first stream it serves.
 */
class TestScenariosIncumbent {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DEVICE_NAME =
            "          <Name><xsl:value-of select=\"data[@name='where']/@value\"/></Name>";
    private static final String SOURCE_USER = String.join("\n",
            "        <User>",
            "          <Id><xsl:value-of select=\"data[@name='who']/@value\"/></Id>",
            "        </User>",
            "      </EventSource>");
    private static final String AUTHENTICATE_USER = String.join("\n",
            "          <User>",
            "            <Id><xsl:value-of select=\"data[@name='who']/@value\"/></Id>",
            "          </User>",
            "        </Authenticate>");
    /**
     * The golden transform with an empty device name: schema-valid — the 3.0.0 schema wants a Device — but
     * short of a field the document requires.
     */
    private static final String V1 = CsvLines.replacing(XSLT, DEVICE_NAME, "          <Name/>");
    private static final String DOC = "doc-1";
    private static final String SHAPE = "Feed=DOOR-ACCESS|Type=Raw Events";

    private static ShapeshifterAiDoc doc() {
        return ShapeshifterAiDoc.builder()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .plan(PlanExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(5)
                // Below what the flawed candidates score, so that the comparison with the incumbent, not the
                // floor, is what decides.
                .promotionFloor(0.85)
                .relearnThreshold(0.95)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.9, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false,
                                        List.of("EventSource/User/Id", "EventSource/Device/Name"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 0.5, false,
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

    private static Script learning(final Scenarios scenarios, final String xslt) {
        return scenarios.script(FOUR_FIELDS, xslt)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(xslt));
    }

    /**
     * A transform that names the user — at the source and on the logon — only for records the predicate
     * admits: right on the records it was shown, wrong on the ones it was not.
     */
    private static String userOnlyWhen(final String stylesheet, final String predicate) {
        final String source = CsvLines.replacing(stylesheet, SOURCE_USER, String.join("\n",
                "        <xsl:if test=\"" + predicate + "\">",
                "        <User>",
                "          <Id><xsl:value-of select=\"data[@name='who']/@value\"/></Id>",
                "        </User>",
                "        </xsl:if>",
                "      </EventSource>"));
        return CsvLines.replacing(source, AUTHENTICATE_USER, String.join("\n",
                "          <User>",
                "            <Id><xsl:if test=\"" + predicate + "\">"
                + "<xsl:value-of select=\"data[@name='who']/@value\"/></xsl:if></Id>",
                "          </User>",
                "        </Authenticate>"));
    }

    /**
     * Learns v1, serves one stream with it — which marks the shape, v1 scoring below the relearn threshold
     * — and returns the document as it then stands.
     */
    private static StageRun incumbentMarked(final Scenarios scenarios, final String users) {
        final Script script = learning(scenarios, V1);
        final StageRun learned = scenarios.stage(script).run(doc(), stream(1, CsvLines.lines(7, users, 0)));
        script.verifyExhausted();
        assertThat(learned.decision()).isInstanceOf(Promoted.class);
        final double v1Score = ((Promoted) learned.decision()).score();
        assertThat(v1Score).isBetween(0.9, 0.95);

        final Script silent = Script.of();
        final StageRun served = scenarios.stage(silent).run(learned.doc(), stream(2, CsvLines.lines(7, users, 0)));
        assertThat(served.decision()).isInstanceOf(Bound.class);
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.shapes.relearnReason(DOC, SHAPE)).isPresent();
        return served;
    }

    @Test
    void scenario8ACandidateBetterOnTheSampleButWorseOnTheStreamIsNotPromoted() {
        final Scenarios scenarios = new Scenarios();
        final StageRun marked = incumbentMarked(scenarios, "user");
        final RoutingRule v1 = scenarios.rules.forDocument(DOC).get(0);

        // v2 names the user for everyone but user6 — the one record in the held-out fifth of a seven-line
        // stream — and, like v1, has no device: it passes every threshold on the six records it is shown
        // and scores below v1 over the seven.
        final String v2 = userOnlyWhen(V1, "not(data[@name='who']/@value = 'user6')");
        final Script relearn = learning(scenarios, v2);
        final StageRun run = scenarios.stage(relearn).run(marked.doc(), stream(3, CsvLines.lines(7, "user", 0)));
        relearn.verifyExhausted();

        assertThat(run.decision()).isInstanceOf(Kept.class);
        final Kept kept = (Kept) run.decision();
        assertThat(kept.incumbent()).isEqualTo(v1);
        assertThat(kept.reason()).contains("against the incumbent's").contains("on this stream");
        assertThat(scenarios.rules.forDocument(DOC)).containsExactly(v1);
        assertThat(scenarios.stores.pipelines.list()).describedAs("v2's documents were not written").hasSize(1);
        assertThat(scenarios.stores.xslts.list()).hasSize(1);
        assertThat(run.transcript()).describedAs("chain, split, target, parser, transform").hasSize(5);
        assertThat(run.bindings().fragment()).describedAs("the incumbent served").isEqualTo(v1.getPipeline());
    }

    @Test
    void scenario8ACandidateWorseOnAnAcceptedRecordIsNotPromoted() {
        // A18: v1 was accepted on a stream of users; the marking stream and the relearning stream are of
        // staff. v2 names the user only for staff, so it beats v1 on the stream it is judged on and would
        // have been promoted on A15 alone — and scores far below v1's recorded score on the accepted stream.
        final Scenarios scenarios = new Scenarios();
        final StageRun marked = incumbentMarked(scenarios, "user");
        final RoutingRule v1 = scenarios.rules.forDocument(DOC).get(0);
        assertThat(scenarios.regressionSet.accepted(v1.getUuid())).hasSize(1);

        final String v2 = userOnlyWhen(XSLT, "starts-with(data[@name='who']/@value, 'staff')");
        final Script relearn = learning(scenarios, v2);
        final StageRun run = scenarios.stage(relearn).run(marked.doc(), stream(3, CsvLines.lines(7, "staff", 0)));
        relearn.verifyExhausted();

        assertThat(run.decision()).isInstanceOf(Kept.class);
        assertThat(((Kept) run.decision()).reason()).contains("accepted on an earlier stream");
        assertThat(scenarios.rules.forDocument(DOC)).containsExactly(v1);
        assertThat(scenarios.stores.xslts.list()).hasSize(1);
    }

    @Test
    void scenario9ABetterCandidateReplacesTheIncumbent() {
        final Scenarios scenarios = new Scenarios();
        final StageRun marked = incumbentMarked(scenarios, "user");
        final RoutingRule v1 = scenarios.rules.forDocument(DOC).get(0);

        final Script relearn = learning(scenarios, XSLT);
        final StageRun run = scenarios.stage(relearn).run(marked.doc(), stream(3, CsvLines.lines(7, "user", 0)));
        relearn.verifyExhausted();

        assertThat(run.decision()).isInstanceOf(Rebound.class);
        final Rebound rebound = (Rebound) run.decision();
        assertThat(rebound.incumbent()).isEqualTo(v1);
        assertThat(rebound.score()).isEqualTo(1.0);
        final RoutingRule v2 = rebound.rule();
        assertThat(v2.getUuid()).isEqualTo(v1.getUuid());
        assertThat(v2.getPipeline()).isNotEqualTo(v1.getPipeline());
        assertThat(v2.getScore()).isEqualTo(1.0);
        assertThat(v2.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(scenarios.rules.forDocument(DOC)).containsExactly(v2);
        // v1's documents are untouched: new siblings, never edits (design 01 §7.3 rule 1).
        assertThat(scenarios.stores.pipelines.list()).hasSize(2);
        assertThat(scenarios.stores.xslts.list()).hasSize(2);
        assertThat(scenarios.stores.pipelines.readDocument(v1.getPipeline())).isNotNull();
        assertThat(scenarios.regressionSet.accepted(v1.getUuid())).hasSize(2);
    }

    @Test
    void scenario10APinnedRuleIsNeverRelearned() {
        final Scenarios scenarios = new Scenarios();
        final Script script = learning(scenarios, V1);
        final StageRun learned = scenarios.stage(script).run(doc(), stream(1, CsvLines.lines(7)));
        final RoutingRule pinned = ((Promoted) learned.decision()).rule().copy().pinned(true).build();
        scenarios.rules.replace(DOC, pinned);

        final Script silent = Script.of();
        for (long id = 2; id <= 4; id++) {
            final StageRun run = scenarios.stage(silent).run(learned.doc(), stream(id, CsvLines.lines(7)));
            assertThat(run.decision()).isInstanceOf(Bound.class);
            assertThat(scenarios.rules.forDocument(DOC)).containsExactly(pinned);
        }
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.shapes.relearnReason(DOC, SHAPE)).describedAs("never even scored against").isEmpty();
        assertThat(scenarios.stores.pipelines.list()).hasSize(1);
    }
}
