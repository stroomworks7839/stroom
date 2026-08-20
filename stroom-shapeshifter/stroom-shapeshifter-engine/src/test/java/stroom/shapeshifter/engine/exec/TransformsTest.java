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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.regex.BytePattern;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The function library, tested directly.
 *
 * <p>The corpus barely touches it — its ten {@code replace} instructions are all literal, so
 * regex replacement with group expansion has <b>no</b> fixture coverage at all, and neither does
 * {@code substring} past the ASCII range. Those are exactly the places a port goes quietly wrong,
 * so they are tested here rather than left to a fixture that might arrive one day.
 */
class TransformsTest {

    @Test
    void translateSubstitutesEachPairInOrder() {
        assertThat(Transforms.translate(List.of("a-b-c"), List.of("-"), List.of("_")))
                .isEqualTo("a_b_c");
        assertThat(Transforms.translate(List.of("abc"), List.of("a", "b"), List.of("1", "2")))
                .isEqualTo("12c");
        // A search with no replacement deletes.
        assertThat(Transforms.translate(List.of("a-b"), List.of("-"), List.of()))
                .isEqualTo("ab");
    }

    @Test
    void stringJoinSkipsWhatIsNotThere() {
        assertThat(Transforms.stringJoin(List.of("a", "b"), ", ")).isEqualTo("a, b");
        // The point of skipping: a missing middle must not leave "a, , c".
        assertThat(Transforms.stringJoin(List.of("a", "", "c"), ", ")).isEqualTo("a, c");
        assertThat(Transforms.stringJoin(List.of("", ""), ", ")).isNull();
        assertThat(Transforms.stringJoin(List.of(), ", ")).isNull();
    }

    @Test
    void normalizeSpaceCollapsesRunsAndEnds() {
        assertThat(Transforms.normalizeSpace(List.of("  a \t\n b  "))).isEqualTo("a b");
        assertThat(Transforms.normalizeSpace(List.of("single"))).isEqualTo("single");
    }

    @Test
    void substringCountsCharactersNotBytes() {
        assertThat(Transforms.substring(List.of("abcdef"), 1, 3)).isEqualTo("bcd");
        assertThat(Transforms.substring(List.of("abcdef"), 3, null)).isEqualTo("def");
        // Past the end is the end, not an exception.
        assertThat(Transforms.substring(List.of("abc"), 1, 99)).isEqualTo("bc");
        assertThat(Transforms.substring(List.of("abc"), 99, 1)).isEmpty();
        // Multi-byte characters count as one each, and are never cut in half.
        assertThat(Transforms.substring(List.of("héllo wörld"), 0, 5)).isEqualTo("héllo");
        assertThat(Transforms.substring(List.of("😀😀😀"), 1, 1)).isEqualTo("😀");
    }

    @Test
    void tokenizeKeepsEmptyPieces() {
        assertThat(Transforms.tokenize(List.of("a,b,c"), ",")).isEqualTo("a\nb\nc");
        assertThat(Transforms.tokenize(List.of("a,,c"), ",")).isEqualTo("a\n\nc");
        // The delimiter is a literal, not a pattern.
        assertThat(Transforms.tokenize(List.of("a.b"), ".")).isEqualTo("a\nb");
    }

    @Test
    void numberKeepsWholeNumbersWhole() {
        assertThat(Transforms.number(List.of(" 42 "))).isEqualTo("42");
        assertThat(Transforms.number(List.of("42.5"))).isEqualTo("42.5");
        assertThat(Transforms.number(List.of("not a number"))).isNull();
    }

    // -----------------------------------------------------------------------------------
    // Regex replacement — no fixture reaches this
    // -----------------------------------------------------------------------------------

    @Test
    void replaceRegexExpandsGroupReferences() {
        final BytePattern pattern = BytePattern.compile("(\\w+)=(\\w+)");
        assertThat(Transforms.replaceRegex(pattern, "a=1 b=2", "$2:$1"))
                .isEqualTo("1:a 2:b");
        assertThat(Transforms.replaceRegex(pattern, "a=1", "${2}${1}"))
                .isEqualTo("1a");
    }

    @Test
    void replaceRegexUsesRustsExpansionRulesNotJavas() {
        final BytePattern pattern = BytePattern.compile("(\\d+)");
        // $$ is a literal dollar.
        assertThat(Transforms.replaceRegex(pattern, "cost 5", "$$$1"))
                .isEqualTo("cost $5");
        // A dollar followed by nothing nameable is a literal dollar, not an error — which is
        // where java.util.regex would throw.
        assertThat(Transforms.replaceRegex(pattern, "5", "$ x")).isEqualTo("$ x");
        // A named group, by name.
        assertThat(Transforms.replaceRegex(BytePattern.compile("(?<word>[a-z]+)"), "hi there", "<$word>"))
                .isEqualTo("<hi> <there>");
    }

    @Test
    void replaceRegexExpandsAnAbsentGroupToNothing() {
        final BytePattern pattern = BytePattern.compile("a(x)?b");
        assertThat(Transforms.replaceRegex(pattern, "ab axb", "[$1]"))
                .isEqualTo("[] [x]");
    }

    @Test
    void replaceRegexTerminatesOnAZeroWidthMatch() {
        // The pattern matches emptily everywhere. Without advancing past a zero-width match this
        // would never finish, which is the classic way a replace-all loop hangs.
        assertThat(Transforms.replaceRegex(BytePattern.compile("x*"), "abc", "-"))
                .isEqualTo("-a-b-c-");
    }

    @Test
    void replaceRegexAdvancesByWholeCharacters() {
        // A zero-width match before a multi-byte character must step over all of it, or the
        // output is cut through the middle of a character.
        assertThat(Transforms.replaceRegex(BytePattern.compile("x*"), "é😀", "-"))
                .isEqualTo("-é-😀-");
    }
}
