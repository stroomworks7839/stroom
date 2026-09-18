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

package stroom.shapeshifter.ai.learning;

import stroom.meta.api.StandardHeaderArguments;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Failing;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.extraction.NodeFixture;
import stroom.shapeshifter.ai.learning.Outcome.Abandoned;
import stroom.shapeshifter.ai.learning.Outcome.Learned;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scoring.CompileScorer;
import stroom.shapeshifter.ai.scoring.Scorecard;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The A21 dialogue end to end against a simulated model: a Shapeshifter AI document, a sample from the {@code TestDS3}
 * corpus, and canned replies that carry the corpus's own Data Splitter configuration and a stylesheet
 * that turns its records into {@code event-logging:3} events. What is asserted is the order and content
 * of the questions — that is the design — and the translation that comes out of the end.
 */
class TestDialogue {

    private static final NodeFixture FIXTURE = new NodeFixture();
    private static final Golden CSV = golden("001_csv_with_header");
    private static final Failing BAD_CSV = failing("008_invalid_xml_FAIL");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String EXPECTED_EVENTS = Scenarios.resource("csv-logon.events.xml");
    /**
     * The sample as the supervisor would build it: the text plus the learning key's values, with a
     * sender-set header present to show that what the key does not name is left behind (A29).
     */
    private static final Sample SAMPLE = Sample.of(CSV.input(),
            List.of(StandardHeaderArguments.FORMAT, StandardHeaderArguments.SYSTEM),
            Map.of(
                    StandardHeaderArguments.FORMAT, "CSV",
                    StandardHeaderArguments.SYSTEM, "Door Access",
                    "X-Sender-Token", "s3cret"));

    /**
     * These tests are about the dialogue; the scorecard is the compile gate alone so that a step passes
     * when it runs. Scoring has its own tests and the scenarios.
     */
    private static Dialogue dialogue(final Advisor advisor) {
        return new Dialogue(advisor, List.of(new DataSplitterStep(FIXTURE.compiler()), new XsltStep()),
                new Scorecard(List.of(new ScorerSetting(ScorerType.COMPILE, 1.0, 1.0, true, null)),
                        List.of(new CompileScorer())));
    }

    private static ShapeshifterAiDoc policy() {
        return ShapeshifterAiDoc.builder().uuid("policy-1").name("csv-logon").build();
    }

