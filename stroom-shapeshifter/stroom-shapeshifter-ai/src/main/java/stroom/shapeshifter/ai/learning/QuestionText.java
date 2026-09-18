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
import stroom.util.shared.StoredError;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The prompt contract of design 01 §10 as text: what a model is told before its first answer, and how
 * each typed question is put. Every question carries the stage's objective and the document's
 * instructions (the system text), the sample and the learning key's values, the real input the element
 * will receive, the previous configuration on a re-ask, and the feedback that lost the marks. For
 * extraction it carries the mandatory {@code xsi:schemaLocation} and the {@code ignoreErrors}
 * prohibition (§9.1); for transformation, the schema's named failure modes (§8.2) and the degeneracy
 * trap (§8.3). The reply grammar is stated on every question: a chain is element names joined by
 * {@code ->}; a configuration is one fenced code block and nothing else.
 * <p>
 * Where a node's advisor over {@code stroom-ai} (§12 item 6) and the live harness agree on what is
 * said, so that the harness measures the prompt the node will use.
 */
public final class QuestionText {

    /**
     * What each allowed element does, in one line, so the chain question is answered from a vocabulary
     * the model understands rather than a list of tokens.
     */
    private static final Map<String, String> ELEMENTS = Map.of(
            "DSParser", "parses raw text into records:2 XML with a Stroom Data Splitter 3.0 configuration",
            "JSONParser", "parses JSON into records:2 XML, with no configuration",
            "XMLParser", "parses XML input as it is, with no configuration",
            "XSLTFilter", "transforms XML records into event-logging:3 events with an XSLT 2.0 stylesheet");

    private static final String EXTRACTION_RULES = """
            The root element is <dataSplitter xmlns="data-splitter:3" \
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
            xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd" version="3.0">. \
            The schemaLocation is mandatory; a configuration without it is rejected.

            The structure is strict, so follow this shape exactly. A <split delimiter="\\n"> cuts the input \
            into lines; inside it, one <group value="$1"> takes each line as the text the elements inside \
            the group match. Inside the group put a <regex pattern="..."> (or a further <split>); the \
            <data name="..." value="$n"/> elements go directly inside that <regex> or <split>, one per \
            captured field, never wrapped in another <group>. Each line that matches becomes one record. \
            <var id="..."/> stores a match for later reference as $id$n. A worked example for lines of \
            "time,user,place,action":

            ```xml
            <?xml version="1.0" encoding="UTF-8"?>
            <dataSplitter xmlns="data-splitter:3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
            xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd" version="3.0">
              <split delimiter="\\n">
                <group value="$1">
                  <regex pattern="^([^,]+),([^,]+),([^,]+),([^,]+)$">
                    <data name="time" value="$1"/>
                    <data name="user" value="$2"/>
                    <data name="place" value="$3"/>
                    <data name="action" value="$4"/>
                  </regex>
                </group>
              </split>
            </dataSplitter>
            ```

            Where the input has more than one kind of line, put one <regex> per kind inside the same group. \
            Do not use ignoreErrors. Every line of the input should be consumed by a match that emits a record; \
            a line the configuration quietly drops counts against it.""";

    private static final String TRANSFORMATION_RULES = """
            The stylesheet reads records:2 (use xpath-default-namespace="records:2") and writes event-logging:3 \
            events: <Events xmlns="event-logging:3" xsi:schemaLocation="event-logging:3 \
            file://event-logging-v3.0.0.xsd" Version="3.0.0"> holding one <Event> per record. Every Event needs \
            EventTime/TimeCreated, EventSource and EventDetail, in that order. EventSource needs System (Name, \
            Environment), Generator and one of Device, Client, Server or Door, and should name the User where the \
            input has one. EventDetail needs TypeId and then exactly one branch describing what happened, from \
            this list and no other: Authenticate, Authorise, Search, Copy, Move, Create, View, Import, Export, \
            Update, Delete, Process, Print, Install, Uninstall, Network, AntiMalware, Alert, Send, Receive. \
            Authenticate holds Action first (Logon, Logoff, ...), then User with its Id; other branches hold \
            their own typed elements — a Description is a child of the branch's element, never the branch. \
            Element order is enforced everywhere; datetimes are ISO 8601 with milliseconds and a trailing Z. \
            Every field the input carries should land in a typed element. Do not put fields into Data elements \
            or use the Unknown branch: output that validates but says nothing scores as nothing.""";

