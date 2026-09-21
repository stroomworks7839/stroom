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

import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.LearningPlan;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.Template;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt contract of design 01 §10, as text: each question carries what the section says it must.
 */
class TestQuestionText {

    private static final Sample SAMPLE = new Sample("a,b,c\n", Map.of("Feed", "DOOR-ACCESS", "Format", "CSV"));
    private static final QuestionText WORDS = QuestionText.builtIn(null);

    @Test
    void theChainQuestionCarriesKeyValuesElementsSampleAndGrammar() {
        final String text = WORDS.render(new Chain(SAMPLE, List.of("DSParser", "XSLTFilter"), List.of()));

        assertThat(text)
                .contains("- Feed: DOOR-ACCESS")
                .contains("- Format: CSV")
                .contains("- DSParser: parses raw text")
                .contains("- XSLTFilter: transforms XML records")
                .contains("a,b,c")
                .contains("joined by ->")
                .doesNotContain("What fell short");
    }

    @Test
    void aReAskCarriesThePreviousConfigurationAndWhatFellShort() {
        final StoredError shortfall = new StoredError(Severity.ERROR, null, new ElementId("Scorecard"),
                "Input coverage scored 0.6 against a threshold of 0.9");
        final String text = WORDS.render(new Configuration("DSParser", "TextConverter", SAMPLE, "a,b,c\n",
                "<dataSplitter/>", null, List.of(), List.of(shortfall)));

        assertThat(text)
                .contains("Write the TextConverter document for the DSParser element")
                .contains("schemaLocation is mandatory")
                .contains("Do not use ignoreErrors")
                .contains("Your previous configuration was:")
                .contains("<dataSplitter/>")
                .contains("What fell short:")
                .contains("Input coverage scored 0.6 against a threshold of 0.9")
                .contains("single fenced XML code block");
    }

    @Test
    void theTransformQuestionCarriesTheSchemasFailureModesAndTheDegeneracyTrap() {
        final String text = WORDS.render(new Configuration("XSLTFilter", "XSLT", SAMPLE,
                "<records xmlns=\"records:2\"/>", null, null, List.of(), List.of()));

        assertThat(text)
                .contains("event-logging:3")
                .contains("EventTime/TimeCreated, EventSource and EventDetail, in that order")
                .contains("one of Device, Client, Server or Door")
                .contains("Do not put fields into Data elements or use the Unknown branch")
                .contains("<records xmlns=\"records:2\"/>")
                .doesNotContain("previous configuration");
    }

    @Test
    void theSystemTextCarriesTheDocumentsInstructions() {
        assertThat(QuestionText.builtIn("Badge readers.").system())
                .contains("The document that governs this stage says:")
                .contains("Badge readers.");
        assertThat(WORDS.system()).doesNotContain("governs this stage says");
    }

    @Test
    void theSystemTextForADocumentSaysWhatItsScorersDemand() {
        // Both live runs put the reader's location under Device/Location, never told that Device/Name was
        // required until the score had already been taken.
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder()
                .uuid("doc-1")
                .name("door-access")
                .instructions("Badge readers.")
                .scorers(List.of(
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false,
                                        List.of("EventSource/User/Id", "EventSource/Device/Name"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 0.5, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "interactive events name the user",
                                        "not(EventDetail/Authenticate) or EventDetail/Authenticate/User/Id")),
                                        true))))
                .build();
        assertThat(QuestionText.of(doc).system())
                .startsWith(QuestionText.builtIn("Badge readers.").system())
                .contains("Events are scored on carrying a value in each of: EventSource/User/Id, "
                          + "EventSource/Device/Name. Fill each wherever the record has a value for it")
                .contains("- interactive events name the user: not(EventDetail/Authenticate) or ");
        assertThat(QuestionText.of(ShapeshifterAiDoc.builder().uuid("doc-2").name("bare").build()).system())
                .isEqualTo(WORDS.system());
    }

    @Test
    void aTemplateOverrideIsRenderedWithItsBlocksAndTheRestFollowsTheBuiltIn() {
        // Scenario 40: the chain question in the document's own words; every other question as built in.
        final LearningPlan plan = LearningPlan.of(PlanExample.TARGET_FIRST)
                .withTemplates(Map.of(Template.CHAIN, "Pick from:\n${elements}\nGiven:\n${sample}${feedback}"));
        final QuestionText words = QuestionText.of(plan);
        final Chain chain = new Chain(SAMPLE, List.of("DSParser", "XSLTFilter"), List.of());

        assertThat(words.render(chain))
                .startsWith("Pick from:\n- DSParser: parses raw text")
                .contains("Given:\n```xml\na,b,c\n```")
                .doesNotContain("joined by ->");
        assertThat(words.render(new Split(SAMPLE, "DSParser", "TextConverter", InputKind.TEXT, List.of())))
                .isEqualTo(WORDS.render(new Split(SAMPLE, "DSParser", "TextConverter", InputKind.TEXT, List.of())));
    }

    @Test
    void aTemplateNamingAVariableItDoesNotHaveIsRefused() {
        final LearningPlan plan = LearningPlan.of(PlanExample.DIRECT)
                .withTemplates(Map.of(Template.CHAIN, "${sample} then ${targets}",
                        Template.EXTRACTION_RULES, "Rules ${nothing}"));
        assertThat(Templates.problems(plan)).containsExactlyInAnyOrder(
                "The chain question template names ${targets}, which it does not have; it may use ${elements}, "
                + "${feedback}, ${headers}, ${sample}",
                "The extraction rules template names ${nothing}, which it does not have; it may use no variables");
        assertThat(Templates.problems(LearningPlan.of(PlanExample.DIRECT))).isEmpty();
    }

    @Test
    void aValueThatLooksLikeASlotIsNotReadAsOne() {
        final Sample tricky = new Sample("price=${amount}\n", Map.of());
        assertThat(WORDS.render(new Chain(tricky, List.of("DSParser", "XSLTFilter"), List.of())))
                .contains("price=${amount}");
    }
}
