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

    @Test
    void theChainQuestionCarriesKeyValuesElementsSampleAndGrammar() {
        final String text = QuestionText.render(new Chain(SAMPLE, List.of("DSParser", "XSLTFilter"), List.of()));

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
        final String text = QuestionText.render(new Configuration("DSParser", "TextConverter", SAMPLE, "a,b,c\n",
                "<dataSplitter/>", List.of(shortfall)));

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
        final String text = QuestionText.render(new Configuration("XSLTFilter", "XSLT", SAMPLE,
                "<records xmlns=\"records:2\"/>", null, List.of()));

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
        assertThat(QuestionText.system("Badge readers.")).contains("The document that governs this stage says:")
                .contains("Badge readers.");
        assertThat(QuestionText.system(null)).doesNotContain("governs this stage says");
    }
}
