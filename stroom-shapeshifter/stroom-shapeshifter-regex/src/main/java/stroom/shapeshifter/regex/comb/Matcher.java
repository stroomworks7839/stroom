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

package stroom.shapeshifter.regex.comb;

import stroom.shapeshifter.regex.Flag;

import java.util.List;
import java.util.Set;

/**
 * A composed matcher — a <em>description</em> of how to match, not a matching engine.
 * <p>
 * This is the deliberate difference from a parser-combinator library in a language with
 * monomorphisation, where composing parsers means composing functions and the compiler inlines
 * the whole tree. Java has no equivalent, and a tree of objects walked at match time would cost
 * an interface call per element at a call site that goes megamorphic — the very overhead this
 * engine exists to avoid.
 * <p>
 * So a composition is lowered into the same intermediate representation a regex produces, and
 * compiled by the same two tiers. A composition and an equivalent regex produce the
 * <b>same plan</b>, and therefore identical behaviour and identical speed: the choice between
 * them is a readability decision with nothing else riding on it.
 *
 * @see Matchers for the factory methods that build these
 */
public sealed interface Matcher {

    /** An exact literal. */
    record Tag(String text) implements Matcher {

    }

    /**
     * A run of characters drawn from a class, repeated between {@code min} and {@code max} times.
     * Covers {@code takeWhile}, {@code takeN} and {@code anyChar}.
     */
    record Characters(String classExpression, int min, int max) implements Matcher {

    }

    /** Consume up to, or through, the first occurrence of a character. */
    record Until(int codePoint, boolean inclusive) implements Matcher {

    }

    /** A whole regex as one element. Its own capture groups are preserved and renumbered. */
    record Regex(String pattern, Set<Flag> flags) implements Matcher {

    }

    /** All elements in order. */
    record Sequence(List<Matcher> items) implements Matcher {

    }

    /** Ordered alternatives — the first that matches wins. */
    record Choice(List<Matcher> alternatives) implements Matcher {

    }

    record Repeat(Matcher body, int min, int max, boolean greedy) implements Matcher {

        public static final int UNBOUNDED = Integer.MAX_VALUE;
    }

    /** Names an element so its span can be read back, exactly as a capture group. */
    record Labelled(Matcher body, String label) implements Matcher {

    }

    /** A reference to a named matcher, resolved and inlined at compile time. */
    record Ref(String name) implements Matcher {

    }

    /**
     * Names this element, making its matched span readable by that name.
     * <p>
     * Labels are the composition layer's capture groups, and compile to exactly that.
     */
    default Matcher label(final String name) {
        return new Labelled(this, name);
    }
}
