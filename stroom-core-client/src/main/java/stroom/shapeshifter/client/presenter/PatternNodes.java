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

import stroom.shapeshifter.config.PatternNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The tree editor's operations over an immutable {@link PatternNode}: a node is addressed by
 * its path — child indexes from the root — and every operation returns a new root. A labelled
 * node is transparent to paths (its children are its body's) and keeps its label through every
 * rewrite of what is beneath it. A single-body container (optional, repeat, peek, not) whose
 * body must become several nodes gets a sequence for a body; one whose body is removed gets
 * {@code any}, so that the tree is always a tree the reader accepts.
 */
public final class PatternNodes {

    /** The stand-in body for a container that has to hold something: matches one byte. */
    public static final PatternNode PLACEHOLDER = new PatternNode.Take(1);

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

    /** The node without its label, if it has one. */
    public static PatternNode bare(final PatternNode node) {
        return node instanceof PatternNode.Labelled labelled
                ? labelled.body()
                : node;
    }

    /** Sequence or choice: holds any number of children. */
    public static boolean isList(final PatternNode node) {
        final PatternNode b = bare(node);
        return b instanceof PatternNode.Sequence || b instanceof PatternNode.Choice;
    }

    /** Any node with children: a list, or a single-body optional, repeat, peek or not. */
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

    /** The same node over new children; a leaf refuses. */
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

    /** One body from a child list: the child, a sequence of them, or the placeholder for none. */
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

    /** The tree without the node at the path; removing the root leaves nothing (null). */
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

    /** A node inserted among a container's children at an index; a leaf parent refuses. */
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

    /** A node moved among its siblings by an offset; the root, or an offset out of range, is a no-op. */
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

    /**
     * A container replaced by what it holds: a single body, or a list's one item. A label on the
     * container is dropped with the container. A leaf, or a list of several, cannot be unwrapped.
     */
    public static PatternNode unwrap(final PatternNode root, final int[] path) {
        final PatternNode node = get(root, path);
        final List<PatternNode> children = children(node);
        if (!isContainer(node) || children.size() != 1) {
            throw new IllegalArgumentException("Only a container holding one node can be unwrapped");
        }
        return replace(root, path, children.get(0));
    }
}