    private static final int INPUT_SHOWN = 6000;

    private QuestionText() {
    }

    /**
     * The system text: the stage's objective and the document's instructions, if it has any.
     */
    public static String system(final String instructions) {
        return "You configure one stage of a Stroom data pipeline so that it turns the streams a feed sends into "
               + "well-formed, meaningful events. You are asked a series of questions: which chain of elements "
               + "fits a sample of the data, then the configuration document for each element in turn, each "
               + "shown the real output of the elements before it. When a candidate falls short you are asked "
               + "again with exactly what lost the marks; fix that. Answer each question in the form it asks for "
               + "and nothing else."
               + (instructions == null || instructions.isBlank()
                ? ""
                : "\n\nThe document that governs this stage says:\n" + instructions.strip());
    }

    public static String render(final Question question) {
        return switch (question) {
            case Chain chain -> chain(chain);
            case Configuration configuration -> configuration(configuration);
        };
    }

    private static String chain(final Chain question) {
        final StringBuilder text = new StringBuilder();
        text.append("Which chain of elements turns this stream into events?\n\n");
        text.append(headers(question.sample()));
        text.append("The elements you may use:\n");
        for (final String element : question.allowedElements()) {
            text.append("- ").append(element).append(": ")
                    .append(ELEMENTS.getOrDefault(element, "a pipeline element")).append('\n');
        }
        text.append("\nA sample of the stream:\n").append(shown(question.sample().text())).append('\n');
        text.append(feedback(question.feedback()));
        text.append("\nReply with the element names in order, joined by ->, for example \"DSParser -> XSLTFilter\", "
                    + "and nothing else.");
        return text.toString();
    }

    private static String configuration(final Configuration question) {
        final StringBuilder text = new StringBuilder();
        final boolean extraction = "DSParser".equals(question.elementType());
        text.append("Write the ").append(question.documentType()).append(" document for the ")
                .append(question.elementType()).append(" element.\n\n");
        text.append(headers(question.sample()));
        text.append(extraction
                ? EXTRACTION_RULES
                : TRANSFORMATION_RULES).append("\n\n");
        text.append("The element will receive this input:\n").append(shown(question.input())).append('\n');
        if (question.previousConfiguration() != null) {
            text.append("\nYour previous configuration was:\n")
                    .append(fenced(question.previousConfiguration())).append('\n');
        }
        text.append(feedback(question.feedback()));
        text.append("\nReply with the ").append(question.documentType())
                .append(" document as a single fenced XML code block and nothing else.");
        return text.toString();
    }

    private static String headers(final Sample sample) {
        if (sample.headers().isEmpty()) {
            return "";
        }
        return "The stream is described by:\n" + sample.headers().entrySet().stream()
                .map(entry -> "- " + entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining("\n")) + "\n\n";
    }

    private static String feedback(final List<StoredError> feedback) {
        if (feedback.isEmpty()) {
            return "";
        }
        return "\nWhat fell short:\n" + feedback.stream()
                .map(error -> "- " + error.getSeverity().getDisplayValue() + ": " + error.getMessage())
                .collect(Collectors.joining("\n")) + "\n";
    }

    private static String fenced(final String document) {
        return "```xml\n" + document.strip() + "\n```";
    }

    /**
     * The input as far as a question should carry it; a long stream is cut with a note, since the model
     * learns from a sample, not the whole.
     */
    private static String shown(final String input) {
        if (input == null) {
            return "(nothing)";
        }
        return input.length() <= INPUT_SHOWN
                ? fenced(input)
                : fenced(input.substring(0, INPUT_SHOWN)) + "\n(cut after " + INPUT_SHOWN + " of " + input.length()
                  + " characters)";
    }
}
