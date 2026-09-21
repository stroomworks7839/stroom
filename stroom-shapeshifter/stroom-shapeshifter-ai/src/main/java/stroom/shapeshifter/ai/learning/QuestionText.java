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
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.LearningPlan;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.Template;
import stroom.util.shared.StoredError;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The prompt contract of design 01 §10 as text: what a model is told before its first answer, and how
 * each typed question is put — in the words of the document's dialogue (§10.2). Every question carries
 * the stage's objective and the document's instructions (the system text), the sample and the learning
 * key's values, the real input the element will receive, the previous configuration on a re-ask, and the
 * feedback that lost the marks. For extraction it carries the mandatory {@code xsi:schemaLocation} and
 * the {@code ignoreErrors} prohibition (§9.1); for transformation, the schema's named failure modes
 * (§8.2) and the degeneracy trap (§8.3). The reply grammar is stated on every question.
 * <p>
 * This class computes the blocks a template's variables stand for — {@code ${feedback}} is the whole
 * "what fell short" list or nothing — and {@link Templates} fills them in. Where a node's advisor over
 * {@code stroom-ai} (§12 item 6) and the live harness agree on what is said, so that the harness
 * measures the prompt the node will use.
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

    private static final int INPUT_SHOWN = 6000;

    private final Templates templates;
    private final String system;

    private QuestionText(final Templates templates, final String system) {
        this.templates = templates;
        this.system = system;
    }

    /**
     * The words of a document's plan: its templates, and a system text carrying its instructions
     * and what its scorers will demand of every event — the fields required and the rules asserted —
     * said up front, so the model aims at them rather than learning them from a shortfall.
     */
    public static QuestionText of(final ShapeshifterAiDoc doc) {
        final Templates templates = Templates.of(doc.getPlan());
        final StringBuilder demands = new StringBuilder();
        for (final ScorerSetting setting : doc.getScorers()) {
            if (setting.getParameters() instanceof final ExtractionQualityParameters quality
                && !quality.getRequiredFields().isEmpty()) {
                // Scored as the share of events that carry each, so said as a place, not a bar: a kind of
                // record with no user is still an event.
                demands.append("\n\nEvents are scored on carrying a value in each of: ")
                        .append(String.join(", ", quality.getRequiredFields()))
                        .append(". Fill each wherever the record has a value for it; a record without one is "
                                + "still an event.");
            }
            if (setting.getParameters() instanceof final BusinessRulesParameters rules
                && !rules.getAssertions().isEmpty()) {
                demands.append("\n\nEvery event must satisfy these rules, each an XPath over the Event:");
                rules.getAssertions().forEach(rule -> demands.append("\n- ").append(rule.getName()).append(": ")
                        .append(rule.getXpath()));
            }
        }
        return new QuestionText(templates, templates.render(Template.SYSTEM,
                Map.of("instructions", instructions(doc.getInstructions()), "demands", demands.toString())));
    }

    /**
     * The built-in words with only the instructions: for a test or a harness that has no document.
     */
    public static QuestionText builtIn(final String instructions) {
        final Templates templates = Templates.builtIn();
        return new QuestionText(templates, templates.render(Template.SYSTEM,
                Map.of("instructions", instructions(instructions), "demands", "")));
    }

    public static QuestionText of(final LearningPlan plan) {
        return of(ShapeshifterAiDoc.builder().uuid("plan").name("plan").plan(plan).build());
    }

    private static String instructions(final String instructions) {
        return instructions == null || instructions.isBlank()
                ? ""
                : "\n\nThe document that governs this stage says:\n" + instructions.strip();
    }

    /**
     * The system text: the stage's objective, the document's instructions and its scorers' demands.
     */
    public String system() {
        return system;
    }

    public String render(final Question question) {
        return switch (question) {
            case Chain chain -> chain(chain);
            case Split split -> split(split);
            case TargetFor target -> target(target);
            case Configuration configuration -> configuration(configuration);
        };
    }

    private String chain(final Chain question) {
        final Map<String, String> variables = new HashMap<>();
        variables.put("headers", headers(question.sample()));
        variables.put("elements", question.allowedElements().stream()
                .map(element -> "- " + element + ": " + ELEMENTS.getOrDefault(element, "a pipeline element"))
                .collect(Collectors.joining("\n")));
        variables.put("sample", shown(question.sample().text()));
        variables.put("feedback", feedback(question.feedback()));
        return templates.render(Template.CHAIN, variables);
    }

    /**
     * The record boundary and nothing else (A31): a configuration that cuts the sample into records and
     * emits each whole as one field, so that what follows can be asked about records.
     */
    private String split(final Split question) {
        final Map<String, String> variables = new HashMap<>();
        variables.put("headers", headers(question.sample()));
        if (question.documentType() == null) {
            // Input already in XML: no document to write, an element to name (A35).
            variables.put("sample", shown(question.sample().text()));
            variables.put("feedback", feedback(question.feedback()));
            return templates.render(Template.SPLIT_XML, variables);
        }
        variables.put("elementType", question.elementType());
        variables.put("documentType", question.documentType());
        variables.put("splitRules", templates.text(Template.SPLIT_RULES));
        variables.put("sample", shown(question.sample().text()));
        variables.put("feedback", feedback(question.feedback()));
        return templates.render(Template.SPLIT, variables);
    }

    /**
     * What one kind of record should become (A31): the event, or the word none.
     */
    private String target(final TargetFor question) {
        final Map<String, String> variables = new HashMap<>();
        variables.put("headers", headers(question.sample()));
        variables.put("kind", String.valueOf(question.kind()));
        variables.put("total", String.valueOf(question.total()));
        variables.put("transformationRules", templates.text(Template.TRANSFORMATION_RULES));
        variables.put("record", fenced(question.record()));
        variables.put("feedback", feedback(question.feedback()));
        return templates.render(Template.TARGET, variables);
    }

    private String configuration(final Configuration question) {
        final boolean extraction = "DSParser".equals(question.elementType());
        final Map<String, String> variables = new HashMap<>();
        variables.put("headers", headers(question.sample()));
        variables.put("elementType", question.elementType());
        variables.put("documentType", question.documentType());
        variables.put("rules", templates.text(extraction
                ? Template.EXTRACTION_RULES
                : Template.TRANSFORMATION_RULES));
        variables.put("input", shown(question.input()));
        variables.put("split", split(question.split()));
        variables.put("targets", targets(question.targets(), extraction));
        variables.put("previous", question.previousConfiguration() == null
                ? ""
                : "\nYour previous configuration was:\n" + fenced(question.previousConfiguration()) + "\n");
        variables.put("feedback", feedback(question.feedback()));
        return templates.render(Template.CONFIGURATION, variables);
    }

    /**
     * The settled boundary as the configuration question carries it: a parser's configuration to cut the
     * same records, or the element that is one record where the input is XML (A35).
     */
    private static String split(final Boundary split) {
        if (split == null) {
            return "";
        }
        if (split.configuration() != null) {
            return "\nThe record boundary is settled; this configuration cuts one record per unit, and yours must "
                   + "cut the same records while extracting every field:\n" + fenced(split.configuration()) + "\n";
        }
        return "\nThe record boundary is settled: each <" + split.element() + "> element is one record. Produce one "
               + "event per <" + split.element() + ">, and nothing for the elements around them.\n";
    }

    private static String targets(final List<Target> targets, final boolean extraction) {
        if (targets.isEmpty()) {
            return "";
        }
        final StringBuilder text = new StringBuilder(extraction
                ? "\nWhat each kind of record must become — the records you emit must carry, as data values, "
                  + "every value these events take from the record:\n"
                : "\nWhat each kind of record must become — produce exactly these events for these records:\n");
        int kind = 0;
        for (final Target target : targets) {
            kind++;
            text.append("\nRecord kind ").append(kind).append(":\n").append(fenced(target.record())).append('\n');
            text.append(target.event()
                    .map(event -> "becomes:\n" + fenced(event) + "\n")
                    .orElse(extraction
                            ? "becomes no event. Still emit it as a record, whole; the transform drops it, and "
                              + "coverage measures what the split kept.\n"
                            : "becomes no event.\n"));
        }
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
