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


package stroom.shapeshifter.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The dialogue a stage holds with its model, as data on the document (design 01 §10.2, rulings A32 and A33):
 * a preset it starts from, the ordered steps it asks — the preset's when unset — and the templates it
 * overrides, every other text following the built-in of the version it was saved against. The question
 * kinds, their reply grammars and their judges are code; this says which are asked, when, in what words.
 */
@JsonPropertyOrder({"preset", "steps", "templates", "builtInVersion"})
@JsonInclude(Include.NON_NULL)
public class DialogueDefinition {

    @JsonProperty
    private final DialogueShape preset;
    /**
     * Null: the preset's steps.
     */
    @JsonProperty
    private final List<DialogueStep> steps;
    /**
     * Only the templates this document changes.
     */
    @JsonProperty
    private final Map<Template, String> templates;
    /**
     * The version of the built-in text this document was last saved against, so that a run read back can be
     * told apart from a later default; null until saved by a node that knows.
     */
    @JsonProperty
    private final Integer builtInVersion;

    @JsonCreator
    public DialogueDefinition(@JsonProperty("preset") final DialogueShape preset,
                              @JsonProperty("steps") final List<DialogueStep> steps,
                              @JsonProperty("templates") final Map<Template, String> templates,
                              @JsonProperty("builtInVersion") final Integer builtInVersion) {
        this.preset = preset == null
                ? DialogueShape.DIRECT
                : preset;
        this.steps = steps == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(steps));
        final Map<Template, String> overrides = new EnumMap<>(Template.class);
        if (templates != null) {
            // A blank override is no override: the document keeps following the built-in.
            templates.forEach((template, text) -> {
                if (text != null && !text.trim().isEmpty()) {
                    overrides.put(template, text);
                }
            });
        }
        this.templates = Collections.unmodifiableMap(overrides);
        this.builtInVersion = builtInVersion;
    }

    public static DialogueDefinition of(final DialogueShape preset) {
        return new DialogueDefinition(preset, null, null, null);
    }

    public DialogueShape getPreset() {
        return preset;
    }

    public List<DialogueStep> getSteps() {
        return steps;
    }

    public Map<Template, String> getTemplates() {
        return templates;
    }

    public Integer getBuiltInVersion() {
        return builtInVersion;
    }

    /**
     * The steps the stage asks: this document's, or the preset's where it names none.
     */
    public List<DialogueStep> effectiveSteps() {
        return steps == null
                ? preset.steps()
                : steps;
    }

    /**
     * What is wrong with the steps, structurally: CHAIN first and once, CONFIGURE last and once, SPLIT and
     * TARGET at most once each between them. Empty where the steps are a dialogue the stage can hold. The
     * templates' variables are the server's to check, since it holds the built-in text.
     */
    public List<String> problems() {
        final List<String> problems = new ArrayList<>();
        final List<DialogueStep> effective = effectiveSteps();
        if (effective.isEmpty()) {
            problems.add("The dialogue has no steps");
            return problems;
        }
        if (effective.get(0).getKind() != QuestionKind.CHAIN) {
            problems.add("The first step must be CHAIN");
        }
        if (effective.get(effective.size() - 1).getKind() != QuestionKind.CONFIGURE) {
            problems.add("The last step must be CONFIGURE");
        }
        for (final QuestionKind kind : QuestionKind.values()) {
            int count = 0;
            for (final DialogueStep step : effective) {
                if (step.getKind() == kind) {
                    count++;
                }
            }
            if (count > 1) {
                problems.add(kind.name() + " may appear once, not " + count + " times");
            }
            if (count == 0 && (kind == QuestionKind.CHAIN || kind == QuestionKind.CONFIGURE)) {
                problems.add(kind.name() + " must appear");
            }
        }
        return problems;
    }

    public DialogueDefinition withSteps(final List<DialogueStep> steps) {
        return new DialogueDefinition(preset, steps, templates, builtInVersion);
    }

    public DialogueDefinition withTemplates(final Map<Template, String> templates) {
        return new DialogueDefinition(preset, steps, templates, builtInVersion);
    }

    public DialogueDefinition withBuiltInVersion(final int version) {
        return new DialogueDefinition(preset, steps, templates, version);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DialogueDefinition that = (DialogueDefinition) o;
        return preset == that.preset && Objects.equals(steps, that.steps) && Objects.equals(templates, that.templates)
               && Objects.equals(builtInVersion, that.builtInVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(preset, steps, templates, builtInVersion);
    }

    @Override
    public String toString() {
        return "DialogueDefinition{preset=" + preset + ", steps=" + steps + ", templates=" + templates.keySet()
               + ", builtInVersion=" + builtInVersion + '}';
    }
}
