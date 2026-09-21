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

import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The project's pattern library as the editor works it (design 44 §3): parts defined once and
 * named with {@code ref} from any template's tree, or from one another. Every edit is a
 * rewrite of the project — a rename follows every {@code ref}, a part in use cannot be
 * removed, extracting a node makes it a part and leaves a {@code ref} in its place, inlining a
 * {@code ref} puts the part back.
 */
public final class Patterns {

    /** What a part's name means, beneath the field that asks for one. */
    public static final String HELP = "A part any template's tree names with ref, and other parts may too; "
                                      + "not a name the standard library has.";

    /** The row id a part has in the nav panel, distinct from any template's uuid. */
    private static final String ROW = "pattern:";

    private Patterns() {
    }

    public static String rowId(final String name) {
        return ROW + name;
    }

    /** The part a nav row stands for, or null for a template's or the document's row. */
    public static String nameOf(final String rowId) {
        return rowId != null && rowId.startsWith(ROW)
                ? rowId.substring(ROW.length())
                : null;
    }

    /** How many templates and other parts name a part, directly. */
    public static int uses(final Project project, final String name) {
        int uses = 0;
        for (final Template template : project.templates()) {
            if (refers(template.match(), name)) {
                uses++;
            }
        }
        for (final Map.Entry<String, PatternNode> part : project.patterns().entrySet()) {
            if (!part.getKey().equals(name) && refers(part.getValue(), name)) {
                uses++;
            }
        }
        return uses;
    }

    private static boolean refers(final MatchExpression match, final String name) {
        if (match instanceof MatchExpression.Pattern pattern) {
            return refers(pattern.node(), name);
        }
        if (match instanceof MatchExpression.Parts parts) {
            for (final MatchPart part : parts.parts()) {
                if (part instanceof MatchPart.Pattern pattern && refers(pattern.node(), name)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean refers(final PatternNode node, final String name) {
        if (PatternNodes.bare(node) instanceof PatternNode.Ref ref) {
            return ref.name().equals(name);
        }
        for (final PatternNode child : PatternNodes.children(node)) {
            if (refers(child, name)) {
                return true;
            }
        }
        return false;
    }

    /** The project with a part defined, or redefined, keeping its place. */
    public static Project define(final Project project, final String name, final PatternNode node) {
        final Map<String, PatternNode> patterns = new LinkedHashMap<>(project.patterns());
        patterns.put(name, node);
        return project.withPatterns(patterns);
    }

    /** The project without a part; the caller has checked nothing names it. */
    public static Project remove(final Project project, final String name) {
        final Map<String, PatternNode> patterns = new LinkedHashMap<>(project.patterns());
        patterns.remove(name);
        return project.withPatterns(patterns);
    }

    /** A part renamed, and every {@code ref} to it — in templates and in other parts — renamed with it. */
    public static Project rename(final Project project, final String from, final String to) {
        final UnaryOperator<PatternNode> renaming = node -> node instanceof PatternNode.Ref ref
                                                            && ref.name().equals(from)
                ? new PatternNode.Ref(to)
                : node;
        final Map<String, PatternNode> patterns = new LinkedHashMap<>();
        for (final Map.Entry<String, PatternNode> part : project.patterns().entrySet()) {
            patterns.put(part.getKey().equals(from)
                    ? to
                    : part.getKey(), map(part.getValue(), renaming));
        }
        final List<Template> templates = new ArrayList<>();
        for (final Template t : project.templates()) {
            templates.add(Templates.withMatch(t, map(t.match(), renaming)));
        }
        return project.withTemplates(templates).withPatterns(patterns);
    }

    /**
     * A node of a tree made a part: the part gets the node — less its label and cast, which
     * are the tree's use of it and stay — and the tree gets a {@code ref} in its place.
     *
     * @return the tree with the node replaced; the part is {@link #part(PatternNode)}
     */
    public static PatternNode extract(final PatternNode root, final int[] path, final String name) {
        final PatternNode node = PatternNodes.get(root, path);
        final PatternNode ref = node instanceof PatternNode.Labelled labelled
                ? new PatternNode.Labelled(new PatternNode.Ref(name), labelled.label(), labelled.as())
                : new PatternNode.Ref(name);
        return PatternNodes.replace(root, path, ref);
    }

    /** What extracting a node puts in the library: the node without the label the tree keeps. */
    public static PatternNode part(final PatternNode node) {
        return node instanceof PatternNode.Labelled labelled
                ? labelled.body()
                : node;
    }

    /**
     * A {@code ref} at a path replaced by the part it names, keeping the tree's label; the tree
     * unchanged for any other node, or for a standard-library name.
     */
    public static PatternNode inline(final PatternNode root,
                                     final int[] path,
                                     final Map<String, PatternNode> patterns) {
        final PatternNode node = PatternNodes.get(root, path);
        final PatternNode bare = PatternNodes.bare(node);
        if (!(bare instanceof PatternNode.Ref ref) || !patterns.containsKey(ref.name())) {
            return root;
        }
        final PatternNode part = patterns.get(ref.name());
        return PatternNodes.replace(root, path, node instanceof PatternNode.Labelled labelled
                ? new PatternNode.Labelled(part, labelled.label(), labelled.as())
                : part);
    }

    /** Every node of a tree through an operator, children first; a label's body is a node of its own. */
    public static PatternNode map(final PatternNode node, final UnaryOperator<PatternNode> operator) {
        if (node instanceof PatternNode.Labelled labelled) {
            final PatternNode body = map(labelled.body(), operator);
            return operator.apply(body == labelled.body()
                    ? node
                    : new PatternNode.Labelled(body, labelled.label(), labelled.as()));
        }
        final List<PatternNode> children = PatternNodes.children(node);
        if (children.isEmpty()) {
            return operator.apply(node);
        }
        final List<PatternNode> mapped = new ArrayList<>(children.size());
        boolean changed = false;
        for (final PatternNode child : children) {
            final PatternNode next = map(child, operator);
            changed |= next != child;
            mapped.add(next);
        }
        return operator.apply(changed
                ? PatternNodes.withChildren(node, mapped)
                : node);
    }

    private static MatchExpression map(final MatchExpression match, final UnaryOperator<PatternNode> operator) {
        if (match instanceof MatchExpression.Pattern pattern) {
            return new MatchExpression.Pattern(map(pattern.node(), operator));
        }
        if (match instanceof MatchExpression.Parts parts) {
            final List<MatchPart> mapped = new ArrayList<>(parts.parts().size());
            for (final MatchPart part : parts.parts()) {
                mapped.add(part instanceof MatchPart.Pattern pattern
                        ? new MatchPart.Pattern(map(pattern.node(), operator))
                        : part);
            }
            return new MatchExpression.Parts(mapped);
        }
        return match;
    }
}
