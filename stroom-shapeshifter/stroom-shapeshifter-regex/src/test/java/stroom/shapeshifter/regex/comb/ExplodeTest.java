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

import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.Encoding;
import stroom.shapeshifter.regex.Flag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A regex explodes into the composition it is (design 38 §3a), and the composition compiles to
 * the identical plan: the round trip preserves meaning exactly and text not at all.
 */
class ExplodeTest {

    /**
     * The plan from the tier line down, with a character class's label — the text it was
     * written as, which the explode rewrites to the set it means — taken out, so the comparison
     * is of instructions and sets.
     */
    private static String planOf(final BytePattern pattern) {
        final String explained = pattern.explain();
        return explained.substring(explained.indexOf("tier:"))
                .replaceAll("(MATCH_CHAR|SCAN_WHILE_CHAR) +\\S+ \\(", "$1 (");
    }

    private static Matcher explode(final String regex, final Flag... flags) {
        return Matchers.explode(regex, Set.of(flags));
    }

    /** The explode's plan is the regex's, for every construct the vocabulary names and several it does not. */
    @ParameterizedTest
    @ValueSource(strings = {
            "([a-z]+)=([0-9]+)", "ERROR|WARN|INFO", "(?<key>\\w+)\\s*=\\s*(?<value>[^,]*),?",
            "^\\d{4}-\\d{2}-\\d{2}$", "a{1}b{2,}c{3,5}d?e*?f+?", "(?:ab)+c", "(?:failed|succeeded) for user '([^']+)'",
            "(?=ab)abc", "(?!zz)abc", "\\bword\\b", "(a)\\1", "x(?:(?!\\d{2}/)\\S)*",
            "(?s).*", ".*", "[\\s\\S]+", "(?i)abc", "(?i)[a-z]+", "é+", "a|", "()",
            "(?>ab)c", "(?<=a)b", "\\B{FF}\\B{00}", "\\G\\d+", "(?m)^line$", "(?i)[:-;k]+", "(?i)(a)\\1"})
    void anExplodeCompilesToTheIdenticalPlan(final String regex) {
        final BytePattern written = BytePattern.compile(regex);
        final BytePattern exploded = new MatcherLibrary().compile(explode(regex));
        assertThat(planOf(exploded)).as(regex + " explodes as " + explode(regex)).isEqualTo(planOf(written));
        assertThat(exploded.groupCount()).isEqualTo(written.groupCount());
    }

    /** The same, under the flags a configuration can set, and in the raw byte form. */
    @Test
    void anExplodeUnderFlagsAndInRawFormCompilesToTheIdenticalPlan() {
        for (final String regex : List.of("abc[a-z]+", "a.b", "(?<w>\\w+)\\k<w>")) {
            for (final Set<Flag> flags : List.of(Set.of(Flag.CASE_INSENSITIVE), Set.of(Flag.DOT_ALL),
                    Set.of(Flag.CASE_INSENSITIVE, Flag.DOT_ALL))) {
                final BytePattern written = BytePattern.compile(regex, flags);
                final BytePattern exploded = new MatcherLibrary().compile(Matchers.explode(regex, flags));
                assertThat(planOf(exploded)).as(regex + " under " + flags).isEqualTo(planOf(written));
            }
        }
        for (final String raw : List.of("[\\x80-\\xff]*[\\x00-\\x7f]", "(.)\\1\\bx")) {
            assertThat(planOf(new MatcherLibrary().compile(explode(raw), Set.of(), Encoding.RAW))).as(raw)
                    .isEqualTo(planOf(BytePattern.compile(raw, Set.of(), Encoding.RAW)));
        }
    }

