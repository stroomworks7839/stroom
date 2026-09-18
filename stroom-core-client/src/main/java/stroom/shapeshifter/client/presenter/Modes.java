/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.config.OutputNode.ApplyTemplates;
import stroom.shapeshifter.config.OutputNode.Attribute;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Element;
import stroom.shapeshifter.config.OutputNode.ForEach;
import stroom.shapeshifter.config.OutputNode.ForEachGroup;
import stroom.shapeshifter.config.OutputNode.If;
import stroom.shapeshifter.config.OutputNode.Switch;
import stroom.shapeshifter.config.OutputNode.SwitchCase;
import stroom.shapeshifter.config.OutputNode.Variable;
import stroom.shapeshifter.config.OutputNode.WhenBranch;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Modes are not declared anywhere: a mode exists through the templates in it and the
 * apply-templates sites that dispatch into it (design 18 §5.6). So the mode editor's operations
 * are rewrites over both — a rename touches every template of the mode and every site naming
 * it, a removal is only of a mode with no templates — and this is where those rewrites live.
 */
public final class Modes {

    private Modes() {
    }

    /** Whether any template is dispatched in a mode (null for the root). */
    public static boolean hasTemplates(final Project project, final String mode) {
        if (project == null) {
            return false;
        }
        for (final Template template : project.templates()) {
            if (Objects.equals(template.mode(), mode)) {
                return true;
            }
        }
        return false;
    }

    /** The modes the project has, templates' first then sites', in first-appearance order. */
    public static List<String> of(final Project project) {
        final Set<String> modes = new LinkedHashSet<>();
        for (final Template template : project.templates()) {
            if (template.mode() != null) {
                modes.add(template.mode());
            }
        }
        for (final Template template : project.templates()) {
            mapApply(template.body(), directive -> {
                if (directive.mode() != null) {
                    modes.add(directive.mode());
                }
                return directive;
            });
        }
        return new ArrayList<>(modes);
    }

    public static int templateCount(final Project project, final String mode) {
        int count = 0;
        for (final Template template : project.templates()) {
            if (Objects.equals(template.mode(), mode)) {
                count++;
            }
        }
        return count;
    }

    public static int applySiteCount(final Project project, final String mode) {
        final int[] count = {0};
        for (final Template template : project.templates()) {
            mapApply(template.body(), directive -> {
                if (Objects.equals(directive.mode(), mode)) {
                    count[0]++;
                }
                return directive;
            });
        }
        return count[0];
    }

    /** The project with a mode renamed: its templates move, and every site naming it follows. */
    public static Project rename(final Project project, final String from, final String to) {
        final List<Template> templates = new ArrayList<>();
        for (final Template t : project.templates()) {
            final String mode = Objects.equals(t.mode(), from)
                    ? to
                    : t.mode();
            final List<OutputNode> body = mapApply(t.body(), directive -> Objects.equals(directive.mode(), from)
                    ? new ApplyDirective(directive.select(), to, directive.withParam(), directive.maxDepth(),
                    directive.ignoreErrors(), directive.dispatch())
                    : directive);
            templates.add(new Template(t.id(), t.name(), mode, t.consume(), t.guard(), t.param(), t.declarations(),
                    t.match(), t.matchLimits(), t.captures(), body, t.encoding(), t.ignoreErrors()));
        }
        return new Project(project.name(), project.version(), project.source(), templates);
    }

    /**
     * The project without the sites that dispatch into a mode: what removing an empty mode
     * means, since a mode with no templates lives on only in the sites naming it. The sites
     * themselves become dispatches with no mode - into the root - which the messages will say.
     */
    public static Project removeSites(final Project project, final String mode) {
        final List<Template> templates = new ArrayList<>();
        for (final Template t : project.templates()) {
            final List<OutputNode> body = mapApply(t.body(), directive -> Objects.equals(directive.mode(), mode)
                    ? new ApplyDirective(directive.select(), null, directive.withParam(), directive.maxDepth(),
                    directive.ignoreErrors(), directive.dispatch())
                    : directive);
            templates.add(new Template(t.id(), t.name(), t.mode(), t.consume(), t.guard(), t.param(),
                    t.declarations(), t.match(), t.matchLimits(), t.captures(), body, t.encoding(),
                    t.ignoreErrors()));
        }
        return new Project(project.name(), project.version(), project.source(), templates);
    }

    /**
     * A body with every apply-templates directive mapped, into every branch of every holder.
     * The one walk over the body vocabulary the editor needs; a holder it does not know is
     * left as it is, which a new holder kind would make a compile error here, not a silent skip.
     */
    static List<OutputNode> mapApply(final List<OutputNode> body, final Function<ApplyDirective, ApplyDirective> f) {
        final List<OutputNode> out = new ArrayList<>(body.size());
        for (final OutputNode node : body) {
            out.add(mapApply(node, f));
        }
        return out;
    }

    private static OutputNode mapApply(final OutputNode node, final Function<ApplyDirective, ApplyDirective> f) {
        if (node instanceof ApplyTemplates apply) {
            final ApplyDirective mapped = f.apply(apply.directive());
            return mapped == apply.directive()
                    ? apply
                    : new ApplyTemplates(mapped);
        } else if (node instanceof If i) {
            return new If(i.test(), mapApply(i.then(), f));
        } else if (node instanceof Choose choose) {
            final List<WhenBranch> when = new ArrayList<>();
            for (final WhenBranch branch : choose.when()) {
                when.add(new WhenBranch(branch.test(), mapApply(branch.body(), f)));
            }
            return new Choose(when, mapApply(choose.otherwise(), f));
        } else if (node instanceof Switch s) {
            final List<SwitchCase> cases = new ArrayList<>();
            for (final SwitchCase c : s.cases()) {
                cases.add(new SwitchCase(c.value(), mapApply(c.body(), f)));
            }
            return new Switch(s.select(), cases, mapApply(s.defaultBody(), f));
        } else if (node instanceof Variable v) {
            return new Variable(v.name(), mapApply(v.body(), f));
        } else if (node instanceof Element e) {
            return new Element(e.name(), e.namespace(), e.omitIfEmpty(), mapApply(e.body(), f));
        } else if (node instanceof Attribute a) {
            return new Attribute(a.name(), a.omitIfEmpty(), mapApply(a.body(), f));
        } else if (node instanceof ForEach fe) {
            return new ForEach(fe.select(), fe.as(), fe.asKey(), fe.sort(), mapApply(fe.body(), f));
        } else if (node instanceof ForEachGroup fg) {
            return new ForEachGroup(fg.select(), fg.groupBy(), mapApply(fg.body(), f));
        }
        return node;
    }
}