    /**
     * A clock that jumps by a set amount every time it is read, so a question can be made to take as long
     * as a test needs.
     */
    private static Clock ticking(final long stepMs) {
        return new Clock() {
            private long now;

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                now += stepMs;
                return Instant.ofEpochMilli(now);
            }
        };
    }

    @Test
    void anAttemptThatOutrunsItsWallClockBudgetIsAbandoned() {
        // A5: the budget bounds the whole dialogue. Every read of the clock moves it by a minute, so the
        // first question's check passes at one minute and the check after it fails at two.
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser -> XSLTFilter",
                CannedAdvisor.fenced(CSV.configuration()),
                CannedAdvisor.fenced(XSLT));
        final Dialogue dialogue = new Dialogue(model, List.of(new DataSplitterStep(FIXTURE.compiler()), new XsltStep()),
                new Scorecard(List.of(new ScorerSetting(ScorerType.COMPILE, 1.0, 1.0, true, null)),
                        List.of(new CompileScorer())), ticking(60_000));

        final Outcome outcome = dialogue.run(policy().copy().attemptBudgetMs(90_000).build(), SAMPLE);

        assertThat(outcome).isInstanceOf(Abandoned.class);
        assertThat(((Abandoned) outcome).reason()).contains("budget of 90000 ms is spent");
        assertThat(model.questions()).describedAs("the first question was asked; the second was not").hasSize(1);
        assertThat(outcome.transcript()).hasSize(1);
    }

    @Test
    void anAttemptThatOutrunsItsTokenBudgetIsAbandoned() {
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser -> XSLTFilter",
                CannedAdvisor.fenced(CSV.configuration()),
                CannedAdvisor.fenced(XSLT)) {
            private long tokens;

            @Override
            public String ask(final List<Exchange> transcript, final Question question) {
                tokens += 700;
                return super.ask(transcript, question);
            }

            @Override
            public long tokensUsed() {
                return tokens;
            }
        };

        final Outcome outcome = dialogue(model).run(policy().copy().tokenBudget(1000L).build(), SAMPLE);

        assertThat(outcome).isInstanceOf(Abandoned.class);
        assertThat(((Abandoned) outcome).reason()).contains("budget of 1000 tokens is spent after 1400 tokens");
        assertThat(model.questions()).hasSize(2);
    }

    @Test
    void asksShapeThenEachConfigurationInChainOrderAndTranslatesTheSample() {
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser -> XSLTFilter",
                CannedAdvisor.fenced(CSV.configuration()),
                CannedAdvisor.fenced(XSLT));

        final Outcome outcome = dialogue(model).run(policy(), SAMPLE);

        assertThat(model.questions()).hasSize(3);
        final Chain shape = (Chain) model.questions().get(0);
        assertThat(shape.allowedElements())
                .describedAs("the document allows four; only the two with runners are offered")
                .containsExactly("DSParser", "XSLTFilter");
        assertThat(shape.sample().text()).isEqualTo(CSV.input());
        assertThat(shape.sample().headers())
                .describedAs("only the learning key's values reach the model")
                .containsExactly(
                        Map.entry(StandardHeaderArguments.FORMAT, "CSV"),
                        Map.entry(StandardHeaderArguments.SYSTEM, "Door Access"));

        final Configuration parser = (Configuration) model.questions().get(1);
        assertThat(parser.elementType()).isEqualTo("DSParser");
        assertThat(parser.documentType()).isEqualTo("TextConverter");
        assertThat(parser.input())
                .describedAs("the first element is given the sample itself")
                .isEqualTo(CSV.input());
        assertThat(model.transcriptAt(1)).hasSize(1);

        final Configuration transform = (Configuration) model.questions().get(2);
        assertThat(transform.elementType()).isEqualTo("XSLTFilter");
        assertThat(transform.documentType()).isEqualTo("XSLT");
        assertThat(transform.input())
                .describedAs("the transform question carries the parser's real output, not a description")
                .isEqualTo(CSV.expectedRecords());
        assertThat(model.transcriptAt(2)).hasSize(2);

        assertThat(outcome).isInstanceOf(Learned.class);
        final Learned learned = (Learned) outcome;
        assertThat(learned.chain()).extracting(LearnedStep::elementType).containsExactly("DSParser", "XSLTFilter");
        assertThat(learned.chain().get(0).configuration()).isEqualTo(CSV.configuration());
        assertThat(learned.chain().get(1).configuration()).isEqualTo(XSLT);
        assertThat(learned.output()).isEqualTo(EXPECTED_EVENTS);
        assertThat(learned.transcript()).hasSize(3);
    }

    @Test
    void feedbackGoesToTheStepThatFailedAndTheShapeIsNotReAsked() {
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser, XSLTFilter",
                CannedAdvisor.fenced(BAD_CSV.configuration()),
                CannedAdvisor.fenced(CSV.configuration()),
                CannedAdvisor.fenced(XSLT));

        final Outcome outcome = dialogue(model).run(policy(), SAMPLE);

        assertThat(outcome).isInstanceOf(Learned.class);
        assertThat(model.questions()).hasSize(4);
        final Configuration first = (Configuration) model.questions().get(1);
        final Configuration reAsk = (Configuration) model.questions().get(2);
        assertThat(first.feedback()).isEmpty();
        assertThat(reAsk.elementType()).isEqualTo("DSParser");
        assertThat(reAsk.previousConfiguration()).isEqualTo(BAD_CSV.configuration());
        assertThat(reAsk.feedback())
                .describedAs("the compile gate's diagnostics are the feedback")
                .isNotEmpty()
                .allMatch(error -> error.getSeverity().greaterThanOrEqual(Severity.ERROR));
        assertThat(model.questions().get(3)).isInstanceOf(Configuration.class);
    }

    @Test
    void aReplyThatIsNotADocumentIsRefusedWithFeedback() {
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser, XSLTFilter",
                "I would suggest splitting on commas.",
                CannedAdvisor.fenced(CSV.configuration()),
                CannedAdvisor.fenced(XSLT));

        final Outcome outcome = dialogue(model).run(policy(), SAMPLE);

        assertThat(outcome).isInstanceOf(Learned.class);
        final Configuration reAsk = (Configuration) model.questions().get(2);
        assertThat(reAsk.previousConfiguration()).isNull();
        assertThat(reAsk.feedback()).extracting(StoredError::getMessage)
                .singleElement()
                .asString()
                .contains("not a single TextConverter document");
    }

    @Test
    void aShapeOutsideTheAllowedSetIsRefusedWithFeedback() {
        final CannedAdvisor model = new CannedAdvisor(
                "CombinedParser",
                "DSParser",
                CannedAdvisor.fenced(CSV.configuration()));

        final Outcome outcome = dialogue(model).run(policy(), SAMPLE);

        assertThat(outcome).isInstanceOf(Learned.class);
        final Chain reAsk = (Chain) model.questions().get(1);
        assertThat(reAsk.feedback()).extracting(StoredError::getMessage)
                .singleElement()
                .asString()
                .contains("allowed element types");
        assertThat(((Learned) outcome).output()).isEqualTo(CSV.expectedRecords());
    }

    @Test
    void theShapeQuestionIsSkippedWhenOneElementIsAllowed() {
        final CannedAdvisor model = new CannedAdvisor(CannedAdvisor.fenced(XSLT));
        final ShapeshifterAiDoc transformOnly = policy().copy().allowedElements(List.of("XSLTFilter")).build();

        final Outcome outcome = dialogue(model).run(transformOnly, Sample.of(CSV.expectedRecords()));

        assertThat(model.questions()).singleElement().isInstanceOf(Configuration.class);
        assertThat(((Learned) outcome).output()).isEqualTo(EXPECTED_EVENTS);
    }

    @Test
    void abandonsAStepAfterMaxAttempts() {
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser",
                CannedAdvisor.fenced(BAD_CSV.configuration()),
                CannedAdvisor.fenced(BAD_CSV.configuration()));
        final ShapeshifterAiDoc twoAttempts = policy().copy().maxAttempts(2).build();

        final Outcome outcome = dialogue(model).run(twoAttempts, SAMPLE);

        assertThat(outcome).isInstanceOf(Abandoned.class);
        final Abandoned abandoned = (Abandoned) outcome;
        assertThat(abandoned.reason()).contains("DSParser").contains("2 attempts");
        assertThat(abandoned.diagnostics())
                .describedAs("the last try's diagnostics never reached a question, so they travel here")
                .isNotEmpty();
        assertThat(abandoned.transcript()).hasSize(3);
    }

    @Test
    void aPolicyAllowingNothingRunnableIsAbandonedBeforeAsking() {
        final CannedAdvisor model = new CannedAdvisor();
        final ShapeshifterAiDoc jsonOnly = policy().copy().allowedElements(List.of("JSONParser")).build();

        final Outcome outcome = dialogue(model).run(jsonOnly, SAMPLE);

        assertThat(outcome).isInstanceOf(Abandoned.class);
        assertThat(((Abandoned) outcome).reason()).contains("JSONParser");
        assertThat(model.questions()).isEmpty();
    }

    @Test
    void aRunOnlyElementThatFailsAbandonsWithItsDiagnostics() {
        final StepRunner passThrough = new StepRunner() {
            @Override
            public String elementType() {
                return "XMLParser";
            }

            @Override
            public String elementId() {
                return "xmlParser";
            }

            @Override
            public Optional<Configured> configured() {
                return Optional.empty();
            }

            @Override
            public StepResult run(final String configuration, final String input) {
                return new StepResult(null, List.of(new StoredError(
                        Severity.FATAL_ERROR, null, new ElementId(elementId()), "not XML")));
            }
        };
        final CannedAdvisor model = new CannedAdvisor("XMLParser, XSLTFilter");
        final Dialogue dialogue = new Dialogue(model, List.of(passThrough, new XsltStep()),
                new Scorecard(List.of(), List.of()));

        final Outcome outcome = dialogue.run(policy(), SAMPLE);

        assertThat(outcome).isInstanceOf(Abandoned.class);
        final Abandoned abandoned = (Abandoned) outcome;
        assertThat(abandoned.reason()).isEqualTo("XMLParser failed on its input");
        assertThat(abandoned.diagnostics()).extracting(StoredError::getMessage).containsExactly("not XML");
        assertThat(model.questions()).hasSize(1);
    }

    private static Golden golden(final String stem) {
        return ExtractionCorpus.goldens().stream()
                .filter(golden -> golden.stem().equals(stem))
                .findFirst()
                .orElseThrow();
    }

    private static Failing failing(final String stem) {
        return ExtractionCorpus.failing().stream()
                .filter(failing -> failing.stem().equals(stem))
                .findFirst()
                .orElseThrow();
    }

}
