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

package stroom.shapeshifter.engine.match;

import stroom.shapeshifter.engine.config.Codec;
import stroom.shapeshifter.engine.config.Endianness;
import stroom.shapeshifter.engine.config.MatchStep;
import stroom.shapeshifter.engine.config.NumericType;
import stroom.shapeshifter.engine.config.Predicate;
import stroom.shapeshifter.engine.config.StepRef;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;

import java.util.List;

/**
 * A step with everything it needs already worked out — the step vocabulary's answer to
 * {@code CompiledMatch}, and design 10 §2's remaining rows (design 29 §3.3, D51).
 *
 * <p>It lives beside the interpreter that runs it, not beside the compiler that builds it,
 * because the dependency runs that way: {@code compile} already reaches into {@code match} for
 * {@link PatternKey} and {@link Codecs}, and the reverse edge would be the package cycle design
 * 27 ruling 8 refused.
 *
 * <p>There is one kind here for all but one of {@link MatchStep}'s: {@code PatternRef} has no
 * compiled form, because {@code MatchCompiler} inlines the pattern it names and nothing is left
 * to run (D8). Most kinds are the authored step with nothing added, and exist so that the
 * interpreter switches over one sealed type rather than two. Five carry a precomputed answer:
 *
 * <ul>
 *   <li>{@link Tag} and {@link MatchByte} hold the bytes they look for <i>and</i> the value they
 *       produce, both constant once the encoding is known — a tag that matches allocates
 *       nothing.</li>
 *   <li>{@link TakeUntil} holds its needle encoded, rather than encoding the same text on every
 *       attempt.</li>
 *   <li>{@link Regex} holds its compiled pattern and its own matcher, so a step neither hashes a
 *       {@link PatternKey} to find the pattern nor allocates a matcher to use it.</li>
 *   <li>{@link TakeWhile} holds the byte table its predicate answers from, when the template's
 *       reading of bytes admits one — which turns its inner loop into an array read.</li>
 * </ul>
 *
 * <p>All of it is per template and per encoding, which is what makes it compilable: a template's
 * match encoding is settled before the graph is built. It can still be moved once afterwards, by
 * a byte-order mark re-declaring the source, which is why a progressive match compiles the two
 * readings a run can give it rather than one — see {@code CompiledMatch.Progressive}.
 */
public sealed interface CompiledStep {

    /**
     * Match an exact string, encoded under the template's encoding.
     *
     * @param bytes what to look for
     * @param value what to produce, which is the same bytes and so is built once
     */
    record Tag(byte[] bytes, TypedValue value) implements CompiledStep {

    }

    /**
     * Match an exact sequence of bytes.
     *
     * @param bytes what to look for
     * @param value what to produce, built once as {@link Tag}'s is
     */
    record MatchByte(byte[] bytes, TypedValue value) implements CompiledStep {

    }

    /**
     * Consume while a character predicate holds.
     *
     * @param predicate the class of character to take
     * @param table     whether each byte value stands alone and satisfies the predicate, or null
     *                  when no byte stands alone under the template's reading. Complete for a
     *                  single-byte reading; for UTF-8 it answers for the ASCII range only and
     *                  a lead byte still decodes.
     */
    record TakeWhile(Predicate predicate, boolean[] table) implements CompiledStep {

    }

    /**
     * Consume up to a literal, encoded under the template's encoding.
     *
     * @param needle    what to stop at
     * @param inclusive whether the needle is taken too
     */
    record TakeUntil(byte[] needle, boolean inclusive) implements CompiledStep {

    }

    /** Take exactly this many bytes, the count possibly coming from an earlier step. */
    record TakeBytes(StepRef count) implements CompiledStep {

    }

    /** Take exactly this many characters, which is encoding-dependent. */
    record TakeN(int count) implements CompiledStep {

    }

    /** Consume one character. */
    record AnyChar() implements CompiledStep {

    }

    /**
     * A regex applied at the current position, holding its pattern and its matcher.
     *
     * <p>The matcher is a field for the same reason a compiled match's is: the graph
     * owns its state (D35) and runs one execution at a time, and a step copies its bytes out of
     * the matcher before anything else runs. Each occurrence in a step tree gets its own node,
     * so two uses of one library pattern do not share a matcher.
     */
    final class Regex implements CompiledStep {

        private final BytePattern pattern;
        private final ByteMatcher matcher;

        public Regex(final BytePattern pattern) {
            this.pattern = pattern;
            this.matcher = pattern.matcher();
        }

        /** The compiled pattern. */
        public BytePattern pattern() {
            return pattern;
        }

        /** This step's matcher. */
        public ByteMatcher matcher() {
            return matcher;
        }
    }

    /** Read a fixed-width number. */
    record ReadNumeric(NumericType numericType, boolean signed, Endianness endian) implements CompiledStep {

    }

    /** Read an LEB128 unsigned varint. */
    record ReadVarint() implements CompiledStep {

    }

    /** Read an LEB128 varint with zigzag decoding. */
    record ReadVarintZigZag() implements CompiledStep {

    }

    /** Move the cursor forward without producing a value. */
    record Seek(StepRef count) implements CompiledStep {

    }

    /** Move the cursor to an absolute offset. */
    record SeekAbs(StepRef offset) implements CompiledStep {

    }

    /** Move the cursor backwards. */
    record SeekBack(StepRef count) implements CompiledStep {

    }

    /** Produce the current byte offset as a value, without consuming anything. */
    record Tell() implements CompiledStep {

    }

    /** Decode an earlier step's output through a codec. */
    record Decode(StepRef data, Codec codec) implements CompiledStep {

    }

    /** Encode an earlier step's output through a codec. */
    record Encode(StepRef data, Codec codec) implements CompiledStep {

    }

    /** Try each alternative in order; the first whole sequence that matches wins. */
    record Choice(List<List<CompiledStep>> alternatives) implements CompiledStep {

    }

    /** Match a sequence, or nothing. */
    record Optional(List<CompiledStep> steps) implements CompiledStep {

    }

    /** Match a sequence repeatedly, greedily, without giving anything back. */
    record Repeat(List<CompiledStep> steps, int min, Integer max) implements CompiledStep {

    }

    /** Group a sequence so it can be treated as one step. */
    record Sequence(List<CompiledStep> steps) implements CompiledStep {

    }

    /** Match without consuming — positive lookahead. */
    record Peek(List<CompiledStep> steps) implements CompiledStep {

    }

    /** Succeed only if the inner sequence fails — negative lookahead. */
    record Not(List<CompiledStep> steps) implements CompiledStep {

    }
}
