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

package stroom.shapeshifter.engine.value;

import stroom.shapeshifter.regex.BytePattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regex replace, which no fixture reaches.
 *
 * <p>Its expansion dialect is Rust regex's, not {@code java.util.regex}'s, and the difference is
 * silent: the same replacement string means different things under the two, so these say which.
 */
class ReplacerTest {

    /** Compile and run in one go, as the tests read; the engine compiles once and runs many. */
    private static String replace(final BytePattern pattern, final String input, final String replacement) {
        return new Replacer(pattern, replacement).replace(input);
    }

    @Test
    void expandsGroupReferences() {
        final BytePattern pattern = BytePattern.compile("(\\w+)=(\\w+)");
        assertThat(replace(pattern, "a=1 b=2", "$2:$1"))
                .isEqualTo("1:a 2:b");
        assertThat(replace(pattern, "a=1", "${2}${1}"))
                .isEqualTo("1a");
    }

    @Test
    void usesRustsExpansionRulesNotJavas() {
        final BytePattern pattern = BytePattern.compile("(\\d+)");
        // $$ is a literal dollar.
        assertThat(replace(pattern, "cost 5", "$$$1"))
                .isEqualTo("cost $5");
        // A dollar followed by nothing nameable is a literal dollar, not an error — which is
        // where java.util.regex would throw.
        assertThat(replace(pattern, "5", "$ x")).isEqualTo("$ x");
        // A named group, by name.
        assertThat(replace(BytePattern.compile("(?<word>[a-z]+)"), "hi there", "<$word>"))
                .isEqualTo("<hi> <there>");
    }

    @Test
    void expandsAnAbsentGroupToNothing() {
        final BytePattern pattern = BytePattern.compile("a(x)?b");
        assertThat(replace(pattern, "ab axb", "[$1]"))
                .isEqualTo("[] [x]");
    }

    @Test
    void terminatesOnAZeroWidthMatch() {
        // The pattern matches emptily everywhere. Without advancing past a zero-width match this
        // would never finish, which is the classic way a replace-all loop hangs.
        assertThat(replace(BytePattern.compile("x*"), "abc", "-"))
                .isEqualTo("-a-b-c-");
    }

    @Test
    void advancesByWholeCharacters() {
        // A zero-width match before a multi-byte character must step over all of it, or the
        // output is cut through the middle of a character.
        assertThat(replace(BytePattern.compile("x*"), "é😀", "-"))
                .isEqualTo("-é-😀-");
    }

    @Test
    void everyMalformedReferenceIsALiteralDollar() {
        // The parse happens once now rather than per match, so its edge branches are compiled
        // code no fixture reaches. Each of these was checked against the pre-phase-3 expander
        // over every combination of eight patterns, thirteen inputs and twenty-six
        // replacements; these pin the answers it gave.
        final BytePattern pattern = BytePattern.compile("(\\d+)");
        assertThat(replace(pattern, "cost 5", "$")).isEqualTo("cost $");
        assertThat(replace(pattern, "cost 5", "${1")).isEqualTo("cost ${1");
        assertThat(replace(pattern, "cost 5", "$1$")).isEqualTo("cost 5$");
        // A reference naming no group in this pattern expands to nothing, as an absent one does.
        assertThat(replace(pattern, "cost 5", "${}")).isEqualTo("cost ");
        assertThat(replace(pattern, "cost 5", "$9")).isEqualTo("cost ");
    }

    @Test
    void oneReplacerAnswersTheSameOnEveryCall() {
        // It holds a matcher and a parsed replacement now, so it carries state between calls in
        // a way the old per-call function did not. Every record of a run goes through one of
        // these, and the second must not read the first's match.
        final Replacer replacer = new Replacer(BytePattern.compile("(\\w+)=(\\w+)"), "$2:$1");
        assertThat(replacer.replace("a=1 b=2")).isEqualTo("1:a 2:b");
        assertThat(replacer.replace("a=1 b=2")).isEqualTo("1:a 2:b");
        assertThat(replacer.replace("c=3")).isEqualTo("3:c");
        assertThat(replacer.replace("nothing here")).isEqualTo("nothing here");
        assertThat(replacer.replace("a=1 b=2")).isEqualTo("1:a 2:b");
    }
}
