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

package stroom.shapeshifter.engine.config;

import stroom.shapeshifter.engine.config.Template.RegexFlags;

import java.util.List;

/**
 * A match as a composition tree (design 38 §2): leaves are the atoms and a regex, interior nodes
 * the combinators, and any node may carry a label — a capture the body reaches by name — and,
 * on a labelled node, a {@link BinaryCast} saying what its bytes mean. The tree is what the UI
 * edits and the configuration stores; at compile time it lowers, node for node, onto the regex
 * library's own composition and from there to one plan on the tiered engines. Nothing is
 * interpreted at run time.
 *
 * <p>The names are the library's, in snake case, so the UI, the configuration and the compiler
 * share one vocabulary and a regex explodes into the tree without renaming (ruled 2026-09-16).
 */
public sealed interface PatternNode {

    /** Literal text, in the template's encoding. */
    record Tag(String text) implements PatternNode {

    }

    /** A run of characters in a class, {@code min} to {@code max} of them; {@link Repeat#UNBOUNDED} for no limit. */
    record TakeWhile(String classExpression, int min, int max) implements PatternNode {

    }

    /** Everything up to a terminator, which is consumed too when {@code inclusive}. */
    record TakeUntil(String terminator, boolean inclusive) implements PatternNode {

    }

    /** Exactly {@code count} characters — bytes, in a raw template — of anything. */
    record Take(int count) implements PatternNode {

    }

    /** A regex, as a leaf: what a tree of short named parts is built from. */
    record Regex(String pattern, RegexFlags flags) implements PatternNode {

        public Regex {
            flags = flags == null ? RegexFlags.none() : flags;
        }
    }

    /** A part the regex library's standard library defines, by name. */
    record Ref(String name) implements PatternNode {

    }

    record Sequence(List<PatternNode> items) implements PatternNode {

        public Sequence {
            items = List.copyOf(items);
        }
    }

    record Choice(List<PatternNode> alternatives) implements PatternNode {

        public Choice {
            alternatives = List.copyOf(alternatives);
        }
    }

    record Optional(PatternNode body) implements PatternNode {

    }

    record Repeat(PatternNode body, int min, int max, boolean greedy) implements PatternNode {

        public static final int UNBOUNDED = Integer.MAX_VALUE;
    }

    /** Lookahead: the body must match here and is not consumed. */
    record Peek(PatternNode body) implements PatternNode {

    }

    /** Negative lookahead: the body must not match here; nothing is consumed. */
    record Not(PatternNode body) implements PatternNode {

    }

    /**
     * A labelled node: a capture the body reaches by name, and what its bytes mean when
     * {@code as} is set. An empty labelled sequence with {@code as: position} is the old
     * {@code Tell}.
     */
    record Labelled(PatternNode body, String label, BinaryCast as) implements PatternNode {

    }
}
