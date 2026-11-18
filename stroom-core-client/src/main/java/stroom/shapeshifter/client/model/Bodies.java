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

import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.Attribute;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Element;
import stroom.shapeshifter.config.OutputNode.ForEach;
import stroom.shapeshifter.config.OutputNode.ForEachGroup;
import stroom.shapeshifter.config.OutputNode.Holder;
import stroom.shapeshifter.config.OutputNode.If;
import stroom.shapeshifter.config.OutputNode.Switch;
import stroom.shapeshifter.config.OutputNode.SwitchCase;
import stroom.shapeshifter.config.OutputNode.Variable;
import stroom.shapeshifter.config.OutputNode.WhenBranch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// The body editor's rewrites over an immutable body (design 43 §4.2). A card is addressed by a
/// **node path** — its index at the top level, then *(branch, index)* pairs into a
/// holder's `bodies()` — and a card list by a **list path**, the same with the final
/// index left off: `[]` is the body itself, `[i, b]` the b-th body of the holder at
/// i. Every operation returns a new body; a holder keeps its head — its test, its name, its
/// branches' conditions and values — through every rewrite of what its branches hold.
public final class Bodies {

    private Bodies() {
    }

    public static String path(final int[] path) {
        final StringBuilder sb = new StringBuilder();
        for (final int p : path) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(p);
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

    /// The list path a node path sits in: everything but its last index.
    public static int[] parent(final int[] nodePath) {
        final int[] parent = new int[nodePath.length - 1];
        System.arraycopy(nodePath, 0, parent, 0, parent.length);
        return parent;
    }

    /// A list path extended into a branch of the node at an index: `list + [index, branch]`.
    public static int[] branch(final int[] listPath, final int index, final int branch) {
        final int[] out = new int[listPath.length + 2];
        System.arraycopy(listPath, 0, out, 0, listPath.length);
        out[listPath.length] = index;
        out[listPath.length + 1] = branch;
        return out;
    }

    /// A list path extended to the node at an index.
    public static int[] child(final int[] listPath, final int index) {
        final int[] out = new int[listPath.length + 1];
        System.arraycopy(listPath, 0, out, 0, listPath.length);
        out[listPath.length] = index;
        return out;
    }

    /// The card list at a list path, or null when the path no longer exists.
    public static List<OutputNode> list(final List<OutputNode> body, final int[] listPath) {
        List<OutputNode> at = body;
        for (int i = 0; i + 1 < listPath.length; i += 2) {
            final int index = listPath[i];
            final int branch = listPath[i + 1];
            if (index < 0 || index >= at.size() || !(at.get(index) instanceof Holder holder)) {
                return null;
            }
            final List<List<OutputNode>> bodies = holder.bodies();
            if (branch < 0 || branch >= bodies.size()) {
                return null;
            }
            at = bodies.get(branch);
        }
        return at;
    }

    public static OutputNode get(final List<OutputNode> body, final int[] nodePath) {
        if (nodePath.length == 0) {
            return null;
        }
        final List<OutputNode> list = list(body, parent(nodePath));
        final int index = nodePath[nodePath.length - 1];
        return list == null || index < 0 || index >= list.size()
                ? null
                : list.get(index);
    }

    /// The body with the list at a list path replaced.
    public static List<OutputNode> replaceList(final List<OutputNode> body, final int[] listPath,
                                               final List<OutputNode> replacement) {
        return replaceList(body, listPath, 0, replacement);
    }

    private static List<OutputNode> replaceList(final List<OutputNode> at, final int[] listPath, final int depth,
                                                final List<OutputNode> replacement) {
        if (depth >= listPath.length) {
            return List.copyOf(replacement);
        }
        final int index = listPath[depth];
        final int branch = listPath[depth + 1];
        final Holder holder = (Holder) at.get(index);
        final List<List<OutputNode>> bodies = new ArrayList<>(holder.bodies());
        bodies.set(branch, replaceList(bodies.get(branch), listPath, depth + 2, replacement));
        final List<OutputNode> out = new ArrayList<>(at);
        out.set(index, withBodies(holder, bodies));
        return out;
    }

    public static List<OutputNode> replace(final List<OutputNode> body, final int[] nodePath, final OutputNode node) {
        final int[] listPath = parent(nodePath);
        final List<OutputNode> list = new ArrayList<>(list(body, listPath));
        list.set(nodePath[nodePath.length - 1], node);
        return replaceList(body, listPath, list);
    }

    public static List<OutputNode> remove(final List<OutputNode> body, final int[] nodePath) {
        final int[] listPath = parent(nodePath);
        final List<OutputNode> list = new ArrayList<>(list(body, listPath));
        list.remove(nodePath[nodePath.length - 1]);
        return replaceList(body, listPath, list);
    }

    public static List<OutputNode> insert(final List<OutputNode> body, final int[] listPath, final int index,
                                          final OutputNode node) {
        final List<OutputNode> list = new ArrayList<>(list(body, listPath));
        list.add(Math.max(0, Math.min(index, list.size())), node);
        return replaceList(body, listPath, list);
    }

    /// A card moved among its siblings; out of range is a no-op returning the same body.
    public static List<OutputNode> move(final List<OutputNode> body, final int[] nodePath, final int by) {
        final int[] listPath = parent(nodePath);
        final List<OutputNode> list = new ArrayList<>(list(body, listPath));
        final int from = nodePath[nodePath.length - 1];
        final int to = from + by;
        if (to < 0 || to >= list.size()) {
            return body;
        }
        list.add(to, list.remove(from));
        return replaceList(body, listPath, list);
    }

    /// A card dragged to a place: lifted from where it was and put down before `index` of
    /// the list at `toListPath`. Refused when the destination is inside the card itself —
    /// a holder cannot contain the thing it is being moved into, and the paths say so before any
    /// list is touched.
    ///
    /// Lifting shifts what follows it in the same list, so an index after the old position is
    /// one too many once the card is out. Judged on the paths rather than by searching afterwards,
    /// because two sibling cards can be equal and the wrong one would be found.
    public static List<OutputNode> moveTo(final List<OutputNode> body, final int[] fromPath,
                                          final int[] toListPath, final int index) {
        if (isWithin(toListPath, fromPath)) {
            return body;
        }
        final OutputNode node = get(body, fromPath);
        if (node == null) {
            return body;
        }
        final int[] fromList = parent(fromPath);
        final int from = fromPath[fromPath.length - 1];
        // Lifting the card renumbers everything after it in its own list, and the destination is
        // addressed by number. So both the list to drop into and the place in it are read in the
        // numbering that will exist once the card is out, not the one on screen. Missing this
        // dropped a card into a list that had shifted out from under the path — into the wrong
        // holder, or off the end of the body altogether.
        final int[] to = shiftForRemoval(toListPath, fromList, from);
        int at = index;
        if (Arrays.equals(fromList, toListPath) && index > from) {
            at--;
        }
        final List<OutputNode> lifted = remove(body, fromPath);
        return insert(lifted, to, at, node);
    }

    /// A path as it reads once the node at `from` of `fromList` has been lifted out:
    /// a path that descends through that list past that position loses one.
    private static int[] shiftForRemoval(final int[] path, final int[] fromList, final int from) {
        if (path.length <= fromList.length || !isWithin(path, fromList)) {
            return path;
        }
        // Arrays.copyOf, not clone: GWT emulates the one and not the other.
        final int[] shifted = Arrays.copyOf(path, path.length);
        if (shifted[fromList.length] > from) {
            shifted[fromList.length]--;
        }
        return shifted;
    }

    /// A path as it reads once a node has been put in at `index` of the list at `listPath`:
    /// a path that descends through that list at or past that position gains one. The card
    /// editor holds a new card as a draft (design 47 D), and taking the draft moves whatever
    /// the author has just pointed at further down the same list.
    ///
    /// @param path A node path or a list path.
    /// @param listPath The list the node went into.
    /// @param index Where in it.
    /// @return The path as it reads afterwards; the same array when it is not moved.
    public static int[] shiftForInsert(final int[] path, final int[] listPath, final int index) {
        if (path.length <= listPath.length || !isWithin(path, listPath)) {
            return path;
        }
        if (path[listPath.length] < index) {
            return path;
        }
        // Arrays.copyOf, not clone: GWT emulates the one and not the other.
        final int[] shifted = Arrays.copyOf(path, path.length);
        shifted[listPath.length]++;
        return shifted;
    }

    /// How many instructions a card's branches hold between them: what goes with the card if
    /// it is removed, or changed to a kind with no place for them.
    ///
    /// @param node The card.
    /// @return The number its branches hold between them; 0 for a card that holds none.
    public static int held(final OutputNode node) {
        int held = 0;
        if (node instanceof Holder holder) {
            for (final List<OutputNode> branch : holder.bodies()) {
                held += branch.size();
            }
        }
        return held;
    }

    /// Whether a path runs through a node — its own path, or any list inside it.
    private static boolean isWithin(final int[] path, final int[] nodePath) {
        if (path.length < nodePath.length) {
            return false;
        }
        for (int i = 0; i < nodePath.length; i++) {
            if (path[i] != nodePath[i]) {
                return false;
            }
        }
        return true;
    }

    /// The same holder over new branch bodies, in `bodies()` order; its head is kept.
    public static OutputNode withBodies(final Holder holder, final List<List<OutputNode>> bodies) {
        if (holder instanceof If i) {
            return new If(i.test(), bodies.get(0));
        } else if (holder instanceof Choose c) {
            final List<WhenBranch> when = new ArrayList<>();
            for (int b = 0; b < c.when().size(); b++) {
                when.add(new WhenBranch(c.when().get(b).test(), bodies.get(b)));
            }
            return new Choose(when, bodies.get(c.when().size()));
        } else if (holder instanceof Switch s) {
            final List<SwitchCase> cases = new ArrayList<>();
            for (int b = 0; b < s.cases().size(); b++) {
                cases.add(new SwitchCase(s.cases().get(b).value(), bodies.get(b)));
            }
            return new Switch(s.select(), cases, bodies.get(s.cases().size()));
        } else if (holder instanceof Variable v) {
            return new Variable(v.name(), bodies.get(0));
        } else if (holder instanceof Element e) {
            return new Element(e.name(), e.namespace(), e.omitIfEmpty(), bodies.get(0));
        } else if (holder instanceof Attribute a) {
            return new Attribute(a.name(), a.omitIfEmpty(), bodies.get(0));
        } else if (holder instanceof ForEach fe) {
            return new ForEach(fe.select(), fe.as(), fe.asKey(), fe.sort(), bodies.get(0));
        } else if (holder instanceof ForEachGroup fg) {
            return new ForEachGroup(fg.select(), fg.groupBy(), bodies.get(0));
        }
        throw new IllegalArgumentException("Unknown holder: " + holder);
    }

    /// What heads a holder's branch — "then", "when …", "otherwise", "case …", "default" — or null.
    public static String branchLabel(final Holder holder, final int branch) {
        if (holder instanceof If) {
            return "then";
        } else if (holder instanceof Choose c) {
            return branch < c.when().size()
                    ? "when " + GuardClause.describe(c.when().get(branch).test())
                    : "otherwise";
        } else if (holder instanceof Switch s) {
            return branch < s.cases().size()
                    ? "case " + s.cases().get(branch).value()
                    : "default";
        }
        return null;
    }

    /// Whether a branch has a head of its own to edit or remove: a when or a case, not otherwise or default.
    public static boolean branchIsOwn(final Holder holder, final int branch) {
        return holder instanceof Choose c && branch < c.when().size()
               || holder instanceof Switch s && branch < s.cases().size();
    }

    public static Choose withWhen(final Choose c, final int branch, final Condition test) {
        final List<WhenBranch> when = new ArrayList<>(c.when());
        when.set(branch, new WhenBranch(test, when.get(branch).body()));
        return new Choose(when, c.otherwise());
    }

    public static Choose addWhen(final Choose c, final Condition test) {
        final List<WhenBranch> when = new ArrayList<>(c.when());
        when.add(new WhenBranch(test, List.of()));
        return new Choose(when, c.otherwise());
    }

    public static Choose removeWhen(final Choose c, final int branch) {
        final List<WhenBranch> when = new ArrayList<>(c.when());
        when.remove(branch);
        return new Choose(when, c.otherwise());
    }

    public static Switch withCase(final Switch s, final int branch, final String value) {
        final List<SwitchCase> cases = new ArrayList<>(s.cases());
        cases.set(branch, new SwitchCase(value, cases.get(branch).body()));
        return new Switch(s.select(), cases, s.defaultBody());
    }

    public static Switch addCase(final Switch s, final String value) {
        final List<SwitchCase> cases = new ArrayList<>(s.cases());
        cases.add(new SwitchCase(value, List.of()));
        return new Switch(s.select(), cases, s.defaultBody());
    }

    public static Switch removeCase(final Switch s, final int branch) {
        final List<SwitchCase> cases = new ArrayList<>(s.cases());
        cases.remove(branch);
        return new Switch(s.select(), cases, s.defaultBody());
    }
}
