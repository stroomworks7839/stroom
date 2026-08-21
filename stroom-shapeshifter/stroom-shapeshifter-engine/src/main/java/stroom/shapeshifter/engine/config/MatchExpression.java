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
     * A sequence of steps, each consuming where the last one stopped.
     *
     * <p>This is the structured and binary case: later steps can refer to what earlier ones
     * produced, so a length field can decide how many bytes the next step takes.
     *
     * @param steps the steps, in order
     */
    record Progressive(List<MatchStep> steps) implements MatchExpression {

        public Progressive {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
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

    /**
     * An Avro object container file, each record's fields becoming match groups.
     *
     * @param schema the writer schema as JSON, or null to use the one embedded in the file
     */
    record Avro(String schema) implements MatchExpression {

    }

    /**
     * A Parquet file, each row becoming a match and each column a group.
     *
     * @param columns the columns to decode, or empty for all of them
     */
    record Parquet(List<String> columns) implements MatchExpression {

        public Parquet {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    /**
     * A protobuf message, decoded dynamically against a descriptor set.
     *
     * @param descriptorPath the {@code FileDescriptorSet} produced by {@code protoc}
     * @param messageType    the fully-qualified message name to decode as
     */
    record Protobuf(String descriptorPath, String messageType) implements MatchExpression {

    }
}
