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

import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 02 §5, scenarios 4–7: the scorers that judge meaning, and what their feedback steers. Coverage
 * points at the lines a splitter quietly dropped; schema conformance passes the degenerate transform of
 * design 01 §8.3 and extraction quality refuses it (A16); a business rule names itself; and a candidate
 * that never clears its threshold is abandoned at the candidate limit, while one that clears every
 * threshold but not the floor is given up too.
 */
class TestScenariosScoring {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String EVEN_USERS_ONLY = Scenarios.resource("csv-fields-even-users.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DEGENERATE = Scenarios.resource("csv-degenerate.xsl");
    private static final String DOC = "doc-1";
    private static final String SHAPE = "Feed=DOOR-ACCESS|Type=Raw Events";
    private static final String AUTHENTICATE_USER_ID = String.join("\n",
            "            <Id><xsl:value-of select=\"data[@name='who']/@value\"/></Id>",
            "          </User>",
            "        </Authenticate>");
    private static final XPathAssertion NAMES_THE_USER = new XPathAssertion(
            "interactive events name the user",
            "not(EventDetail/Authenticate) or EventDetail/Authenticate/User/Id[normalize-space(.) != '']");

    /**
     * The full scorer set of design 01 §8.4 as built: compile, coverage and yield from the first slice;
     * schema conformance as a gate, extraction quality as a gate requiring the user, and the business rule.
     */
    private static ShapeshifterAiDoc doc() {
        return ShapeshifterAiDoc.builder()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
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
                                new BusinessRulesParameters(List.of(NAMES_THE_USER), true))))
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), data);
    }

    private static List<String> feedback(final Question question) {
        return question.feedback().stream().map(StoredError::getMessage).toList();
    }

    @Test
    void scenario4DiscardedInputIsPointedAtThenCoverageIsFixed() {
        final Scenarios scenarios = new Scenarios();
        final Script script = Script.of()
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback())
                .reply(Scenarios.fenced(EVEN_USERS_ONLY))
                .expect(QuestionMatcher.configuration("DSParser")
                        // Over the learning prefix — five of the six lines — not the whole stream.
                        .withFeedbackMentioning("Input coverage scored 0.6 against a threshold of 0.9")
                        .withFeedbackMentioning("The split consumed 3 of 5 lines")
                        .withFeedbackMentioning("Lines not consumed: 2, 4")
                        .withPreviousConfiguration(EVEN_USERS_ONLY))
                .reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.stage(script).run(doc(), stream(1, CsvLines.lines(6)));

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        assertThat(script.asked()).hasSize(4);
        // The second splitter consumed everything; the split's verdict says so and the whole stream scores full.
        assertThat(run.verdicts().get(0).passed()).isTrue();
        assertThat(((Promoted) run.decision()).score()).isEqualTo(1.0);
    }

    @Test
    void scenario5TheDegenerateTransformValidatesAndIsRefused() {
        final Scenarios scenarios = new Scenarios();
        final Script script = Script.of()
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(DEGENERATE))
                .expect(QuestionMatcher.configuration("XSLTFilter")
                        .withFeedbackMentioning("Extraction quality scored")
                        .withFeedbackMentioning("(a gate)")
                        .withFeedbackMentioning("Typed-element ratio")
                        .withFeedbackMentioning("records name Unknown in EventDetail")
                        .withFeedbackMentioning("Required field EventSource/User/Id is present in 0 of 5 records"))
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.stage(script).run(doc(), stream(1, CsvLines.lines(6)));

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // The degenerate candidate passed the schema gate: nothing in its feedback came from conformance.
        final Question reAsk = script.asked().get(3);
        assertThat(feedback(reAsk)).noneMatch(message -> message.startsWith("Schema conformance scored"));
        assertThat(feedback(reAsk)).noneMatch(message -> message.startsWith("Record "));
        // The promoted transform scores full on every judge of meaning over the whole stream.
        assertThat(run.verdicts().get(1).judgements())
                .extracting(judgement -> judgement.setting().getType())
                .containsExactly(ScorerType.COMPILE, ScorerType.YIELD, ScorerType.SCHEMA_CONFORMANCE,
                        ScorerType.EXTRACTION_QUALITY, ScorerType.BUSINESS_RULES);
        assertThat(run.verdicts().get(1).judgements())
                .extracting(judgement -> judgement.score().value())
                .containsOnly(1.0);
    }

    @Test
    void scenario6ABrokenBusinessRuleNamesItself() {
        final Scenarios scenarios = new Scenarios();
        // Schema-valid — the 3.0.0 schema wants a User under Authenticate — but the user it names is empty.
        final String noUserOnLogon = CsvLines.replacing(XSLT, AUTHENTICATE_USER_ID,
                "            <Id/>\n          </User>\n        </Authenticate>");
        final Script script = Script.of()
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(noUserOnLogon))
                .expect(QuestionMatcher.configuration("XSLTFilter")
                        .withFeedbackMentioning("Business rules scored 0.0 against a threshold of 1.0")
                        .withFeedbackMentioning("Rule 'interactive events name the user'")
                        .withFeedbackMentioning("fails for 5 of 5 records"))
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.stage(script).run(doc(), stream(1, CsvLines.lines(6)));

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // The flawed transform still validated and still extracted the user at the source: only the rule spoke.
        assertThat(feedback(script.asked().get(3))).noneMatch(message -> message.startsWith("Schema conformance"));
        assertThat(feedback(script.asked().get(3))).noneMatch(message -> message.startsWith("Extraction quality"));
    }

    @Test
    void scenario7ACandidateThatNeverClearsItsThresholdIsAbandonedAtTheLimit() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc().copy()
                .promotionFloor(0.95)
                .scorers(doc().getScorers().stream()
                        .map(setting -> setting.getType() == ScorerType.YIELD
                                ? new ScorerSetting(ScorerType.YIELD, 1.0, 0.75, false,
                                        new YieldParameters(1.0, YieldBasis.RECORDS))
                                : setting)
                        .toList())
                .build();
        final String everyOther = CsvLines.replacing(XSLT, "  <xsl:template match=\"record\">",
                "  <xsl:template match=\"record[position() mod 2 = 0]\"/>\n\n  <xsl:template match=\"record\">");
        final Script script = Script.of()
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(everyOther))
                .expect(QuestionMatcher.configuration("XSLTFilter").withFeedbackMentioning("Yield scored"))
                .reply(Scenarios.fenced(everyOther))
                .expect(QuestionMatcher.configuration("XSLTFilter").withFeedbackMentioning("Yield scored"))
                .reply(Scenarios.fenced(everyOther));

        final StageRun run = scenarios.stage(script).run(doc, stream(1, CsvLines.lines(6)));

        script.verifyExhausted();
        assertThat(script.asked()).hasSize(5);
        assertThat(run.decision()).isInstanceOf(GivenUp.class);
        assertThat(((GivenUp) run.decision()).reason()).contains("No passing configuration for XSLTFilter after 3");
        assertThat(feedback(script.asked().get(4)))
                .anyMatch(message -> message.contains("against an expected 1.0"));
        assertThat(run.doc().getRoutingTable()).isEmpty();
        assertThat(scenarios.stores.pipelines.list()).isEmpty();
        assertThat(scenarios.shapes.reasonGivenUp(DOC, SHAPE)).isPresent();
        assertThat(scenarios.ledger.rows()).extracting(row -> row.inputId()).containsExactly(1L);

        // And the shape is not asked about again (scenario 11).
        final Script silent = Script.of();
        final StageRun again = scenarios.stage(silent).run(run.doc(), stream(2, CsvLines.lines(6)));
        assertThat(again.decision()).isInstanceOf(stroom.shapeshifter.ai.stage.Decision.Sentinel.class);
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.ledger.rows()).hasSize(2);
    }

    @Test
    void scenario7ACandidateThatClearsEveryThresholdButNotTheFloorIsGivenUp() {
        // Yield at its default threshold of 0.5 lets a transform that drops every other record through the
        // dialogue; the floor of 0.95 over the whole stream does not.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc().copy().promotionFloor(0.95).build();
        final String everyOther = CsvLines.replacing(XSLT, "  <xsl:template match=\"record\">",
                "  <xsl:template match=\"record[position() mod 2 = 0]\"/>\n\n  <xsl:template match=\"record\">");
        final Script script = Script.of()
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(everyOther));

        final StageRun run = scenarios.stage(script).run(doc, stream(1, CsvLines.lines(6)));

        script.verifyExhausted();
        assertThat(script.asked()).hasSize(3);
        assertThat(run.decision()).isInstanceOf(GivenUp.class);
        assertThat(((GivenUp) run.decision()).reason()).isEqualTo("Below the promotion floor");
        assertThat(scenarios.shapes.reasonGivenUp(DOC, SHAPE).orElseThrow()).contains("against a floor of 0.95");
        assertThat(run.doc().getRoutingTable()).isEmpty();
        assertThat(scenarios.stores.pipelines.list()).describedAs("nothing written below the floor").isEmpty();
    }
}
