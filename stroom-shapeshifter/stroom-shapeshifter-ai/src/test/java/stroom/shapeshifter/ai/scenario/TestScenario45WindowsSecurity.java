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
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PlanExample;
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

/// Design 02 §5, scenario 45 (design 03 §3): the Windows Security event log as XML. The split names the element
/// that is one record — EventData, holding most of an event but not the System block, is refused on wholeness
/// — and the degeneracy trap arrives in the wild: a transform that copies every named Data to a Data under
/// Unknown validates and is refused on extraction quality, before the one that maps the EventID to a typed
/// branch is promoted, three kinds of event among its records — which structure alone cannot tell apart.
class TestScenario45WindowsSecurity {

    private static final String TYPED = Scenarios.resource("windows-security.xsl");
    private static final String DEGENERATE = Scenarios.resource("windows-security-degenerate.xsl");
    private static final String LOG = Scenarios.resource("windows-security.xml");
    private static final String EVENTS = Scenarios.resource("windows-security.events.xml");

    private static ShapeshifterAiDoc doc() {
        return ShapeshifterAiDoc.builder()
                .uuid("doc-1")
                .name("windows-security")
                .learningMode(LearningMode.AUTOMATIC)
                .plan(PlanExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(5)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "logons name the user",
                                        "not(EventDetail/Authenticate) "
                                        + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                        true))))
                .build();
    }

    @Test
    void scenario45NamedDataBecomeTypedFields() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.xmlScript("Event", TYPED)
                .expect(QuestionMatcher.chain()).reply("XSLTFilter")
                // Most of an event, but not its System block: refused on wholeness.
                .expect(QuestionMatcher.split().withoutFeedback()).reply("EventData")
                .expect(QuestionMatcher.split().withFeedbackMentioning("of the document's")).reply("Event")
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(DEGENERATE))
                .expect(QuestionMatcher.configuration("XSLTFilter")
                        .withFeedbackMentioning("Extraction quality scored")
                        .withFeedbackMentioning("Typed-element ratio")
                        .withFeedbackMentioning("records name Unknown in EventDetail"))
                .reply(Scenarios.fenced(TYPED));

        final StageRun run = scenarios.stage(script).run(doc(),
                new Input(1, "WINDOWS-SECURITY", "Raw Events", Map.of("Format", "XML"), LOG));

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(run.output()).isEqualTo(EVENTS);
        // The records are the root's children, so the stream's count is the events': promoted outright.
        assertThat(((Promoted) run.decision()).score()).isEqualTo(1.0);
        final List<Exchange> turns = run.transcript();
        assertThat(turns.get(1).question()).isInstanceOf(Split.class);
        assertThat(turns.get(1).outcome()).isEqualTo(StepOutcome.WHOLENESS_SHORT);
        // Kinds of record are told apart by structure, and a logon, a process and a logoff are one structure —
        // a System block and named Data — so one target stands for all three (design 03 §5); the transform is
        // still judged over the whole stream, and its output carries all three TypeIds.
        final List<TargetFor> targets = script.asked().stream()
                .filter(TargetFor.class::isInstance).map(TargetFor.class::cast).toList();
        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).record()).startsWith("<Event ").contains("<EventData>");
        assertThat(run.output()).contains("<TypeId>4624</TypeId>", "<TypeId>4634</TypeId>", "<TypeId>4688</TypeId>");
        // The degenerate transform validated; only extraction quality refused it.
        final Exchange degenerate = turns.stream()
                .filter(turn -> turn.question() instanceof Configuration).findFirst().orElseThrow();
        assertThat(degenerate.outcome()).isEqualTo(StepOutcome.QUALITY_SHORT);
        final Configuration reAsk = (Configuration) script.scripted().get(script.scripted().size() - 1);
        assertThat(reAsk.feedback()).extracting(error -> error.getMessage())
                .noneMatch(message -> message.startsWith("Schema conformance scored"));
        assertThat(reAsk.split().element()).isEqualTo("Event");
    }
}
