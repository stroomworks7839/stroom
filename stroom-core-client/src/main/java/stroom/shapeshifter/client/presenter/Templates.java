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

import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.MatchExpression.Length;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.MatchLimits;
import stroom.shapeshifter.config.Template.ParamDecl;

import java.util.ArrayList;
import java.util.List;

/**
 * With-copies of {@link Template} and one-line descriptions of its parts. The model is records
 * without withers (it is shared with the engine and stays plain), so the editor's copies live here.
 */
public final class Templates {

    /** The editor's stable palette: a template's swatch is its position in the project. */
    private static final String[] PALETTE = {
            "#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#b07aa1",
            "#76b7b2", "#edc948", "#ff9da7", "#9c755f", "#bab0ac"};

    private Templates() {
    }

    public static String colour(final int index) {
        return PALETTE[Math.floorMod(index, PALETTE.length)];
    }

    public static int paletteSize() {
        return PALETTE.length;
    }

    public static Template withMatch(final Template t, final MatchExpression match) {
        return new Template(t.id(), t.name(), t.mode(), t.consume(), t.guard(), t.param(), t.declarations(),
                match, t.matchLimits(), t.captures(), t.body(), t.encoding(), t.ignoreErrors());
    }

    public static Template withDeclarations(final Template t, final List<Declaration> declarations) {
        return new Template(t.id(), t.name(), t.mode(), t.consume(), t.guard(), t.param(), declarations,
                t.match(), t.matchLimits(), t.captures(), t.body(), t.encoding(), t.ignoreErrors());
    }

    public static Template withCaptures(final Template t, final List<CaptureBinding> captures) {
        return new Template(t.id(), t.name(), t.mode(), t.consume(), t.guard(), t.param(), t.declarations(),
                t.match(), t.matchLimits(), captures, t.body(), t.encoding(), t.ignoreErrors());
    }

    public static Template withGuard(final Template t, final Condition guard) {
        return new Template(t.id(), t.name(), t.mode(), t.consume(), guard, t.param(), t.declarations(),
                t.match(), t.matchLimits(), t.captures(), t.body(), t.encoding(), t.ignoreErrors());
    }

    public static Template withLimits(final Template t, final MatchLimits limits) {
        return new Template(t.id(), t.name(), t.mode(), t.consume(), t.guard(), t.param(), t.declarations(),
                t.match(), limits, t.captures(), t.body(), t.encoding(), t.ignoreErrors());
    }

    public static Template withIdentity(final Template t, final String name, final String mode, final boolean consume,
                                        final List<ParamDecl> params, final String encoding,
                                        final boolean ignoreErrors) {
        return new Template(t.id(), name, mode, consume, t.guard(), params, t.declarations(),
                t.match(), t.matchLimits(), t.captures(), t.body(), encoding, ignoreErrors);
    }

    /** What the template's matches are for: a record, or only the cursor's advance (§5f). */
    public static Template withRole(final Template t, final boolean consume) {
        return new Template(t.id(), t.name(), t.mode(), consume, t.guard(), t.param(), t.declarations(),
                t.match(), t.matchLimits(), t.captures(), t.body(), t.encoding(), t.ignoreErrors());
    }

    public static Template withBody(final Template t, final List<OutputNode> body) {
        return new Template(t.id(), t.name(), t.mode(), t.consume(), t.guard(), t.param(), t.declarations(),
                t.match(), t.matchLimits(), t.captures(), body, t.encoding(), t.ignoreErrors());
    }

    /** A new template: a regex that matches nothing yet, everything else empty. */
    public static Template create(final String name, final String mode, final boolean consume) {
        return new Template(ProjectText.newId(), name, mode, consume, null, null, null,
                new MatchExpression.Regex("", null, 0), null, null, null, null, false);
    }

    /**
     * The document template a project starts with (design 44 §5m): the source match, and a body
     * that is one {@code apply-templates}. Behaviourally that is what a project without one
     * already does — `RootPlanner` reads a null directive as the null mode and the project's own
     * dispatch, which is exactly what an empty directive says — so this adds no behaviour. What
     * it adds is the place: the author can see where the input loop sits and wrap a root element
     * around it, and has somewhere to declare a run-scoped name.
     *
     * <p>One factory because both routes to it — a new project, and the panel's + on the document
     * row — must produce the same thing.
     */
    public static Template document() {
        final OutputNode apply = new OutputNode.ApplyTemplates(new OutputNode.ApplyDirective(
                null, null, null, OutputNode.ApplyDirective.DEFAULT_MAX_DEPTH, false, null));
        return withBody(withMatch(create("document", null, false), new MatchExpression.Source()),
                List.of(apply));
    }

