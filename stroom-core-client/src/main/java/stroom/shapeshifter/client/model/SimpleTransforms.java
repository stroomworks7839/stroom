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

import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.Add;
import stroom.shapeshifter.config.OutputNode.Divide;
import stroom.shapeshifter.config.OutputNode.LowerCase;
import stroom.shapeshifter.config.OutputNode.NormalizeSpace;
import stroom.shapeshifter.config.OutputNode.Round;
import stroom.shapeshifter.config.OutputNode.StringLength;
import stroom.shapeshifter.config.OutputNode.Trim;
import stroom.shapeshifter.config.OutputNode.UpperCase;
import stroom.shapeshifter.config.RefExpression;

import java.util.List;

/// The transforms that are one shape: what they read, and what the result is called. Eight of
/// them — `trim`, `lower-case`, `upper-case`, `normalize-space`, `add`, `divide`, `round`,
/// `string-length` — differ only in the operation, so one form serves them all and this is where
/// the kind is read off and put back (design 46 §8.2).
///
/// <p>The name is optional in every one of them, and that is not a detail: 42 of the corpus's 55
/// trims have no name, because a transform without one writes its result straight out instead of
/// binding it. A form that insisted on a name would leave those on the wire form, which is the
/// opposite of the point.
public final class SimpleTransforms {

    private SimpleTransforms() {
    }

    /// Whether a node is one of the eight.
    ///
    /// @param node The node.
    /// @return True where one form can edit it.
    public static boolean isSimple(final OutputNode node) {
        return node instanceof Trim
               || node instanceof LowerCase
               || node instanceof UpperCase
               || node instanceof NormalizeSpace
               || node instanceof Add
               || node instanceof Divide
               || node instanceof Round
               || node instanceof StringLength;
    }

    /// What the transform reads.
    ///
    /// @param node One of the eight.
    /// @return Its selects, in order.
    public static List<RefExpression> selectsOf(final OutputNode node) {
        if (node instanceof Trim t) {
            return t.select();
        } else if (node instanceof LowerCase l) {
            return l.select();
        } else if (node instanceof UpperCase u) {
            return u.select();
        } else if (node instanceof NormalizeSpace n) {
            return n.select();
        } else if (node instanceof Add a) {
            return a.select();
        } else if (node instanceof Divide d) {
            return d.select();
        } else if (node instanceof Round r) {
            return r.select();
        } else if (node instanceof StringLength s) {
            return s.select();
        }
        return List.of();
    }

    /// What the result is called, or null where it is written straight out.
    ///
    /// @param node One of the eight.
    /// @return The name, or null.
    public static String nameOf(final OutputNode node) {
        if (node instanceof Trim t) {
            return t.name();
        } else if (node instanceof LowerCase l) {
            return l.name();
        } else if (node instanceof UpperCase u) {
            return u.name();
        } else if (node instanceof NormalizeSpace n) {
            return n.name();
        } else if (node instanceof Add a) {
            return a.name();
        } else if (node instanceof Divide d) {
            return d.name();
        } else if (node instanceof Round r) {
            return r.name();
        } else if (node instanceof StringLength s) {
            return s.name();
        }
        return null;
    }

    /// The node a kind, its selects and its name mean.
    ///
    /// @param kind    One of the eight kinds.
    /// @param selects What it reads.
    /// @param name    What the result is called, or null to write it straight out.
    /// @return The node.
    public static OutputNode build(final String kind, final List<RefExpression> selects,
                                   final String name) {
        switch (kind) {
            case "trim":
                return new Trim(selects, name);
            case "lower-case":
                return new LowerCase(selects, name);
            case "upper-case":
                return new UpperCase(selects, name);
            case "normalize-space":
                return new NormalizeSpace(selects, name);
            case "add":
                return new Add(selects, name);
            case "divide":
                return new Divide(selects, name);
            case "round":
                return new Round(selects, name);
            case "string-length":
                return new StringLength(selects, name);
            default:
                throw new IllegalArgumentException("Not a one-shape transform: " + kind);
        }
    }

    /// How many selects a kind takes (design/17 §5, and the arities `BodyCompiler` enforces).
    /// The form is shaped by this: a kind that reads one thing gets a field, a kind that reads
    /// two gets two, and only a fold gets a list. Saving the wrong number is a configuration
    /// that will not compile, so the form cannot offer it.
    ///
    /// @param kind The kind.
    /// @return Its arity.
    public static Arity arity(final String kind) {
        switch (kind) {
            case "add":
                return Arity.FOLD;
            case "divide":
                return Arity.TWO;
            default:
                return Arity.ONE;
        }
    }

    /// Whether a node holds as many selects as its kind takes.
    ///
    /// @param node One of the eight.
    /// @return True where the engine would accept it.
    public static boolean arityHolds(final OutputNode node) {
        final int selects = selectsOf(node).size();
        switch (arity(Instructions.kind(node))) {
            case ONE:
                return selects == 1;
            case TWO:
                return selects == 2;
            default:
                return selects >= 1;
        }
    }

    /// How many selects a transform reads.
    public enum Arity {
        /// Exactly one, as `trim` and `round` do.
        ONE,
        /// Exactly two, `a op b`, as `divide` does.
        TWO,
        /// One or more, folded in order, as `add` does.
        FOLD
    }
}
