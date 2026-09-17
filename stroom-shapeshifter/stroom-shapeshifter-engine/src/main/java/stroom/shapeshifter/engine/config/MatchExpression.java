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
 * How a template tests the content in front of it.
 *
 * <p>A successful match always consumes bytes — that is what separates a match from a condition.
 * How many bytes is usually the whole match, but {@link Regex#advance()} can end the consumption
 * at a group boundary instead, which is how a pattern can look further than it eats.
 */
public sealed interface MatchExpression {

    /**
     * A regex, producing capture groups {@code $0}..{@code $N}.
     *
     * @param pattern the pattern, in {@link stroom.shapeshifter.regex.BytePattern}'s dialect
     * @param flags   case and dot-all handling
     * @param advance where the cursor lands after a match: 0 for the end of the whole match, or
     *                {@code N} for the end of group {@code N}. A group that did not participate
     *                in the match cannot place the cursor, so the match falls back to consuming
     *                to the end of the whole match
     */
    record Regex(String pattern, RegexFlags flags, int advance) implements MatchExpression {

        public Regex {
            flags = flags == null ? RegexFlags.none() : flags;
            if (advance < 0) {
                throw new ConfigException("A regex advance cannot be negative: " + advance);
            }
        }
    }

    /** A composition tree (design 38 §2), lowered onto the regex engine at compile time. */
    record Pattern(PatternNode node) implements MatchExpression {

    }

    /**
     * A match as a sequence of parts (design 38 §3b): patterns, and the three framing verbs a
     * binary format needs — take this many bytes, seek to here, read a value here (design 39,
     * D56) — with the amounts coming from labels already matched or from variables. Run in
     * order by the level; nothing backtracks across parts. A one-part sequence is a
     * {@link Pattern}.
     */
    record Parts(List<MatchPart> parts) implements MatchExpression {

        public Parts {
            parts = List.copyOf(parts);
            if (parts.isEmpty()) {
                throw new ConfigException("A match sequence needs at least one part");
            }
        }
    }

    /** One part of a match sequence. */
    sealed interface MatchPart {

        record Pattern(PatternNode node) implements MatchPart {

        }

        /** Consume {@code length} bytes as one group, reachable by {@code label} when it has one. */
        record Take(Length length, String label) implements MatchPart {

        }

        /**
         * Move the cursor by {@code length} bytes — or, when {@code absolute}, to {@code length}
         * from the start.
         */
        record Seek(Length length, boolean absolute) implements MatchPart {

        }

        /**
         * Read a value at the cursor: the cast says how many bytes and what they mean, the
         * value is bound to {@code label} when there is one, and the cursor moves past it
         * (design 39, D56). No pattern runs: a varint, a fixed-width number, a flag or a
         * position is byte arithmetic where it lies.
         */
        record Read(BinaryCast as, String label) implements MatchPart {

            public Read {
                if (as == null) {
                    throw new ConfigException("A read needs a cast: what the bytes at the cursor mean");
                }
            }
        }
    }

    /** Where a take's or a seek's amount comes from. */
    sealed interface Length {

        record Literal(int count) implements Length {

        }

        /** A label matched by an earlier part, read as an integer. */
        record Label(String label) implements Length {

        }

        /** A variable, read as an integer. */
        record Var(String name) implements Length {

        }
    }

    /**
     * A separator, with optional escaping and quoting — the CSV case, generalised.
     *
     * @param delimiter      the separator between fields
     * @param escape         a prefix that makes the next character literal, or null
     * @param containerStart opens a region in which the delimiter is literal, or null
     * @param containerEnd   closes that region, or null
     */
    record Delimiter(String delimiter,
                     String escape,
                     String containerStart,
                     String containerEnd) implements MatchExpression {

    }

    /**
     * The document itself, matched exactly once.
     *
     * <p>Its body is split at {@code apply-templates}: what comes before is written once at the
     * start of the stream, what comes after once at the end, and the {@code apply-templates}
     * itself becomes the loop over the input.
     */
    record Source() implements MatchExpression {

    }

    /** Consume everything handed to this template as group 0. Not valid at the root. */
    record All() implements MatchExpression {

    }

    /** Invocable only by name. Never a candidate for {@code apply-templates}. */
    record Named() implements MatchExpression {

    }
}