    /**
     * The project with its document template, adding one at the head where it has none. Every
     * project the editor holds has one (design 44 §5m): the run always has a document, the row
     * is always there, and an author should find the place for a root element and a run-scoped
     * name already made rather than have to know to ask for it.
     */
    public static Project ensureDocument(final Project project) {
        if (project == null) {
            return null;
        }
        for (final Template template : project.templates()) {
            if (template.match() instanceof MatchExpression.Source) {
                return project;
            }
        }
        final List<Template> templates = new ArrayList<>();
        templates.add(document());
        templates.addAll(project.templates());
        return project.withTemplates(templates);
    }

    /** The template with an id in a project, or null. */
    public static Template byId(final Project project, final String id) {
        if (project == null || id == null) {
            return null;
        }
        for (final Template template : project.templates()) {
            if (template.id().equals(id)) {
                return template;
            }
        }
        return null;
    }

    /** The project with a template replaced by id, or appended when it is new. */
    public static Project replace(final Project project, final Template template) {
        final List<Template> templates = new ArrayList<>(project.templates());
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).id().equals(template.id())) {
                templates.set(i, template);
                return project.withTemplates(templates);
            }
        }
        templates.add(template);
        return project.withTemplates(templates);
    }

    /**
     * The project of one template a workbench sample is tried with (design 44 §2): the subject's
     * match and encoding under the document's source settings and with its pattern library, at
     * the root with no guard, limits, declarations, captures or body — so every match is found
     * and nothing else runs.
     */
    public static Project experiment(final Project project, final Template template) {
        // Never a skipping template, whatever the subject is: skipped bytes open no frame (D36),
        // and the sample is nothing but the frames a match opens.
        final Template bare = new Template(template.id(), template.name(), null, false, null, null,
                null, template.match(), null, null, null, template.encoding(), template.ignoreErrors());
        return project.withTemplates(List.of(bare));
    }

    /**
     * The match a kind starts as when there is nothing to carry into it (design 44 §1): the
     * picker says what kind this match <i>is</i>, so choosing one always arrives at that kind —
     * an empty pattern, a newline delimiter, or the kinds that hold nothing at all.
     */
    public static MatchExpression blank(final MatchKind kind) {
        switch (kind) {
            case REGEX:
                return new MatchExpression.Regex("", null, 0);
            case TREE:
                return new MatchExpression.Pattern(new PatternNode.Regex("", null));
            case PARTS:
                return new MatchExpression.Parts(List.of(
                        new MatchPart.Pattern(new PatternNode.Regex("", null))));
            case DELIMITER:
                return new MatchExpression.Delimiter("\n", null, null, null);
            case SOURCE:
                return new MatchExpression.Source();
            case ALL:
                return new MatchExpression.All();
            default:
                return new MatchExpression.Named();
        }
    }

    /**
     * The one pattern a match holds, or null: a tree's node, a one-part sequence's, and a
     * regex's as a leaf. Null means either that the match holds no pattern at all — source,
     * all, named, delimiter — or that it holds more than one, which {@link #holdsPattern} tells
     * apart.
     */
    public static PatternNode singlePattern(final MatchExpression match) {
        if (match instanceof MatchExpression.Pattern pattern) {
            return pattern.node();
        }
        if (match instanceof MatchExpression.Regex regex) {
            return new PatternNode.Regex(regex.pattern(), regex.flags());
        }
        if (match instanceof MatchExpression.Parts parts
            && parts.parts().size() == 1
            && parts.parts().get(0) instanceof MatchPart.Pattern pattern) {
            return pattern.node();
        }
        return null;
    }

    /** Whether the match is one of the pattern kinds, whatever it holds: what a conversion could lose. */
    public static boolean holdsPattern(final MatchExpression match) {
        return match instanceof MatchExpression.Regex
               || match instanceof MatchExpression.Pattern
               || match instanceof MatchExpression.Parts;
    }

    public static String kind(final MatchExpression match) {
        return MatchKind.of(match).spelling();
    }

    public static String describe(final MatchExpression match) {
        if (match instanceof MatchExpression.Regex regex) {
            return regex.pattern();
        } else if (match instanceof MatchExpression.Pattern pattern) {
            return describe(pattern.node());
        } else if (match instanceof MatchExpression.Parts parts) {
            final StringBuilder sb = new StringBuilder();
            for (final MatchPart part : parts.parts()) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(describe(part));
            }
            return sb.toString();
        } else if (match instanceof MatchExpression.Delimiter delimiter) {
            // Escaped, because a newline delimiter rendered raw is a line break the browser
            // collapses to a space — which reads as "split on a space", the wrong answer to the
            // question the chip is there to answer.
            return "split on " + quote(ControlEscapes.escape(delimiter.delimiter()));
        }
        return kind(match);
    }

    public static String describe(final MatchPart part) {
        if (part instanceof MatchPart.Pattern pattern) {
            return "pattern " + describe(pattern.node());
        } else if (part instanceof MatchPart.Take take) {
            return "take " + describe(take.length()) + (take.label() != null
                    ? " as " + take.label()
                    : "");
        } else if (part instanceof MatchPart.Seek seek) {
            return "seek " + (seek.absolute()
                    ? "to "
                    : "") + describe(seek.length());
        } else if (part instanceof MatchPart.Read read) {
            return "read " + read.as().label() + (read.label() != null
                    ? " as " + read.label()
                    : "");
        }
        return "?";
    }

    public static String describe(final Length length) {
        if (length instanceof Length.Literal literal) {
            return String.valueOf(literal.count());
        } else if (length instanceof Length.Label label) {
            return "$" + label.label();
        } else if (length instanceof Length.Var var) {
            return "$" + var.name();
        }
        return "?";
    }

    /** A pattern node in one line, the design 38 vocabulary spelt as the wire format names it. */
    public static String describe(final PatternNode node) {
        if (node instanceof PatternNode.Labelled labelled) {
            return describe(labelled.body()) + " → " + labelled.label()
                   + (labelled.as() != null
                    ? " as " + labelled.as().label()
                    : "");
        } else if (node instanceof PatternNode.Tag tag) {
            return "tag " + quote(tag.text());
        } else if (node instanceof PatternNode.TakeWhile take) {
            return "take_while " + take.classExpression() + bounds(take.min(), take.max());
        } else if (node instanceof PatternNode.TakeUntil take) {
            return (take.inclusive()
                    ? "take_through "
                    : "take_until ") + quote(take.terminator());
        } else if (node instanceof PatternNode.Take take) {
            return take.count() == 1
                    ? "any"
                    : "take " + take.count();
        } else if (node instanceof PatternNode.Regex regex) {
            return "regex " + regex.pattern();
        } else if (node instanceof PatternNode.Ref ref) {
            return "ref " + ref.name();
        } else if (node instanceof PatternNode.Sequence sequence) {
            return "sequence (" + sequence.items().size() + ")";
        } else if (node instanceof PatternNode.Choice choice) {
            return "choice (" + choice.alternatives().size() + ")";
        } else if (node instanceof PatternNode.Optional) {
            return "optional";
        } else if (node instanceof PatternNode.Repeat repeat) {
            return "repeat" + bounds(repeat.min(), repeat.max()) + (repeat.greedy()
                    ? ""
                    : " lazy");
        } else if (node instanceof PatternNode.Peek) {
            return "peek";
        } else if (node instanceof PatternNode.Not) {
            return "not";
        }
        return "?";
    }

    /** The children a node holds, for a nested rendering; leaves answer an empty list. */
    public static List<PatternNode> children(final PatternNode node) {
        if (node instanceof PatternNode.Labelled labelled) {
            return children(labelled.body());
        } else if (node instanceof PatternNode.Sequence sequence) {
            return sequence.items();
        } else if (node instanceof PatternNode.Choice choice) {
            return choice.alternatives();
        } else if (node instanceof PatternNode.Optional optional) {
            return List.of(optional.body());
        } else if (node instanceof PatternNode.Repeat repeat) {
            return List.of(repeat.body());
        } else if (node instanceof PatternNode.Peek peek) {
            return List.of(peek.body());
        } else if (node instanceof PatternNode.Not not) {
            return List.of(not.body());
        }
        return List.of();
    }

    public static String describe(final CaptureSource source) {
        if (source instanceof CaptureSource.Group group) {
            return "group " + group.group();
        } else if (source instanceof CaptureSource.Label label) {
            return "label " + label.label();
        } else if (source instanceof CaptureSource.Select select) {
            final String name = select.select().bareName();
            return "select " + (name != null
                    ? name
                    : "…");
        } else if (source instanceof CaptureSource.KeyValue) {
            return "key-value";
        }
        return "?";
    }

    private static String bounds(final int min, final int max) {
        if (max == PatternNode.Repeat.UNBOUNDED) {
            return min == 0
                    ? ""
                    : " ≥" + min;
        }
        return min == max
                ? " ×" + min
                : " " + min + "–" + max;
    }

    private static String quote(final String text) {
        return "\"" + text + "\"";
    }
}
