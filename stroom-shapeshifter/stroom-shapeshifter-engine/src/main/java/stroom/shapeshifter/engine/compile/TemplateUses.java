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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.regex.LeadingAnchor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one template's body refers to — the templates it calls and the applies it makes —
 * collected in one walk per body, and the two checks that read the collection: every name
 * must exist, and D36's dispatch lint must know which modes are strict.
 */
record TemplateUses(Template template, List<String> calls, List<OutputNode.ApplyDirective> applies) {

    /** One walk per body for both kinds of reference; name resolution and the dispatch lint read it. */
    static List<TemplateUses> of(final Project project) {
        final List<TemplateUses> uses = new ArrayList<>(project.templates().size());
        for (final Template template : project.templates()) {
            final List<String> calls = new ArrayList<>();
            final List<OutputNode.ApplyDirective> applies = new ArrayList<>();
            collectUses(template.body(), calls, applies);
            uses.add(new TemplateUses(template, List.copyOf(calls), List.copyOf(applies)));
        }
        return uses;
    }

    private static void collectUses(final List<OutputNode> body,
                                    final List<String> calls,
                                    final List<OutputNode.ApplyDirective> applies) {
        for (final OutputNode node : body) {
            switch (node) {
                case OutputNode.CallTemplate value -> calls.add(value.name());
                case OutputNode.ApplyTemplates apply -> applies.add(apply.directive());
                default -> {
                    // Refers to no template itself; what it holds is walked below.
                }
            }
            for (final List<OutputNode> nested : Containers.bodies(node)) {
                collectUses(nested, calls, applies);
            }
        }
    }

    /**
     * Resolve every name that points at a template — a {@code call-template}'s target and an
     * {@code apply-templates}' template reference — against the templates that exist.
     *
     * <p>Compilation is where a configuration's mistakes are found. Left to run time, a name
     * with a typo in it finds nothing, and finding nothing is spelt the same as a template that
     * legitimately wrote nothing: the run completes, the output is short, and the configuration
     * looks correct.
     */
    static void resolveNames(final Project project, final List<TemplateUses> uses) {
        final Set<String> names = new HashSet<>();
        for (final Template template : project.templates()) {
            names.add(template.name());
        }
        for (final TemplateUses use : uses) {
            final List<String> referenced = new ArrayList<>(use.calls());
            for (final OutputNode.ApplyDirective directive : use.applies()) {
                if (directive.templateRef() != null) {
                    referenced.add(directive.templateRef());
                }
            }
            for (final String name : referenced) {
                if (!names.contains(name)) {
                    throw new ConfigException("Template '" + use.template().name() + "' refers to a"
                                              + " template named '" + name + "', which does not exist");
                }
            }
        }
        for (final String name : recursiveTargets(uses)) {
            final String reserved = OutputNode.ApplyDirective.recursiveMode(name);
            for (final Template template : project.templates()) {
                if (reserved.equals(template.mode())) {
                    throw new ConfigException("Template '" + template.name() + "' is in mode '" + reserved
                                              + "', which is the mode a template_ref to '" + name
                                              + "' dispatches to; choose another mode name");
                }
            }
        }
    }

    /**
     * The templates {@code template_ref} directives name, for the graph to register each under
     * its recursive mode (E42).
     */
    static Set<String> recursiveTargets(final List<TemplateUses> uses) {
        final Set<String> targets = new LinkedHashSet<>();
        for (final TemplateUses use : uses) {
            for (final OutputNode.ApplyDirective directive : use.applies()) {
                if (directive.templateRef() != null) {
                    targets.add(directive.templateRef());
                }
            }
        }
        return targets;
    }

    /**
     * D36's dispatch lint: a line-anchored pattern in a strict or lexer level draws a warning —
     * the anchored question means it matches at the cursor only, and a line anchor does not
     * make it search line starts. It reads the compiled matches, so it runs after them.
     */
    static void lintDispatch(final Project project,
                             final List<CompiledTemplate> templates,
                             final List<TemplateUses> uses,
                             final List<Message> warnings) {
        final Set<String> strictModes = new HashSet<>();
        for (final TemplateUses use : uses) {
            for (final OutputNode.ApplyDirective directive : use.applies()) {
                final Dispatch effective = Dispatch.effective(directive.dispatch(), project);
                if (effective == Dispatch.STRICT || effective == Dispatch.LEXER) {
                    strictModes.add(directive.effectiveMode());
                }
            }
        }
        for (final CompiledTemplate compiledTemplate : templates) {
            if (strictModes.contains(compiledTemplate.template().mode())
                && compiledTemplate.match() instanceof CompiledMatch.Regex regex
                && regex.pattern().leadingAnchor() == LeadingAnchor.LINE) {
                warnings.add(new Message(Severity.WARNING, "Template '"
                        + compiledTemplate.template().name()
                        + "' uses a line-anchored pattern in a strict level: it matches at the"
                        + " cursor only, and the line anchor does not make it search line"
                        + " starts. If line iteration is intended, add a line eater."));
            }
        }
    }
}
