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
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.regex.BytePattern;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Progressive matching, tested directly.
 *
 * <p>The four progressive fixtures between them use seven of the twenty-four step kinds. Every
 * combinator, every text atom and both seeks have no fixture coverage at all, so they are tested
 * here — including, deliberately, the <b>absence</b> of backtracking, which is the property that
 * would silently change if these steps were ever lowered onto
 * {@code stroom.shapeshifter.regex.comb} and compiled into a real pattern.
 */
class StepsTest {

    /**
     * Compile and run, because the interpreter's input is compiled steps: what a tag's bytes are
     * and what a predicate's table says are the compiler's answers, and a test that wrote them
     * out by hand would agree with itself rather than with the engine.
     */
    private static MatchResult run(final List<MatchStep> steps, final String input) {
        return run(steps, input.getBytes(StandardCharsets.UTF_8), 0, Encoding.UTF_8);
    }

    private static MatchResult run(final List<MatchStep> steps,
                                   final byte[] data,
                                   final int from,
                                   final Encoding encoding) {
        final Decoding decoding = Decoding.of(encoding);
        return Steps.match(
                StepCompiler.compile(steps, "test", decoding,
                        key -> BytePattern.compile(key.text(), key.flags(), key.encoding())),
                data, from, data.length, decoding);
    }

