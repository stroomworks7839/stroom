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

import java.util.List;

/**
 * A test on a single character, used by {@link MatchStep.TakeWhile}.
 *
 * <p>Six named predicates cover the common cases; anything else is a {@link Custom} character
 * set written in a small bracket language, {@code [a-zA-Z0-9_.-]}, deliberately close enough to
 * regex character-class syntax to read without explanation.
 */
public sealed interface Predicate {

    /** A letter. */
    record Alphabetic() implements Predicate {

    }

    /** A letter or a digit. */
    record Alphanumeric() implements Predicate {

    }

    /** A digit. */
    record Numeric() implements Predicate {

    }

    /** Whitespace. */
    record Whitespace() implements Predicate {

    }

    /** Anything that is not whitespace. */
    record NonWhitespace() implements Predicate {

    }

    /** Any character at all. */
    record Any() implements Predicate {

    }

    /**
     * An explicit set of characters.
     *
     * @param charSet the set, parsed from its bracket expression
     */
    record Custom(CharSet charSet) implements Predicate {

    }

    /**
     * A set of characters, as written and as parsed.
     *
     * <p>The original {@code expression} is kept alongside the parsed form because it is what a
     * person wrote and what any error message should quote back at them.
     *
     * @param expression the bracket expression it was parsed from
     * @param chars      individual characters in the set
     * @param ranges     inclusive ranges in the set
     * @param negated    whether the set is everything <i>except</i> its members
     */
    record CharSet(String expression, List<Character> chars, List<Range> ranges, boolean negated) {

        public CharSet {
            chars = chars == null ? List.of() : List.copyOf(chars);
            ranges = ranges == null ? List.of() : List.copyOf(ranges);
        }

        /**
         * An inclusive range of characters.
         *
         * @param from the first character in the range
         * @param to   the last character in the range
         */
        public record Range(char from, char to) {

        }
    }
}
