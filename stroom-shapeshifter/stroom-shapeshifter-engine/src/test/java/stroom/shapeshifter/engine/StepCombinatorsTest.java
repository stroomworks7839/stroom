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

package stroom.shapeshifter.engine;

import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.output.XmlByteSink;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nesting step combinators: {@code Choice}, {@code Optional}, {@code Repeat},
 * {@code Sequence}, {@code Peek} and {@code Not}.
 *
 * <p><b>Written because nothing executed them.</b> Five fixtures exercise progressive matching
 * and cover fourteen of the twenty-four step kinds; not one of them nests, so
 * {@code Steps.sequence} — the method all six go through — was never called by any test, and
 * neither benchmark row reaches it. That is untested engine behaviour in its own right, and it is
 * also what design 34 proposes to rewrite, so it is pinned first (design 34 §5).
 *
 * <p>Each test asserts two things where it can: what the combinator <i>matches</i>, and what its
 * nested steps leave behind. A nested sequence contributes one output — the span it consumed —
 * and its own steps' outputs do not escape it, which is the invariant a buffer with a mark would
 * have to preserve.
 */
class StepCombinatorsTest {

    /** One template whose match is the given steps, writing each output it kept. */
    private static String run(final String steps, final String input) {
        final String json = """
                {
                  "name": "steps", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                     "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "rec"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "rec", "mode": "rec",
                     "match": {"progressive": [STEPS]},
                     "body": [{"value-of": {"parts": [{"text": "["},
                                                      {"capture": {"group": 0}},
                                                      {"text": "]"}]}}]}
                  ]
                }
                """.replace("STEPS", steps);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(out));
        return out.toString(StandardCharsets.UTF_8).replace("\n", "");
    }

    // -----------------------------------------------------------------------------------
    // Choice
    // -----------------------------------------------------------------------------------

    @Test
    void choiceTakesTheFirstAlternativeThatMatchesWhole() {
        final String steps = """
                {"Choice": [[{"Tag": "ab"}, {"Tag": "cd"}], [{"Tag": "ab"}, {"Tag": "xy"}]]}""";
        assertThat(run(steps, "abcd")).isEqualTo("[abcd]");
        assertThat(run(steps, "abxy")).isEqualTo("[abxy]");
    }

    /**
     * A losing alternative consumes nothing and leaves nothing: the second alternative must see
     * the input from the start, not from where the first gave up.
     */
    @Test
    void failedAlternativeLeavesNothingBehind() {
        final String steps = """
                {"Choice": [[{"Tag": "aa"}, {"Tag": "zz"}], [{"Tag": "aa"}, {"Tag": "bb"}]]}""";
        assertThat(run(steps, "aabb")).isEqualTo("[aabb]");
    }

    @Test
    void choiceWithNoMatchingAlternativeDoesNotMatch() {
        final String steps = """
                {"Choice": [[{"Tag": "xx"}], [{"Tag": "yy"}]]}""";
        assertThat(run(steps, "zz")).isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // Optional, Sequence
    // -----------------------------------------------------------------------------------

    @Test
    void optionalConsumesWhatItCanAndSucceedsRegardless() {
        final String steps = """
                {"Optional": [{"Tag": "<<"}]}, {"Tag": "body"}""";
        assertThat(run(steps, "<<body")).isEqualTo("[<<body]");
        assertThat(run(steps, "body")).isEqualTo("[body]");
    }

    @Test
    void sequenceGroupsItsStepsAndFailsIfAnyOfThemDoes() {
        assertThat(run("""
                {"Sequence": [{"Tag": "a"}, {"Tag": "b"}]}, {"Tag": "c"}""", "abc"))
                .isEqualTo("[abc]");
        assertThat(run("""
                {"Sequence": [{"Tag": "a"}, {"Tag": "z"}]}, {"Tag": "c"}""", "abc"))
                .isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // Repeat
    // -----------------------------------------------------------------------------------

    @Test
    void repeatTakesAsManyAsItCan() {
        assertThat(run("""
                {"Repeat": {"steps": [{"Tag": "ab"}], "min": 1, "max": null}}""", "ababab"))
                .isEqualTo("[ababab]");
    }

    /** The maximum binds the repeat; what is left over is the level's next match, not this one's. */
    @Test
    void repeatStopsAtItsMaximum() {
        assertThat(run("""
                {"Repeat": {"steps": [{"Tag": "ab"}], "min": 1, "max": 2}}""", "ababab"))
                .isEqualTo("[abab][ab]");
    }

    @Test
    void repeatBelowItsMinimumDoesNotMatch() {
        assertThat(run("""
                {"Repeat": {"steps": [{"Tag": "ab"}], "min": 3, "max": null}}""", "abab"))
                .isEmpty();
    }

    /** Each iteration starts where the last one ended, and none sees the last one's outputs. */
    @Test
    void repeatRunsItsStepsOncePerIteration() {
        assertThat(run("""
                {"Repeat": {"steps": [{"Tag": "["}, {"TakeWhile": "Numeric"}, {"Tag": "]"}],
                            "min": 1, "max": null}}""", "[1][22][333]"))
                .isEqualTo("[[1][22][333]]");
    }

    // -----------------------------------------------------------------------------------
    // Peek and Not
    // -----------------------------------------------------------------------------------

    @Test
    void peekMatchesWithoutConsuming() {
        assertThat(run("""
                {"Peek": [{"Tag": "ab"}]}, {"Tag": "abc"}""", "abc")).isEqualTo("[abc]");
    }

    @Test
    void peekThatDoesNotMatchFailsTheWholeMatch() {
        assertThat(run("""
                {"Peek": [{"Tag": "zz"}]}, {"Tag": "abc"}""", "abc")).isEmpty();
    }

    @Test
    void notSucceedsWhenItsStepsDoNotMatchAndConsumesNothing() {
        assertThat(run("""
                {"Not": [{"Tag": "zz"}]}, {"Tag": "abc"}""", "abc")).isEqualTo("[abc]");
    }

    @Test
    void notFailsWhenItsStepsDoMatch() {
        assertThat(run("""
                {"Not": [{"Tag": "ab"}]}, {"Tag": "abc"}""", "abc")).isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // Nesting, and what a nested sequence leaves behind
    // -----------------------------------------------------------------------------------

    /**
     * A step reference indexes the outputs produced so far. A nested combinator contributes one
     * output — its span — and its own steps' outputs do not escape it, so the index after a
     * nested sequence counts the sequence itself and not its parts.
     */
    @Test
    void nestedSequenceIsOneOutputAndItsPartsAreNotIndexable() {
        // Output 0 is the sequence's span "xy"; output 1 is the TakeWhile's "3"; the TakeBytes
        // reads output 1 and so takes three bytes.
        //
        // The discriminator: if the sequence's own steps escaped into the index space, output 0
        // would be "x" and output 1 "y", and "y" is not a count — so the match would fail rather
        // than take "abc".
        final String steps = """
                {"Sequence": [{"Tag": "x"}, {"Tag": "y"}]},
                {"TakeWhile": "Numeric"},
                {"TakeBytes": {"StepOutput": 1}}""";
        assertThat(run(steps, "xy3abc")).isEqualTo("[xy3abc]");
    }

    /**
     * A step <i>inside</i> a nested combinator can read an output produced <i>before</i> that
     * combinator started.
     *
     * <p>This is the whole purpose of {@code concat(enclosing, callerLocal)}: the nested steps see
     * the enclosing sequences' outputs <b>and</b> the caller's own, as one flat index space.
     * Breaking it so the inner sees only the enclosing outputs passed every other test in this
     * file, which is why this one exists — design 34 replaces that concatenation with a buffer
     * and must preserve exactly this.
     */
    @Test
    void stepInsideANestedSequenceSeesOutputsFromBeforeIt() {
        // Output 0 is "3", produced before the sequence opens; the TakeBytes inside the sequence
        // reads it and takes three bytes.
        final String steps = """
                {"TakeWhile": "Numeric"},
                {"Sequence": [{"Tag": "x"}, {"TakeBytes": {"StepOutput": 0}}]}""";
        assertThat(run(steps, "3xabc")).isEqualTo("[3xabc]");
    }

    /**
     * And the case that {@code concat} exists for: a step inside a nested combinator reading an
     * output produced by an <b>earlier step of the combinator that encloses it</b>.
     *
     * <p>{@code Steps.match} passes an empty {@code callerLocal}, so at the top level
     * {@code concat(enclosing, callerLocal)} returns the enclosing outputs unchanged and the
     * concatenation is never exercised. It takes <b>two</b> levels: the outer sequence produces
     * "3", the inner sequence then reads it. Breaking {@code concat} so it returns only the
     * enclosing outputs passes every other test here, including the one above.
     */
    @Test
    void stepInsideTwoCombinatorsSeesTheOuterOnesEarlierOutput() {
        final String steps = """
                {"Sequence": [
                    {"TakeWhile": "Numeric"},
                    {"Sequence": [{"Tag": "x"}, {"TakeBytes": {"StepOutput": 0}}]}]}""";
        assertThat(run(steps, "3xabc")).isEqualTo("[3xabc]");
    }

    /**
     * A <b>failed</b> alternative's outputs do not survive into the index space of what runs
     * after it.
     *
     * <p>The first alternative produces one output and then fails; the second produces the
     * number the following {@code TakeBytes} reads. If the failed alternative's output were
     * still there, index 0 would be its non-numeric "3a" rather than the choice's own "3", and
     * the match would fail.
     *
     * <p>Design 34 §3 names this as the defect a shared buffer can introduce — a nested sequence
     * that forgets to truncate on its <i>failing</i> exit. Every other test in this file passes
     * with that truncation removed.
     */
    @Test
    void failedAlternativeOutputsDoNotSurviveIntoTheIndexSpace() {
        final String steps = """
                {"Choice": [[{"Tag": "3a"}, {"Tag": "Q"}],
                            [{"TakeWhile": "Numeric"}]]},
                {"TakeBytes": {"StepOutput": 0}}""";
        assertThat(run(steps, "3abc")).isEqualTo("[3abc]");
    }

    /**
     * The output buffer grows, and what it held before growing is still readable at its old index.
     *
     * <p>The buffer starts at the top-level step count — two here — so the twenty-one steps of the
     * nested sequence take it through three doublings. The digit is written at index 3 while the
     * buffer still holds four, and read back by the last step, three doublings later: if growth
     * allocated without carrying the old values across, that read finds nothing there.
     */
    @Test
    void theBufferGrowsAndKeepsWhatItHeldAtItsOldIndex() {
        final String after = "cdefghijklmnopqrs";
        final StringBuilder tags = new StringBuilder();
        for (final char letter : after.toCharArray()) {
            tags.append(", {\"Tag\": \"").append(letter).append("\"}");
        }
        // Index 0 is the outer tag, 1 and 2 the first two letters, 3 the digit the last step reads
        // as a count, and 4..20 the rest — which is where the buffer outgrows the four it began on.
        final String steps = """
                {"Tag": "S"},
                {"Sequence": [{"Tag": "a"}, {"Tag": "b"}, {"Tag": "3"}TAGS,
                              {"TakeBytes": {"StepOutput": 3}}]}"""
                .replace("TAGS", tags.toString());
        assertThat(run(steps, "Sab3" + after + "xyz")).isEqualTo("[Sab3" + after + "xyz]");
    }

    @Test
    void combinatorsNestInsideOneAnother() {
        final String steps = """
                {"Repeat": {"steps": [{"Choice": [[{"Tag": "a"}], [{"Tag": "b"}]]}],
                            "min": 1, "max": null}}""";
        assertThat(run(steps, "abba")).isEqualTo("[abba]");
    }
}