    private static String group(final MatchResult result, final int index) {
        final TypedValue value = result.group(index);
        return value == null ? "" : new String(value.asBytes(), StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------------------
    // Atoms
    // -----------------------------------------------------------------------------------

    @Test
    void textAtomsConsumeWhatTheyMatch() {
        final MatchResult result = run(List.of(
                new MatchStep.Tag("id="),
                new MatchStep.TakeWhile(new Predicate.Numeric()),
                new MatchStep.Tag(","),
                new MatchStep.TakeUntil(";", false),
                new MatchStep.Tag(";"),
                new MatchStep.TakeN(3),
                new MatchStep.AnyChar()), "id=42,name;abcZ");

        assertThat(result).isNotNull();
        assertThat(group(result, 0)).isEqualTo("id=42,name;abcZ");
        assertThat(group(result, 2)).isEqualTo("42");
        assertThat(group(result, 4)).isEqualTo("name");
        assertThat(group(result, 6)).isEqualTo("abc");
        assertThat(group(result, 7)).isEqualTo("Z");
    }

    @Test
    void takeUntilCanIncludeItsTerminator() {
        assertThat(group(run(List.of(new MatchStep.TakeUntil("::", true)), "a::b"), 1))
                .isEqualTo("a::");
        assertThat(group(run(List.of(new MatchStep.TakeUntil("::", false)), "a::b"), 1))
                .isEqualTo("a");
        // No terminator is no match, rather than "all of it".
        assertThat(run(List.of(new MatchStep.TakeUntil("::", false)), "abc")).isNull();
    }

    @Test
    void predicatesClassifyCharactersUnderTheEffectiveEncoding() {
        // E5, ruled 2026-08-21: the predicate classifies characters, and a character is what
        // the encoding says it is. An accented letter is a letter under UTF-8 —
        assertThat(group(run(List.of(new MatchStep.TakeWhile(new Predicate.Alphabetic())), "abcé,"), 1))
                .isEqualTo("abcé");
        assertThat(group(run(List.of(new MatchStep.TakeWhile(new Predicate.NonWhitespace())), "ab cd"), 1))
                .isEqualTo("ab");
        assertThat(group(run(List.of(new MatchStep.TakeWhile(new Predicate.Custom(
                new Predicate.CharSet("[^,]", List.of(','), List.of(), true)))), "ab,c"), 1))
                .isEqualTo("ab");
    }

    @Test
    void singleByteEncodingsClassifyTheirOwnLetters() {
        // 0xE9 is é under windows-1252 and a meaningless byte under raw: the same bytes, two
        // encodings, two answers — both of them accurate (E5).
        final byte[] data = {'a', (byte) 0xE9, 'b', ','};
        final MatchResult latin = run(
                List.of(new MatchStep.TakeWhile(new Predicate.Alphabetic())),
                data, 0, Encoding.WINDOWS_1252);
        assertThat(latin.advance()).isEqualTo(3);

        final MatchResult raw = run(
                List.of(new MatchStep.TakeWhile(new Predicate.Alphabetic())),
                data, 0, Encoding.RAW);
        assertThat(raw.advance()).isEqualTo(1);
    }

    @Test
    void anyCharTakesAWholeCharacter() {
        // One character, not one byte — otherwise a multi-byte character comes out in pieces.
        assertThat(group(run(List.of(new MatchStep.AnyChar()), "é"), 1)).isEqualTo("é");
        assertThat(group(run(List.of(new MatchStep.AnyChar()), "😀"), 1)).isEqualTo("😀");
    }

    @Test
    void numbersReadInBothByteOrders() {
        final byte[] data = {0x01, 0x02, 0x02, 0x01, (byte) 0xFF, (byte) 0xFF};
        final MatchResult result = run(List.of(
                new MatchStep.ReadNumeric(NumericType.SHORT, false, Endianness.BIG),
                new MatchStep.ReadNumeric(NumericType.SHORT, false, Endianness.LITTLE),
                new MatchStep.ReadNumeric(NumericType.SHORT, true, Endianness.BIG)),
                data, 0, Encoding.UTF_8);

        assertThat(result.group(1)).isEqualTo(new TypedValue.Integer(0x0102));
        assertThat(result.group(2)).isEqualTo(new TypedValue.Integer(0x0102));
        // Signed, so all-ones is minus one rather than 65535.
        assertThat(result.group(3)).isEqualTo(new TypedValue.Integer(-1));
    }

    @Test
    void unsignedLongTooBigForALongBecomesText() {
        final byte[] data = {-1, -1, -1, -1, -1, -1, -1, -1};
        final MatchResult result = run(
                List.of(new MatchStep.ReadNumeric(NumericType.LONG, false, Endianness.BIG)),
                data, 0, Encoding.UTF_8);
        // The alternative is a silently negative number, which is worse than a string.
        assertThat(result.group(1).asString()).isEqualTo("18446744073709551615");
    }

    @Test
    void varintsReadSevenBitsAtATime() {
        final byte[] data = {(byte) 0xAC, 0x02, 0x03};
        final MatchResult result = run(List.of(
                new MatchStep.ReadVarint(), new MatchStep.ReadVarintZigZag()),
                data, 0, Encoding.UTF_8);
        assertThat(result.group(1)).isEqualTo(new TypedValue.Integer(300));
        // ZigZag: 3 encodes -2.
        assertThat(result.group(2)).isEqualTo(new TypedValue.Integer(-2));
        assertThat(result.advance()).isEqualTo(3);
    }

    @Test
    void seeksMoveTheCursorInBothDirections() {
        final MatchResult result = run(List.of(
                new MatchStep.Seek(new StepRef.Literal(3)),
                new MatchStep.Tell(),
                new MatchStep.TakeN(2),
                new MatchStep.SeekBack(new StepRef.Literal(5)),
                new MatchStep.TakeN(1),
                new MatchStep.SeekAbs(new StepRef.Literal(7))), "abcdefgh");

        assertThat(result.group(2)).isEqualTo(new TypedValue.Integer(3));
        assertThat(group(result, 3)).isEqualTo("de");
        assertThat(group(result, 5)).isEqualTo("a");
        // A rewind does not un-read: the match still consumes up to the furthest point reached.
        assertThat(result.advance()).isEqualTo(7);
    }

    @Test
    void codecsTransformWithoutConsuming() {
        final MatchResult result = run(List.of(
                new MatchStep.TakeUntil("|", false),
                new MatchStep.Decode(new StepRef.StepOutput(0), Codec.BASE64)), "aGVsbG8=|rest");
        assertThat(group(result, 2)).isEqualTo("hello");
        // The decode read nothing from the input, so the cursor is still where the take left it.
        assertThat(result.advance()).isEqualTo("aGVsbG8=".length());
    }

    @Test
    void takeBytesUsesAnEarlierStepsValue() {
        final byte[] data = {0x00, 0x03, 'a', 'b', 'c', 'd'};
        final MatchResult result = run(List.of(
                new MatchStep.ReadNumeric(NumericType.SHORT, false, Endianness.BIG),
                new MatchStep.TakeBytes(new StepRef.StepOutput(0))),
                data, 0, Encoding.UTF_8);
        assertThat(group(result, 2)).isEqualTo("abc");
        assertThat(result.advance()).isEqualTo(5);
    }

    // -----------------------------------------------------------------------------------
    // Combinators
    // -----------------------------------------------------------------------------------

    @Test
    void choiceTakesTheFirstAlternativeThatMatches() {
        final List<MatchStep> steps = List.of(new MatchStep.Choice(List.of(
                List.of(new MatchStep.Tag("ab"), new MatchStep.Tag("cd")),
                List.of(new MatchStep.Tag("ab")))));
        assertThat(group(run(steps, "abcd"), 1)).isEqualTo("abcd");
        assertThat(group(run(steps, "abzz"), 1)).isEqualTo("ab");
    }

    @Test
    void optionalAndRepeatAreGreedyAndDoNotGiveBack() {
        assertThat(group(run(List.of(new MatchStep.Optional(List.of(new MatchStep.Tag("x")))), "y"), 1))
                .isEmpty();
        assertThat(group(run(List.of(new MatchStep.Repeat(
                List.of(new MatchStep.Tag("ab")), 1, null)), "ababab"), 1)).isEqualTo("ababab");
        // Below the minimum is no match at all.
        assertThat(run(List.of(new MatchStep.Repeat(List.of(new MatchStep.Tag("ab")), 3, null)), "abab"))
                .isNull();
    }

    @Test
    void theRegexStepIsAnAtomAnchoredAtTheCursor() {
        // A pre-compiled fragment of grammar, usable beside Tag and TakeWhile — which means it
        // matches from the cursor and never skips (E4, ruled 2026-08-21). The pattern here
        // would be found four bytes in by a search; an atom must refuse instead.
        final byte[] data = "abcd42;".getBytes(StandardCharsets.UTF_8);
        assertThat(run(List.of(new MatchStep.Regex("[0-9]+", null)),
                data, 0, Encoding.UTF_8)).isNull();

        // At the cursor it consumes exactly its match, leaving the next step where it ended.
        final MatchResult result = run(List.of(
                        new MatchStep.Regex("[0-9]+", null),
                        new MatchStep.Tag(";")),
                data, 4, Encoding.UTF_8);
        assertThat(result).isNotNull();
        assertThat(result.advance()).isEqualTo(3);
    }

    @Test
    void thereIsNoBacktrackingBetweenSteps() {
        // A pattern would match this: the repeat would give back its last "ab" so the tag could
        // have it. These steps do not, and that difference is the whole reason they are
        // interpreted rather than lowered onto the regex library's combinators and compiled.
        assertThat(run(List.of(
                new MatchStep.Repeat(List.of(new MatchStep.Tag("ab")), 1, null),
                new MatchStep.Tag("ab")), "abab")).isNull();

        // The same shape, with the repeat bounded so it stops in the right place, does match.
        assertThat(run(List.of(
                new MatchStep.Repeat(List.of(new MatchStep.Tag("ab")), 1, 1),
                new MatchStep.Tag("ab")), "abab")).isNotNull();
    }

    @Test
    void lookaheadMatchesWithoutConsuming() {
        final MatchResult peek = run(List.of(
                new MatchStep.Peek(List.of(new MatchStep.Tag("ab"))),
                new MatchStep.TakeN(2)), "abc");
        assertThat(peek.advance()).isEqualTo(2);
        assertThat(group(peek, 1)).isEmpty();

        assertThat(run(List.of(new MatchStep.Peek(List.of(new MatchStep.Tag("zz")))), "abc")).isNull();
        assertThat(run(List.of(new MatchStep.Not(List.of(new MatchStep.Tag("zz")))), "abc")).isNotNull();
        assertThat(run(List.of(new MatchStep.Not(List.of(new MatchStep.Tag("ab")))), "abc")).isNull();
    }

    @Test
    void nestedStepsSeeEveryOutputProducedSoFarAtAnyDepth() {
        // The flat rule: output indexes count all step outputs in execution order, at any
        // nesting depth. Flat indexes here: 0 is the outer "3", 1 the outer ":", 2 the middle
        // sequence's "2", 3 its ":". The doubly nested steps read both the middle sequence's
        // own earlier output (index 2 -> take 2 bytes) and the outer sequence's (index 0 ->
        // take 3 bytes) — the second of which a depth-2 step could not see when the recursion
        // dropped its parent's outputs and handed down only the grandparent's.
        final MatchResult result = run(List.of(
                new MatchStep.TakeWhile(new Predicate.Numeric()),
                new MatchStep.Tag(":"),
                new MatchStep.Sequence(List.of(
                        new MatchStep.TakeWhile(new Predicate.Numeric()),
                        new MatchStep.Tag(":"),
                        new MatchStep.Sequence(List.of(
                                new MatchStep.TakeBytes(new StepRef.StepOutput(2)),
                                new MatchStep.TakeBytes(new StepRef.StepOutput(0))))))),
                "3:2:aabbbZ");

        assertThat(result).isNotNull();
        assertThat(group(result, 3)).isEqualTo("2:aabbb");
        assertThat(result.advance()).isEqualTo("3:2:aabbb".length());
    }

    @Test
    void sequenceIsOneStepFromTheOutside() {
        final MatchResult result = run(List.of(
                new MatchStep.Sequence(List.of(new MatchStep.Tag("a"), new MatchStep.Tag("b"))),
                new MatchStep.Tag("c")), "abc");
        assertThat(group(result, 1)).isEqualTo("ab");
        assertThat(group(result, 2)).isEqualTo("c");
    }

    @Test
    void failedStepFailsTheWholeMatch() {
        assertThat(run(List.of(new MatchStep.Tag("a"), new MatchStep.Tag("z")), "abc")).isNull();
        assertThat(run(List.of(new MatchStep.TakeN(99)), "abc")).isNull();
        assertThat(run(List.of(new MatchStep.Seek(new StepRef.Literal(99))), "abc")).isNull();
    }
}