    @Test
    void theVocabularyIsNamedAndTheRestStaysALeaf() {
        assertThat(explode("([a-z]+)=([0-9]+)")).isEqualTo(new Matcher.Sequence(List.of(
                new Matcher.Labelled(new Matcher.Characters("[a-z]", 1, Matcher.Repeat.UNBOUNDED), "_1"),
                new Matcher.Tag("="),
                new Matcher.Labelled(new Matcher.Characters("[0-9]", 1, Matcher.Repeat.UNBOUNDED), "_2"))));
        assertThat(explode("ERROR|WARN|INFO")).isEqualTo(new Matcher.Choice(List.of(
                new Matcher.Tag("ERROR"), new Matcher.Tag("WARN"), new Matcher.Tag("INFO"))));
        assertThat(explode("(?<k>\\w+)\\s*")).isEqualTo(new Matcher.Sequence(List.of(
                new Matcher.Labelled(new Matcher.Characters("\\w", 1, Matcher.Repeat.UNBOUNDED), "k"),
                new Matcher.Characters("\\s", 0, Matcher.Repeat.UNBOUNDED))));
        assertThat(explode("^a$")).isEqualTo(new Matcher.Sequence(List.of(
                new Matcher.Regex("^", Set.of()), new Matcher.Tag("a"), new Matcher.Regex("$", Set.of()))));
        assertThat(explode("(?=ab)(?!c)x")).isEqualTo(new Matcher.Sequence(List.of(
                new Matcher.Peek(new Matcher.Tag("ab")), new Matcher.Not(new Matcher.Tag("c")), new Matcher.Tag("x"))));
        assertThat(explode("(ab)*?")).isEqualTo(new Matcher.Repeat(
                new Matcher.Labelled(new Matcher.Tag("ab"), "_1"), 0, Matcher.Repeat.UNBOUNDED, false));
    }

    /** The flags are absorbed: what they changed is written out as the set it became. */
    @Test
    void flagsAreAbsorbedIntoTheClassesTheyChanged() {
        assertThat(explode("ab", Flag.CASE_INSENSITIVE)).isEqualTo(new Matcher.Sequence(List.of(
                new Matcher.Characters("[Aa]", 1, 1), new Matcher.Characters("[Bb]", 1, 1))));
        assertThat(explode("[a-c]+", Flag.CASE_INSENSITIVE))
                .isEqualTo(new Matcher.Characters("[A-Ca-c]", 1, Matcher.Repeat.UNBOUNDED));
        assertThat(explode(".", Flag.DOT_ALL)).isEqualTo(new Matcher.Characters("[\\s\\S]", 1, 1));
        assertThat(explode(".")).isEqualTo(new Matcher.Characters(".", 1, 1));
        assertThat(explode("(a)\\1", Flag.CASE_INSENSITIVE)).isEqualTo(new Matcher.Sequence(List.of(
                new Matcher.Labelled(new Matcher.Characters("[Aa]", 1, 1), "_1"),
                new Matcher.Regex("(?iu:\\1)", Set.of()))));
        assertThat(explode("(a)\\1")).isEqualTo(new Matcher.Sequence(List.of(
                new Matcher.Labelled(new Matcher.Tag("a"), "_1"), new Matcher.Regex("(?-i:\\1)", Set.of()))));
        assertThat(explode("[:-;k]", Flag.CASE_INSENSITIVE)).as("a set that begins with ':' is not a POSIX class")
                .isEqualTo(new Matcher.Characters("[\\x{3A}-;Kk\\x{212A}]", 1, 1));
    }

    /** An atomic group or a lookbehind has no leaf of its own until the printer exists: the regex is one leaf. */
    @Test
    void whatCannotBeMappedIsTheWholeRegexAsOneLeaf() {
        assertThat(explode("(?>ab)c")).isEqualTo(new Matcher.Regex("(?>ab)c", Set.of()));
        assertThat(explode("(?<=a)b", Flag.DOT_ALL)).isEqualTo(new Matcher.Regex("(?<=a)b", Set.of(Flag.DOT_ALL)));
        assertThat(explode("\\B{FF}x")).isEqualTo(new Matcher.Sequence(List.of(
                new Matcher.Regex("\\B{FF}", Set.of()), new Matcher.Tag("x"))));
    }
}
