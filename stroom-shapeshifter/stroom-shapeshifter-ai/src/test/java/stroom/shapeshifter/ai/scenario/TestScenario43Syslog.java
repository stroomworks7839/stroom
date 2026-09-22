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

import stroom.meta.shared.MetaFields;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 43 (design 03 §3): syslog in both forms — RFC 3164 and RFC 5424 — from two senders on
/// one feed. Under the default key that is one shape, so one variant must take both forms, and a splitter that
/// handles one is sent back naming the lines it dropped; with the signature in the key the two forms are two
/// shapes, each learned from its own stream and bound by its own rule.
class TestScenario43Syslog {

    private static final String BOTH_FORMS = Scenarios.resource("syslog.ds3.xml");
    private static final String BSD_ONLY = Scenarios.resource("syslog-3164-only.ds3.xml");
    private static final String XSLT = Scenarios.resource("syslog.xsl");
    private static final String LOG = Scenarios.resource("syslog.log");
    private static final String EVENTS = Scenarios.resource("syslog.events.xml");
    private static final String FEED = "GATEWAY-SSH";

    private static ShapeshifterAiDoc doc(final List<String> learningKey) {
        final ShapeshifterAiDoc.Builder builder = Scenarios.document()
                .uuid("doc-1")
                .name("gateway-ssh")
                .learningMode(LearningMode.AUTOMATIC)
                .plan(PlanExample.TARGET_FIRST)
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
                                new ExtractionQualityParameters(false,
                                        List.of("EventSource/User/Id", "EventSource/Client/IPAddress"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "logons name the user",
                                        "EventDetail/Authenticate/User/Id[normalize-space(.) != '']")), true))));
        if (learningKey != null) {
            builder.learningKey(learningKey);
        }
        return builder.build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "syslog"), data);
    }

    /**
     * The lines of one form: RFC 5424 lines begin with the priority and a version of 1.
     */
    private static String form(final boolean rfc5424) {
        return LOG.lines()
                .filter(line -> line.matches("^<\\d+>1 .*") == rfc5424)
                .collect(Collectors.joining("\n")) + "\n";
    }

    @Test
    void scenario43OneShapeUnderTheDefaultKeyMustTakeBothForms() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(BOTH_FORMS, XSLT)
                .expect(QuestionMatcher.chain().withKey("Feed", FEED)).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback()).reply(Scenarios.fenced(BSD_ONLY))
                .expect(QuestionMatcher.configuration("DSParser")
                        .withFeedbackMentioning("Input coverage scored")
                        .withFeedbackMentioning("Lines not consumed: 2, 4, 6")
                        .withPreviousConfiguration(BSD_ONLY))
                .reply(Scenarios.fenced(BOTH_FORMS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.stage(script).run(doc(null), stream(1, LOG));

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(run.output()).isEqualTo(EVENTS);
        // One shape — feed and type — so one rule, and one variant that handles both forms.
        assertThat(run.shape().id()).isEqualTo("Feed=" + FEED + "|Type=Raw Events");
        assertThat(scenarios.rules.forDocument("doc-1")).hasSize(1);
        // The two forms are two kinds of record, so two targets were proposed and both configurations carried them.
        assertThat(script.asked()).filteredOn(TargetFor.class::isInstance).hasSize(2);
        assertThat(script.scripted()).hasSize(4);
    }

    @Test
    void scenario43WithTheSignatureInTheKeyEachFormIsItsOwnShape() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc(List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE,
                RoutingRule.SHAPE_SIGNATURE_FIELD));
        final Script bsd = scenarios.script(BSD_ONLY, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(BSD_ONLY))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun first = scenarios.stage(bsd).run(doc, stream(1, form(false)));
        assertThat(first.decision()).describedAs(first.decision().toString()).isInstanceOf(Promoted.class);
        bsd.verifyExhausted();

        // The other sender's form: the bound variant is tried first (design 01 §6) and drops every line, so
        // the shape is learned on its own, from its own representative, and bound by a second rule.
        final Script rfc5424 = scenarios.script(BOTH_FORMS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(BOTH_FORMS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun second = scenarios.stage(rfc5424).run(first.doc(), stream(2, form(true)));
        rfc5424.verifyExhausted();
        assertThat(second.decision()).isInstanceOf(Promoted.class);
        assertThat(second.shape().id()).isNotEqualTo(first.shape().id());
        assertThat(scenarios.rules.forDocument("doc-1")).hasSize(2);
        assertThat(scenarios.rules.forDocument("doc-1")).allMatch(rule ->
                rule.getExpression().toString().contains(RoutingRule.SHAPE_SIGNATURE_FIELD));
        assertThat(((TargetFor) rfc5424.asked().get(2)).record()).startsWith("<86>1 ");
    }
}
