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

import stroom.shapeshifter.client.component.Delimiter;
import stroom.shapeshifter.client.component.Parts;
import stroom.shapeshifter.client.component.Regex;
import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.EngineVars;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/// With-copies of [Template] and one-line descriptions of its parts. The model is records
/// without withers (it is shared with the engine and stays plain), so the editor's copies live here.
public final class Templates {

    /// The editor's stable palette: a template's swatch is its position in the project.
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

    /// Every name a template may bind or read, as the engine's reference check sees them
    /// (design 35): a name is declared once, on the template whose executions it lives for, and
    /// may be bound or read from any other. The template's own come first, then the rest in the
    /// project's order, each once.
    ///
    /// @param project The project, or null for none.
    /// @param template The template the names are offered in, or null.
    /// @return The declared names.
    public static List<String> declaredNames(final Project project, final Template template) {
        return declared(project, template, declaration -> true);
    }

    /// Every list a template may read, as [#declaredNames] finds the names: the template's own
    /// first, then the rest in the project's order, each once.
    ///
    /// @param project The project, or null for none.
    /// @param template The template the names are offered in, or null.
    /// @return The declared lists.
    public static List<String> declaredLists(final Project project, final Template template) {
        return declared(project, template, declaration -> declaration.type() == Declaration.Type.LIST);
    }

    /// What may follow a Select that names a declared list, to say which of its entries is read
    /// (design 44 Q14, ruled 2026-11-19): `[matchCount()]`, the entry as this match is counted,
    /// so the nth match reads the nth; and, inside a `for-each`, `[index()]`, the entry at its
    /// position. The one way to say *the nth of these goes with the nth of those*, offered
    /// where the author is looking, rather than known.
    ///
    /// Nothing where the Select holds anything but the plain name of a declared list: a name
    /// that already wears a subscript says which entry already, and one that is not a list has
    /// no entries to choose among. And no `[index()]` outside an iteration (ruled 2026-11-20):
    /// nothing sets it there, and the reference would quietly read the first entry every time.
    ///
    /// @param lists The declared lists ([#declaredLists]).
    /// @param select What the Select holds.
    /// @param iterated Whether a `for-each` or a `for-each-group` holds the card ([Bodies#iterated]).
    /// @return The subscripts to offer, in order.
    public static List<String> subscriptsAfter(final List<String> lists, final String select,
                                               final boolean iterated) {
        final String name = select == null
                ? ""
                : select.trim();
        if (!Instructions.isPlainName(name) || !lists.contains(name)) {
            return List.of();
        }
        final String counted = "[" + EngineVars.MATCH_COUNT.spelling() + "]";
        return iterated
                ? List.of(counted, "[" + EngineVars.INDEX.spelling() + "]")
                : List.of(counted);
    }

    /// Whether a name a `variable` binds is one to offer to declare (design 47 I): a name a
    /// person could declare — a letter or underscore, then letters, digits and underscores —
    /// that no template declares and that is not a param or a function.
    ///
    /// @param known Every name the template may already read: declared, its params, the functions.
    /// @param name The name, as typed.
    /// @return Whether to offer to declare it.
    public static boolean undeclared(final List<String> known, final String name) {
        return Instructions.isPlainName(name) && !known.contains(name);
    }

    // The names declared that a test passes: the template's own first, then the rest in the
    // project's order, each once — the order they are offered in
    private static List<String> declared(final Project project, final Template template,
                                         final Predicate<Declaration> wanted) {
        final Set<String> names = new LinkedHashSet<>();
        if (template != null) {
            addDeclared(template, wanted, names);
        }
        if (project != null) {
            for (final Template other : project.templates()) {
                addDeclared(other, wanted, names);
            }
        }
        return new ArrayList<>(names);
    }

    private static void addDeclared(final Template template, final Predicate<Declaration> wanted,
                                    final Set<String> names) {
        for (final Declaration declaration : template.declarations()) {
            if (wanted.test(declaration)) {
                names.add(declaration.name());
            }
        }
    }

