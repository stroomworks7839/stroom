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

package stroom.shapeshifter.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// The learning plan (A33, A34, A37; design 01 §10.2): the graph of questions, checks and transitions the
/// Shapeshifter AI document holds, and the templates it overrides. An attempt follows the plan; what it
/// says and hears is its conversation. A document naming no steps is read as [PlanExample#DIRECT]'s.
@JsonPropertyOrder({"steps", "templates", "builtInVersion"})
@JsonInclude(Include.NON_NULL)
public class LearningPlan {

    /// The document's own steps, in the order they are first entered.
    @JsonProperty
    private final List<PlanStep> steps;
    /// Only the templates this document changes.
    @JsonProperty
    private final Map<Template, String> templates;
    /// The version of the built-in text this document was last saved against, so that a run read back can be
    /// told apart from a later default; null until saved by a node that knows.
    @JsonProperty
    private final Integer builtInVersion;

    @JsonCreator
    public LearningPlan(@JsonProperty("steps") final List<PlanStep> steps,
                        @JsonProperty("templates") final Map<Template, String> templates,
                        @JsonProperty("builtInVersion") final Integer builtInVersion) {
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps == null
                ? PlanExample.DIRECT.steps()
                : steps));
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

    /// A plan with an example's steps as its own and the built-in text.
    public static LearningPlan of(final PlanExample example) {
        return new LearningPlan(example.steps(), null, null);
    }

    /// @return The steps in the order they are first entered.
    public List<PlanStep> getSteps() {
        return steps;
    }

    /// @return Only the templates this document overrides, by template.
    public Map<Template, String> getTemplates() {
        return templates;
    }

    /// @return The built-in text's version this document was last saved against, or null until saved.
    public Integer getBuiltInVersion() {
        return builtInVersion;
    }

    /// The step `goto` reaches by a name, or null where no step has it.
    public PlanStep step(final String id) {
        for (final PlanStep step : steps) {
            if (step.effectiveId().equals(id)) {
                return step;
            }
        }
        return null;
    }

    /// What is wrong with the steps, structurally: CHAIN first, once and without transitions, CONFIGURE last,
    /// SPLIT and TARGET at most once each, every id unique and every `goto` naming a step or `end`. Empty where
    /// the steps are a plan the stage can hold. The templates' variables are the server's to check, since it
    /// holds the built-in text.
    public List<String> problems() {
        final List<String> problems = new ArrayList<>();
        if (steps.isEmpty()) {
            problems.add("The plan has no steps");
            return problems;
        }
        if (steps.get(0).getKind() != QuestionKind.CHAIN) {
            problems.add("The first step must be CHAIN");
        }
        if (steps.get(steps.size() - 1).getKind() != QuestionKind.CONFIGURE) {
            problems.add("The last step must be CONFIGURE");
        }
        for (final QuestionKind kind : QuestionKind.values()) {
            int count = 0;
            for (final PlanStep step : steps) {
                if (step.getKind() == kind) {
                    count++;
                }
            }
            if (count > 1 && kind != QuestionKind.CONFIGURE) {
                problems.add(kind.name() + " may appear once, not " + count + " times");
            }
            if (count == 0 && (kind == QuestionKind.CHAIN || kind == QuestionKind.CONFIGURE)) {
                problems.add(kind.name() + " must appear");
            }
        }
        final Map<String, PlanStep> ids = new HashMap<>();
        for (final PlanStep step : steps) {
            if (Transition.END.equals(step.effectiveId())) {
                problems.add("'" + Transition.END + "' is the end of the plan and cannot name a step");
            }
            final PlanStep first = ids.putIfAbsent(step.effectiveId(), step);
            // Two of a kind that may appear once have been reported above, unless one was named on purpose.
            final boolean reported = first != null && first.getKind() == step.getKind()
                                     && step.getKind() != QuestionKind.CONFIGURE
                                     && first.getId() == null && step.getId() == null;
            if (first != null && !reported) {
                problems.add("Two steps are named '" + step.effectiveId() + "'; give one an id");
            }
        }
        for (final PlanStep step : steps) {
            final Set<String> fired = new HashSet<>();
            for (final Transition transition : step.getTransitions()) {
                final String on = transition.onSpent()
                        ? Transition.SPENT
                        : transition.getOn().getDisplayValue();
                if (!fired.add(on)) {
                    problems.add("Step '" + step.effectiveId() + "' says 'on " + on + "' twice; the first would "
                                 + "be taken and the second never");
                }
            }
            if (step.getKind() == QuestionKind.CHAIN && !step.getTransitions().isEmpty()) {
                // No later step can run without a chain: a refused chain is re-asked, and one whose candidates
                // are spent abandons.
                problems.add("CHAIN takes no transition; nothing can run until the chain is settled");
            }
            for (final Transition transition : step.getTransitions()) {
                if (!transition.abandons() && !ids.containsKey(transition.getGoTo())
                    && !Transition.END.equals(transition.getGoTo())) {
                    problems.add("'" + transition.format() + "' in step '" + step.effectiveId()
                                 + "' names no step");
                }
            }
        }
        return problems;
    }

    /// This plan with other steps.
    public LearningPlan withSteps(final List<PlanStep> steps) {
        return new LearningPlan(steps, templates, builtInVersion);
    }

    /// This plan with other template overrides.
    public LearningPlan withTemplates(final Map<Template, String> templates) {
        return new LearningPlan(steps, templates, builtInVersion);
    }

    /// This plan stamped with the built-in text's version it is saved against.
    public LearningPlan withBuiltInVersion(final int version) {
        return new LearningPlan(steps, templates, version);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LearningPlan that = (LearningPlan) o;
        return Objects.equals(steps, that.steps) && Objects.equals(templates, that.templates)
               && Objects.equals(builtInVersion, that.builtInVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(steps, templates, builtInVersion);
    }

    @Override
    public String toString() {
        return "LearningPlan{steps=" + steps + ", templates=" + templates.keySet()
               + ", builtInVersion=" + builtInVersion + '}';
    }
}
