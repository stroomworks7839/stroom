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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * One step of a {@link MatchExpression.Progressive} match.
 *
 * <p>Steps split into <b>atoms</b>, which consume bytes and produce a value, and
 * <b>combinators</b>, which compose other steps. The two together are a small parser
 * combinator language, which is what structured and binary formats need and what a single regex
 * cannot express: a length prefix deciding the next read, a tagged union, a repeated block.
 *
 * <p>Each step's output is addressable by its index within its sequence, which is how
 * {@link StepRef.StepOutput} lets a later step use an earlier one's value.
 */
public sealed interface MatchStep {

    // -----------------------------------------------------------------------------------
    // Atoms
    // -----------------------------------------------------------------------------------

    /** Match an exact string. */
    record Tag(String value) implements MatchStep {

    }

    /** Match an exact sequence of bytes. */
    record MatchByte(byte[] value) implements MatchStep {

        public MatchByte {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof MatchByte matchByte
                   && Arrays.equals(value, matchByte.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "MatchByte[" + Arrays.toString(value) + "]";
        }
    }

    /** Consume while a character predicate holds. */
    record TakeWhile(Predicate predicate) implements MatchStep {

    }

    /**
     * Consume up to a pattern.
     *
     * @param pattern   the text to stop at
     * @param inclusive whether the pattern itself is consumed
     */
    record TakeUntil(String pattern, boolean inclusive) implements MatchStep {

    }

    /** Take exactly this many bytes, the count possibly coming from an earlier step. */
    record TakeBytes(StepRef count) implements MatchStep {

    }

    /** Take exactly this many characters, which is encoding-dependent. */
    record TakeN(int count) implements MatchStep {

        public TakeN {
            if (count < 0) {
                throw new ConfigException("A TakeN count cannot be negative: " + count);
            }
        }
    }

    /** Consume one character. */
    record AnyChar() implements MatchStep {

    }

    /**
     * Read a fixed-width number from binary data.
     *
     * @param numericType the width and kind
     * @param signed      whether to interpret the high bit as a sign
     * @param endian      the byte order; null means network order (big-endian), as it is on
     *                    the wire
     */
    record ReadNumeric(NumericType numericType, boolean signed, Endianness endian) implements MatchStep {

        public ReadNumeric {
            endian = endian == null ? Endianness.BIG : endian;
        }
    }

    /** Read an LEB128 unsigned varint. */
    record ReadVarint() implements MatchStep {

    }

    /** Read an LEB128 varint with zigzag decoding, as protobuf's {@code sint32} uses. */
    record ReadVarintZigZag() implements MatchStep {

    }

    /** Move the cursor forward without producing a value. */
    record Seek(StepRef count) implements MatchStep {

    }

    /** Move the cursor to an absolute offset. Needs the whole input to be addressable. */
    record SeekAbs(StepRef offset) implements MatchStep {

    }

    /** Move the cursor backwards. Needs the whole input to be addressable. */
    record SeekBack(StepRef count) implements MatchStep {

    }

    /** Produce the current byte offset as a value, without consuming anything. */
    record Tell() implements MatchStep {

    }

    /**
     * Decode an earlier step's output.
     *
     * @param data  the step whose output is the input to the codec
     * @param codec what to decode it with
     */
    record Decode(StepRef data, Codec codec) implements MatchStep {

    }

    /**
     * Encode an earlier step's output.
     *
     * @param data  the step whose output is the input to the codec
     * @param codec what to encode it with
     */
    record Encode(StepRef data, Codec codec) implements MatchStep {

    }

    /** A regex applied at the current position. */
    record Regex(String pattern, RegexFlags flags) implements MatchStep {

        public Regex {
            flags = flags == null ? RegexFlags.none() : flags;
        }
    }

    // -----------------------------------------------------------------------------------
    // Combinators
    // -----------------------------------------------------------------------------------

    /** Try each alternative in order; the first whole sequence that matches wins. */
    record Choice(List<List<MatchStep>> alternatives) implements MatchStep {

        public Choice {
            alternatives = alternatives == null
                    ? List.of()
                    : alternatives.stream().map(List::copyOf).toList();
        }
    }

    /** Match a sequence, or nothing. */
    record Optional(List<MatchStep> steps) implements MatchStep {

        public Optional {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /**
     * Match a sequence repeatedly.
     *
     * @param steps the sequence
     * @param min   the fewest repetitions that count as a match
     * @param max   the most to attempt, or null for unbounded
     */
    record Repeat(List<MatchStep> steps, int min, Integer max) implements MatchStep {

        public Repeat {
            steps = steps == null ? List.of() : List.copyOf(steps);
            if (min < 0) {
                throw new ConfigException("A Repeat minimum cannot be negative: " + min);
            }
            if (max != null && max < min) {
                throw new ConfigException(
                        "A Repeat maximum cannot be less than its minimum: " + max + " < " + min);
            }
        }
    }

    /** Group a sequence so it can be treated as one step. */
    record Sequence(List<MatchStep> steps) implements MatchStep {

        public Sequence {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** Use a named pattern from the project's library. */
    record PatternRef(UUID pattern) implements MatchStep {

    }

    /** Match without consuming — positive lookahead. */
    record Peek(List<MatchStep> steps) implements MatchStep {

        public Peek {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** Succeed only if the inner sequence fails — negative lookahead. */
    record Not(List<MatchStep> steps) implements MatchStep {

        public Not {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }
}