    /// The template with a scalar declared on it, after what it declares already.
    ///
    /// @param t The template.
    /// @param name The name.
    /// @return The template, or itself where it declares the name already.
    public static Template withScalar(final Template t, final String name) {
        for (final Declaration declaration : t.declarations()) {
            if (declaration.name().equals(name)) {
                return t;
            }
        }
        final List<Declaration> declarations = new ArrayList<>(t.declarations());
        declarations.add(new Declaration(name, Declaration.Type.SCALAR));
        return withDeclarations(t, declarations);
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

    /// The template with the identity another has: its name, mode, params, encoding and whether
    /// it ignores errors. Its role, match, guard, limits, captures, declarations and body are its
    /// own. For a title taken a moment after it was left, onto the template as it is by then.
    ///
    /// @param t The template.
    /// @param from The template whose identity it takes.
    /// @return The template with that identity.
    public static Template withIdentityOf(final Template t, final Template from) {
        return withIdentity(t, from.name(), from.mode(), t.consume(), from.param(), from.encoding(),
                from.ignoreErrors());
    }

    /// Whether two templates have one identity: the name, mode, params, encoding and whether
    /// errors are ignored, which a title edits ([#withIdentityOf]).
    ///
    /// @param a A template.
    /// @param b Another.
    /// @return Whether those are the same in both.
    public static boolean sameIdentity(final Template a, final Template b) {
        return withIdentityOf(a, b).equals(a);
    }

    /// Whether a template other than the one given is called the name given. A call names a
    /// template, so two of a name would be two things a call cannot tell apart.
    ///
    /// @param project The project.
    /// @param name The name.
    /// @param id The template that may have it, or null for none.
    /// @return Whether another template has it.
    public static boolean nameTaken(final Project project, final String name, final String id) {
        if (project == null) {
            return false;
        }
        for (final Template template : project.templates()) {
            if (template.name().equals(name) && !template.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /// What a template's limits let it match, said on its row in the panel: `first only` for at
    /// most one match, `at most N` for at most N, `never matches` for none, or null where there
    /// is no limit on how many (design 47 §7.3, ruled 2026-11-19). The fewest and the only
    /// matches are the strip's to say: they decide what a template's matches mean, not how many
    /// there can be. A template that eats its matches has no limit on how many, whatever it
    /// holds: the engine never applies one to it, so it eats every line it matches.
    ///
    /// @param template The template.
    /// @return What to say, or null.
    public static String limitNote(final Template template) {
        final MatchLimits limits = template.matchLimits();
        if (template.consume() || limits == null || limits.maxMatch() == MatchLimits.UNLIMITED) {
            return null;
        }
        if (limits.maxMatch() == 0) {
            return "never matches";
        }
        return limits.maxMatch() == 1
                ? "first only"
                : "at most " + limits.maxMatch();
    }

    /// A template's limits in a line, as the strip and the workbench say them: `min 2 · max 5`,
    /// `only [1, 3]`, or `no limits`.
    ///
    /// @param limits The limits, or null for none.
    /// @return The line.
    public static String describeLimits(final MatchLimits limits) {
        final List<String> parts = new ArrayList<>();
        if (limits != null) {
            if (limits.minMatch() > 0) {
                parts.add("min " + limits.minMatch());
            }
            if (limits.maxMatch() != MatchLimits.UNLIMITED) {
                parts.add("max " + limits.maxMatch());
            }
            if (limits.onlyMatch() != null && !limits.onlyMatch().isEmpty()) {
                parts.add("only " + limits.onlyMatch());
            }
        }
        return parts.isEmpty()
                ? "no limits"
                : String.join(" · ", parts);
    }

    /// A template's guard and limits in a line, as the workbench's title bar for them says it:
    /// `no guard · no limits`, `status ge 400 · max 1`.
    ///
    /// @param template The template.
    /// @return The line.
    public static String describeGuardAndLimits(final Template template) {
        return GuardClause.describe(template.guard()) + " · " + describeLimits(template.matchLimits());
    }

    /// @param template The template.
    /// @return Whether it has neither a guard nor any limit, so that every frame of its mode is
    ///         tried and every match counts.
    public static boolean unguardedAndUnlimited(final Template template) {
        final GuardClause.Rows guard = GuardClause.read(template.guard());
        final MatchLimits limits = template.matchLimits();
        return guard != null && guard.rows().isEmpty()
               && (limits == null || (limits.minMatch() == 0 && limits.maxMatch() == MatchLimits.UNLIMITED
                                      && (limits.onlyMatch() == null || limits.onlyMatch().isEmpty())));
    }

    /// What the template's matches are for: a record, or only the cursor's advance (§5f).
    public static Template withRole(final Template t, final boolean consume) {
        return new Template(t.id(), t.name(), t.mode(), consume, t.guard(), t.param(), t.declarations(),
                t.match(), t.matchLimits(), t.captures(), t.body(), t.encoding(), t.ignoreErrors());
    }

    public static Template withBody(final Template t, final List<OutputNode> body) {
        return new Template(t.id(), t.name(), t.mode(), t.consume(), t.guard(), t.param(), t.declarations(),
                t.match(), t.matchLimits(), t.captures(), body, t.encoding(), t.ignoreErrors());
    }

    /// A new template: a regex that matches nothing yet, everything else empty.
    public static Template create(final String name, final String mode, final boolean consume) {
        return new Template(ProjectText.newId(), name, mode, consume, null, null, null,
                new MatchExpression.Regex("", null, 0), null, null, null, null, false);
    }

    /// The document template a project starts with (design 44 §5m): the source match, and a body
    /// that is one `apply-templates`. Behaviourally that is what a project without one
    /// already does — `RootPlanner` reads a null directive as the null mode and the project's own
    /// dispatch, which is exactly what an empty directive says — so this adds no behaviour. What
    /// it adds is the place: the author can see where the input loop sits and wrap a root element
    /// around it, and has somewhere to declare a run-scoped name.
    ///
    /// One factory because both routes to it — a new project, and the panel's + on the document
    /// row — must produce the same thing.
    public static Template document() {
        final OutputNode apply = new OutputNode.ApplyTemplates(new OutputNode.ApplyDirective(
                null, null, null, OutputNode.ApplyDirective.DEFAULT_MAX_DEPTH, false, null));
        return withBody(withMatch(create("document", null, false), new MatchExpression.Source()),
                List.of(apply));
    }

    /// The project with its document template, adding one at the head where it has none. Every
    /// project the editor holds has one (design 44 §5m): the run always has a document, the row
    /// is always there, and an author should find the place for a root element and a run-scoped
    /// name already made rather than have to know to ask for it.
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

    /// The template with an id in a project, or null.
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

    /// The project with a template replaced by id, or appended when it is new.
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

    /// The project of one template a workbench sample is tried with (design 44 §2): the subject's
    /// match and encoding under the document's source settings and with its pattern library, at
    /// the root with no guard, limits, declarations, captures or body — so every match is found
    /// and nothing else runs.
    public static Project experiment(final Project project, final Template template) {
        // Never a skipping template, whatever the subject is: skipped bytes open no frame (D36),
        // and the sample is nothing but the frames a match opens.
        final Template bare = new Template(template.id(), template.name(), null, false, null, null,
                null, template.match(), null, null, null, template.encoding(), template.ignoreErrors());
        return project.withTemplates(List.of(bare));
    }

    /// The match a kind starts as when there is nothing to carry into it (design 44 §1): the
    /// picker says what kind this match *is*, so choosing one always arrives at that kind —
    /// an empty pattern, a newline delimiter, or the kinds that hold nothing at all.
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

    /// The one pattern a match holds, or null: a tree's node, a one-part sequence's, and a
    /// regex's as a leaf. Null means either that the match holds no pattern at all — source,
    /// all, named, delimiter — or that it holds more than one, which [#holdsPattern] tells
    /// apart.
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

    /// Whether the match is one of the pattern kinds, whatever it holds: what a conversion could lose.
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

    /// A pattern node in one line, the design 38 vocabulary spelt as the wire format names it.
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

    /// The children a node holds, for a nested rendering; leaves answer an empty list.
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
