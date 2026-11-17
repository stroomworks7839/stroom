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

package stroom.shapeshifter.client.model;

import stroom.shapeshifter.config.Dispatch;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/// Modes are not declared anywhere: a mode exists through the templates in it and the
/// apply-templates sites that dispatch into it (design 18 §5.6). So the mode editor's operations
/// are rewrites over both — a rename touches every template of the mode and every site naming
/// it, a removal is only of a mode with no templates — and this is where those rewrites live.
public final class Modes {

    /// What a mode's name means, beneath the field that asks for one.
    public static final String HELP =
            "Templates in a mode are dispatched only by apply-templates sites naming it.";

    private Modes() {
    }

    /// Whether any template is dispatched in a mode (null for the root).
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

    /// The modes the project has, templates' first then sites', in first-appearance order.
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

    /// Every mode something dispatches into, in one walk of the project.
    ///
    /// @param project The project.
    /// @return The modes named by an apply-templates anywhere in it; the root is null.
    public static Set<String> dispatchedModes(final Project project) {
        final Set<String> modes = new HashSet<>();
        for (final Template template : project.templates()) {
            mapApply(template.body(), directive -> {
                modes.add(directive.mode());
                return directive;
            });
        }
        return modes;
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

    /// How the templates in a mode are chosen at the sites that dispatch into it, or null where
    /// that has no single answer (design 46 §10 W4).
    ///
    /// A mode's ordered list means three different things depending on the strategy at the site
    /// that dispatches into it — priority under `STRICT`, `LAX` and `ANY`, a tie-break under
    /// `LEXER`, and nothing at all under `CLASSIFY` — and the strategy is chosen on a card in
    /// another pane. The panel says which, and this is the fact it says it from.
    ///
    /// Resolved with [Dispatch#effective(Dispatch, Project)], the engine's own answer, because a
    /// site that names no strategy takes the project's and there should be one reading of that.
    ///
    /// Null has two causes and the caller can tell them apart with [#applySiteCount]: no site
    /// dispatches into the mode at all, or several do and they disagree. The corpus has no
    /// example of the second — of 80 modes with a site, six have more than one and none of those
    /// mixes strategies — but a sentence that is sometimes false is worse than none.
    ///
    /// @param project The project.
    /// @param mode The mode, or null for the root group.
    /// @return The one strategy its sites use, or null.
    public static Dispatch dispatchOf(final Project project, final String mode) {
        if (mode == null) {
            // The root level is not dispatched by a card: the run enters it, with the project's
            // own setting.
            return Dispatch.effective(null, project);
        }
        final Set<Dispatch> found = new HashSet<>();
        for (final Template template : project.templates()) {
            mapApply(template.body(), directive -> {
                if (Objects.equals(directive.mode(), mode)) {
                    found.add(Dispatch.effective(directive.dispatch(), project));
                }
                return directive;
            });
        }
        return found.size() == 1
                ? found.iterator().next()
                : null;
    }

    /// The project with a mode renamed: its templates move, and every site naming it follows.
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
        return project.withTemplates(templates);
    }

    /// The project without the sites that dispatch into a mode: what removing an empty mode
    /// means, since a mode with no templates lives on only in the sites naming it. The sites
    /// themselves become dispatches with no mode - into the root - which the messages will say.
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
        return project.withTemplates(templates);
    }

    /// A body with every apply-templates directive mapped, into every branch of every holder.
    /// The one walk over the body vocabulary the editor needs; a holder it does not know is
    /// left as it is, which a new holder kind would make a compile error here, not a silent skip.
    static List<OutputNode> mapApply(final List<OutputNode> body, final Function<ApplyDirective, ApplyDirective> f) {
        List<OutputNode> out = null;
        for (int i = 0; i < body.size(); i++) {
            final OutputNode node = body.get(i);
            final OutputNode mapped = mapApply(node, f);
            // Nothing is copied until something changes: a walk that only reads — counting the
            // sites of a mode, which the panel does on every refresh — then allocates nothing
            // and leaves the project's own nodes in place.
            if (mapped != node && out == null) {
                out = new ArrayList<>(body.subList(0, i));
            }
            if (out != null) {
                out.add(mapped);
            }
        }
        return out == null
                ? body
                : out;
    }

    private static OutputNode mapApply(final OutputNode node, final Function<ApplyDirective, ApplyDirective> f) {
        if (node instanceof ApplyTemplates apply) {
            final ApplyDirective mapped = f.apply(apply.directive());
            return mapped == apply.directive()
                    ? apply
                    : new ApplyTemplates(mapped);
        } else if (node instanceof If i) {
            final List<OutputNode> then = mapApply(i.then(), f);
            return then == i.then()
                    ? i
                    : new If(i.test(), then);
        } else if (node instanceof Choose choose) {
            final List<WhenBranch> when = new ArrayList<>();
            boolean changed = false;
            for (final WhenBranch branch : choose.when()) {
                final List<OutputNode> body = mapApply(branch.body(), f);
                changed |= body != branch.body();
                when.add(body == branch.body()
                        ? branch
                        : new WhenBranch(branch.test(), body));
            }
            final List<OutputNode> otherwise = mapApply(choose.otherwise(), f);
            return !changed && otherwise == choose.otherwise()
                    ? choose
                    : new Choose(when, otherwise);
        } else if (node instanceof Switch s) {
            final List<SwitchCase> cases = new ArrayList<>();
            boolean changed = false;
            for (final SwitchCase c : s.cases()) {
                final List<OutputNode> body = mapApply(c.body(), f);
                changed |= body != c.body();
                cases.add(body == c.body()
                        ? c
                        : new SwitchCase(c.value(), body));
            }
            final List<OutputNode> defaultBody = mapApply(s.defaultBody(), f);
            return !changed && defaultBody == s.defaultBody()
                    ? s
                    : new Switch(s.select(), cases, defaultBody);
        } else if (node instanceof Variable v) {
            final List<OutputNode> body = mapApply(v.body(), f);
            return body == v.body()
                    ? v
                    : new Variable(v.name(), body);
        } else if (node instanceof Element e) {
            final List<OutputNode> body = mapApply(e.body(), f);
            return body == e.body()
                    ? e
                    : new Element(e.name(), e.namespace(), e.omitIfEmpty(), body);
        } else if (node instanceof Attribute a) {
            final List<OutputNode> body = mapApply(a.body(), f);
            return body == a.body()
                    ? a
                    : new Attribute(a.name(), a.omitIfEmpty(), body);
        } else if (node instanceof ForEach fe) {
            final List<OutputNode> body = mapApply(fe.body(), f);
            return body == fe.body()
                    ? fe
                    : new ForEach(fe.select(), fe.as(), fe.asKey(), fe.sort(), body);
        } else if (node instanceof ForEachGroup fg) {
            final List<OutputNode> body = mapApply(fg.body(), f);
            return body == fg.body()
                    ? fg
                    : new ForEachGroup(fg.select(), fg.groupBy(), body);
        }
        return node;
    }
}
