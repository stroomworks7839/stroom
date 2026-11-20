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

import stroom.shapeshifter.config.PatternNode;

import java.util.ArrayList;
import java.util.List;

/// The tree editor's operations over an immutable [PatternNode]: a node is addressed by
/// its path — child indexes from the root — and every operation returns a new root. A labelled
/// node is transparent to paths (its children are its body's) and keeps its label through every
/// rewrite of what is beneath it. A single-body container (optional, repeat, peek, not) whose
/// body must become several nodes gets a sequence for a body; one whose body is removed gets
/// `any`, so that the tree is always a tree the reader accepts.
public final class PatternNodes {

    /// The stand-in body for a container that has to hold something: matches one byte.
    public static final PatternNode PLACEHOLDER = new PatternNode.Take(1);

    // What every trial label starts with: letters only, as a group's name must start
    private static final String TRIAL_LABEL = "ssnode";

    private PatternNodes() {
    }

    public static String path(final int[] path) {
        final StringBuilder sb = new StringBuilder();
        for (final int index : path) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(index);
        }
        return sb.toString();
    }

    public static int[] path(final String text) {
        if (text == null || text.isEmpty()) {
            return new int[0];
        }
        final String[] parts = text.split("\\.");
        final int[] path = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            path[i] = Integer.parseInt(parts[i]);
        }
        return path;
    }

    public static int[] parent(final int[] path) {
        final int[] parent = new int[path.length - 1];
        System.arraycopy(path, 0, parent, 0, parent.length);
        return parent;
    }

    public static int[] child(final int[] path, final int index) {
        final int[] child = new int[path.length + 1];
        System.arraycopy(path, 0, child, 0, path.length);
        child[path.length] = index;
        return child;
    }

    /// The node without its label, if it has one.
    public static PatternNode bare(final PatternNode node) {
        return node instanceof PatternNode.Labelled labelled
                ? labelled.body()
                : node;
    }

    /// Sequence or choice: holds any number of children.
    public static boolean isList(final PatternNode node) {
        final PatternNode b = bare(node);
        return b instanceof PatternNode.Sequence || b instanceof PatternNode.Choice;
    }

    /// Any node with children: a list, or a single-body optional, repeat, peek or not.
    public static boolean isContainer(final PatternNode node) {
        final PatternNode b = bare(node);
        return isList(node)
               || b instanceof PatternNode.Optional
               || b instanceof PatternNode.Repeat
               || b instanceof PatternNode.Peek
               || b instanceof PatternNode.Not;
    }

    public static List<PatternNode> children(final PatternNode node) {
        return Templates.children(node);
    }

    /// The same node over new children; a leaf refuses.
    public static PatternNode withChildren(final PatternNode node, final List<PatternNode> children) {
        if (node instanceof PatternNode.Labelled labelled) {
            return new PatternNode.Labelled(withChildren(labelled.body(), children), labelled.label(), labelled.as());
        } else if (node instanceof PatternNode.Sequence) {
            return new PatternNode.Sequence(children);
        } else if (node instanceof PatternNode.Choice) {
            return new PatternNode.Choice(children);
        } else if (node instanceof PatternNode.Optional) {
            return new PatternNode.Optional(single(children));
        } else if (node instanceof PatternNode.Repeat repeat) {
            return new PatternNode.Repeat(single(children), repeat.min(), repeat.max(), repeat.greedy());
        } else if (node instanceof PatternNode.Peek) {
            return new PatternNode.Peek(single(children));
        } else if (node instanceof PatternNode.Not) {
            return new PatternNode.Not(single(children));
        }
        throw new IllegalArgumentException("A " + Templates.describe(node) + " has no children");
    }

    /// One body from a child list: the child, a sequence of them, or the placeholder for none.
    private static PatternNode single(final List<PatternNode> children) {
        if (children.isEmpty()) {
            return PLACEHOLDER;
        }
        return children.size() == 1
                ? children.get(0)
                : new PatternNode.Sequence(children);
    }

    public static PatternNode get(final PatternNode root, final int[] path) {
        PatternNode node = root;
        for (final int index : path) {
            final List<PatternNode> children = children(node);
            if (index < 0 || index >= children.size()) {
                return null;
            }
            node = children.get(index);
        }
        return node;
    }

    public static PatternNode replace(final PatternNode root, final int[] path, final PatternNode node) {
        return replace(root, path, 0, node);
    }

    private static PatternNode replace(final PatternNode at, final int[] path, final int depth,
                                       final PatternNode node) {
        if (depth == path.length) {
            return node;
        }
        final List<PatternNode> children = new ArrayList<>(children(at));
        children.set(path[depth], replace(children.get(path[depth]), path, depth + 1, node));
        return withChildren(at, children);
    }

    /// The tree without the node at the path; removing the root leaves nothing (null).
    public static PatternNode remove(final PatternNode root, final int[] path) {
        if (path.length == 0) {
            return null;
        }
        final int[] parentPath = parent(path);
        final PatternNode parent = get(root, parentPath);
        final List<PatternNode> children = new ArrayList<>(children(parent));
        children.remove(path[path.length - 1]);
        return replace(root, parentPath, withChildren(parent, children));
    }

    /// A node inserted among a container's children at an index; a leaf parent refuses.
    public static PatternNode insert(final PatternNode root, final int[] parentPath, final int index,
                                     final PatternNode node) {
        final PatternNode parent = get(root, parentPath);
        if (!isContainer(parent)) {
            throw new IllegalArgumentException("A " + Templates.describe(parent) + " holds no children;"
                                               + " add beside it instead");
        }
        final List<PatternNode> children = new ArrayList<>(children(parent));
        children.add(Math.max(0, Math.min(index, children.size())), node);
        return replace(root, parentPath, withChildren(parent, children));
    }

    /// A node moved among its siblings by an offset; the root, or an offset out of range, is a no-op.
    public static PatternNode move(final PatternNode root, final int[] path, final int by) {
        if (path.length == 0) {
            return root;
        }
        final int[] parentPath = parent(path);
        final PatternNode parent = get(root, parentPath);
        final List<PatternNode> children = new ArrayList<>(children(parent));
        final int from = path[path.length - 1];
        final int to = from + by;
        if (to < 0 || to >= children.size()) {
            return root;
        }
        children.add(to, children.remove(from));
        return replace(root, parentPath, withChildren(parent, children));
    }

    /// A container replaced by what it holds: a single body, or a list's one item. A label on the
    /// container is dropped with the container. A leaf, or a list of several, cannot be unwrapped.
    public static PatternNode unwrap(final PatternNode root, final int[] path) {
        final PatternNode node = get(root, path);
        final List<PatternNode> children = children(node);
        if (!isContainer(node) || children.size() != 1) {
            throw new IllegalArgumentException("Only a container holding one node can be unwrapped");
        }
        return replace(root, path, children.get(0));
    }

    /// The label the workbench's trial gives a node that has none, so that the engine reports the
    /// stretch it matched as a group (design 44 §4a, ruled 2026-11-20): its path, spelt as a name
    /// no author would choose. Never written into the project; only the trial carries it.
    ///
    /// @param path The node's path.
    /// @return The label.
    public static String trialLabel(final int[] path) {
        final StringBuilder sb = new StringBuilder(TRIAL_LABEL);
        for (int i = 0; i < path.length; i++) {
            if (i > 0) {
                sb.append('x');
            }
            sb.append(path[i]);
        }
        return sb.toString();
    }

    /// @param label A label the trial gave ([#trialLabel]).
    /// @return The path of the node it was given to.
    public static int[] pathOfTrialLabel(final String label) {
        final String digits = label.substring(TRIAL_LABEL.length());
        return digits.isEmpty()
                ? new int[0]
                : path(digits.replace('x', '.'));
    }

    /// @param label A group's name.
    /// @return Whether it is a label the trial gave ([#trialLabel]), not the author's: the stem
    ///         and a path, and nothing else, so an author's label that only starts the same way
    ///         is the author's.
    public static boolean isTrialLabel(final String label) {
        return label != null && label.matches(TRIAL_LABEL + "([0-9]+(x[0-9]+)*)?");
    }

    /// The label a node's stretch is reported under in the trial: its own if it has one, else
    /// its trial label; null for the root, whose stretch is the match's own, and for an
    /// unlabelled node in a peek or a not, which the trial leaves unlabelled ([#labelEvery]).
    ///
    /// @param root The tree.
    /// @param path The node's path.
    /// @param everyNode Whether the trial labelled every node: a template's tree is tried so, a
    ///                  library part's is tried through a ref to it, and reports only its own
    ///                  labels.
    /// @return The label, or null.
    public static String reportedLabel(final PatternNode root, final int[] path, final boolean everyNode) {
        if (path.length == 0) {
            return null;
        }
        PatternNode at = root;
        boolean inLookaround = false;
        for (final int index : path) {
            inLookaround = inLookaround || isLookaround(at);
            final List<PatternNode> children = children(at);
            if (index < 0 || index >= children.size()) {
                return null;
            }
            at = children.get(index);
        }
        if (at instanceof PatternNode.Labelled labelled) {
            return labelled.label();
        }
        return everyNode && !inLookaround
                ? trialLabel(path)
                : null;
    }

    /// The tree as the workbench tries it: every node below the root that has no label given its
    /// trial label ([#trialLabel]), so pointing at any node can mark what it matched. The root,
    /// and what sits in a peek or a not, are left as they are ([#reportedLabel]).
    ///
    /// @param root The tree.
    /// @return The tree, labelled.
    public static PatternNode labelEvery(final PatternNode root) {
        return labelEvery(root, new int[0]);
    }

    private static PatternNode labelEvery(final PatternNode node, final int[] path) {
        PatternNode next = node;
        if (!isLookaround(node)) {
            final List<PatternNode> children = children(node);
            if (!children.isEmpty()) {
                final List<PatternNode> labelled = new ArrayList<>(children.size());
                for (int i = 0; i < children.size(); i++) {
                    labelled.add(labelEvery(children.get(i), child(path, i)));
                }
                next = withChildren(node, labelled);
            }
        }
        return path.length == 0 || next instanceof PatternNode.Labelled
                ? next
                : new PatternNode.Labelled(next, trialLabel(path), null);
    }

    // A peek or a not: what it holds is looked at and not consumed, so labels in it report nothing
    private static boolean isLookaround(final PatternNode node) {
        final PatternNode b = bare(node);
        return b instanceof PatternNode.Peek || b instanceof PatternNode.Not;
    }

    /// The one text a node is, where it has only one to edit, as it is edited in place on the
    /// tree (ruled 2026-11-20): a tag's text and a take-until's terminator with their control
    /// characters spelt out (`\n`), a regex's pattern, a take-while's class. Null for a kind
    /// with more to say, or nothing, whose dialog edits it.
    ///
    /// @param node The node.
    /// @return Its text, or null.
    public static String inlineText(final PatternNode node) {
        final PatternNode b = bare(node);
        if (b instanceof PatternNode.Tag tag) {
            return ControlEscapes.escape(tag.text());
        } else if (b instanceof PatternNode.TakeUntil until) {
            return ControlEscapes.escape(until.terminator());
        } else if (b instanceof PatternNode.Regex regex) {
            return regex.pattern();
        } else if (b instanceof PatternNode.TakeWhile take) {
            return take.classExpression();
        }
        return null;
    }

    /// The node with its one text replaced ([#inlineText]): the rest of it, and its label, kept.
    ///
    /// @param node The node.
    /// @param text The text, as [#inlineText] spells it.
    /// @return The node, or the node unchanged for a kind with no one text.
    public static PatternNode withInlineText(final PatternNode node, final String text) {
        if (node instanceof PatternNode.Labelled labelled) {
            return new PatternNode.Labelled(withInlineText(labelled.body(), text), labelled.label(), labelled.as());
        } else if (node instanceof PatternNode.Tag) {
            return new PatternNode.Tag(ControlEscapes.unescape(text));
        } else if (node instanceof PatternNode.TakeUntil until) {
            return new PatternNode.TakeUntil(ControlEscapes.unescape(text), until.inclusive());
        } else if (node instanceof PatternNode.Regex regex) {
            return new PatternNode.Regex(text, regex.flags());
        } else if (node instanceof PatternNode.TakeWhile take) {
            return new PatternNode.TakeWhile(text, take.min(), take.max());
        }
        return node;
    }

    /// Nodes added after a selected one, as the add line under the tree adds them (ruled
    /// 2026-11-20): into a sequence that is selected, at its end; after any other node, beside it
    /// in its sequence; and where there is no sequence to be in, the node and the added become
    /// one in its place. A sequence added, unlabelled, is its items, so a fragment of several
    /// nodes lands as several.
    ///
    /// @param root The tree.
    /// @param path The selected node.
    /// @param added What was added.
    /// @return The tree, and the path of the last node added: the next is added after it.
    public static Placed addAfter(final PatternNode root, final int[] path, final PatternNode added) {
        final List<PatternNode> nodes = added instanceof PatternNode.Sequence sequence
                ? sequence.items()
                : List.of(added);
        final PatternNode at = get(root, path);
        if (bare(at) instanceof PatternNode.Sequence) {
            PatternNode next = root;
            final int start = children(at).size();
            for (int i = 0; i < nodes.size(); i++) {
                next = insert(next, path, start + i, nodes.get(i));
            }
            return new Placed(next, child(path, start + nodes.size() - 1));
        }
        if (path.length > 0 && bare(get(root, parent(path))) instanceof PatternNode.Sequence) {
            final int[] parentPath = parent(path);
            PatternNode next = root;
            final int start = path[path.length - 1] + 1;
            for (int i = 0; i < nodes.size(); i++) {
                next = insert(next, parentPath, start + i, nodes.get(i));
            }
            return new Placed(next, child(parentPath, start + nodes.size() - 1));
        }
        final List<PatternNode> together = new ArrayList<>();
        together.add(at);
        together.addAll(nodes);
        return new Placed(replace(root, path, new PatternNode.Sequence(together)), child(path, nodes.size()));
    }

    /// A tree after an addition, and where the last node added is in it.
    ///
    /// @param root The tree.
    /// @param last The last node added.
    public record Placed(PatternNode root, int[] last) {

    }
}
