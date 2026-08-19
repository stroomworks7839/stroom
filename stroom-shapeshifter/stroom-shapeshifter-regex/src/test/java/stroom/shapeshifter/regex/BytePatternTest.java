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

package stroom.shapeshifter.regex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BytePatternTest {

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------------------
    // Matching
    // -----------------------------------------------------------------------------------

    @Test
    void matchesLiteral() {
        final ByteMatcher matcher = BytePattern.compile("hello").matcher();
        assertThat(matcher.find(bytes("say hello there"))).isTrue();
        assertThat(matcher.start()).isEqualTo(4);
        assertThat(matcher.end()).isEqualTo(9);
    }

    @Test
    void capturesGroups() {
        final ByteMatcher matcher = BytePattern.compile("^([^,]+),(\\d+)$").matcher();
        assertThat(matcher.find(bytes("widget,42"))).isTrue();
        assertThat(matcher.groupString(1)).isEqualTo("widget");
        assertThat(matcher.groupString(2)).isEqualTo("42");
        assertThat(matcher.group(1).length()).isEqualTo(6);
    }

    @Test
    void capturesNamedGroups() {
        final ByteMatcher matcher = BytePattern.compile("^(?<host>\\S+) (?<level>[A-Z]+)").matcher();
        assertThat(matcher.find(bytes("server01 ERROR something"))).isTrue();
        assertThat(matcher.group("host").toString()).isEqualTo("server01");
        assertThat(matcher.group("level").toString()).isEqualTo("ERROR");
    }

    @Test
    void distinguishesUnsetFromEmptyGroups() {
        // Group 2 never participates; group 1 matches empty. These must not look the same.
        final ByteMatcher matcher = BytePattern.compile("^(x*)(y)?z").matcher();
        assertThat(matcher.find(bytes("z"))).isTrue();
        assertThat(matcher.matchedGroup(1)).isTrue();
        assertThat(matcher.group(1).isEmpty()).isTrue();
        assertThat(matcher.matchedGroup(2)).isFalse();
        assertThat(matcher.group(2)).isNull();
    }

    @Test
    void anchoredMatchDoesNotSearch() {
        final ByteMatcher matcher = BytePattern.compile("hello").matcher();
        final byte[] data = bytes("say hello");
        assertThat(matcher.match(data, 0, data.length, Anchoring.ANCHORED)).isFalse();
        assertThat(matcher.match(data, 4, data.length, Anchoring.ANCHORED)).isTrue();
    }

    @Test
    void findsLeftmostMatch() {
        final ByteMatcher matcher = BytePattern.compile("[0-9]+").matcher();
        assertThat(matcher.find(bytes("ab 12 cd 34"))).isTrue();
        assertThat(matcher.start()).isEqualTo(3);
        assertThat(matcher.groupString(0)).isEqualTo("12");
    }

    @Test
    void respectsRegionBounds() {
        final ByteMatcher matcher = BytePattern.compile("[0-9]+").matcher();
        final byte[] data = bytes("ab 12 cd 34");
        assertThat(matcher.match(data, 6, data.length, Anchoring.UNANCHORED)).isTrue();
        assertThat(matcher.groupString(0)).isEqualTo("34");
    }

    @Test
    void matchesAlternation() {
        final ByteMatcher matcher = BytePattern.compile("^(error|INFO) (.*)$").matcher();
        assertThat(matcher.find(bytes("INFO started"))).isTrue();
        assertThat(matcher.groupString(1)).isEqualTo("INFO");
        assertThat(matcher.groupString(2)).isEqualTo("started");
    }

    @Test
    void matchesBoundedRepetition() {
        final ByteMatcher matcher = BytePattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}").matcher();
        assertThat(matcher.find(bytes("2026-08-17 rest"))).isTrue();
        assertThat(matcher.groupString(0)).isEqualTo("2026-08-17");
        assertThat(matcher.find(bytes("206-08-17"))).isFalse();
    }

    @Test
    void matchesCompoundRepetition() {
        final ByteMatcher matcher = BytePattern.compile("^(?:ab)+c").matcher();
        assertThat(matcher.find(bytes("ababababc"))).isTrue();
        assertThat(matcher.groupString(0)).isEqualTo("ababababc");
        assertThat(matcher.find(bytes("abac"))).isFalse();
    }

    @Test
    void appliesCaseInsensitivity() {
        final ByteMatcher matcher = BytePattern.compile("^error", Flag.CASE_INSENSITIVE).matcher();
        assertThat(matcher.find(bytes("ERROR here"))).isTrue();
        assertThat(matcher.find(bytes("ErRoR here"))).isTrue();
        assertThat(matcher.find(bytes("warn here"))).isFalse();
    }

    @Test
    void matchesUtf8Literals() {
        final ByteMatcher matcher = BytePattern.compile("café=(\\d+)").matcher();
        assertThat(matcher.find(bytes("le café=42"))).isTrue();
        assertThat(matcher.groupString(1)).isEqualTo("42");
    }

    @Test
    void matchesNonAsciiCharacterClasses() {
        final ByteMatcher matcher = BytePattern.compile("^([a-zé]+)=").matcher();
        assertThat(matcher.find(bytes("café=1"))).isTrue();
        assertThat(matcher.groupString(1)).isEqualTo("café");
        assertThat(matcher.find(bytes("caf1=1"))).isFalse();
    }

    @Test
    void matchesWordBoundaries() {
        final ByteMatcher matcher = BytePattern.compile("\\b(\\w+)=").matcher();
        assertThat(matcher.find(bytes("a, key=value"))).isTrue();
        assertThat(matcher.groupString(1)).isEqualTo("key");

        // \B is the complement, and neither ever falls inside a multi-byte character.
        final ByteMatcher inner = BytePattern.compile("\\Bell\\B").matcher();
        assertThat(inner.find(bytes("hello"))).isTrue();
        assertThat(inner.find(bytes("ell"))).isFalse();

        // The boundary is defined over characters, and word characters are Unicode by default,
        // so there is no boundary in the middle of "café" — the accented letter is a word
        // character and finding one takes a step back over its continuation bytes.
        assertThat(BytePattern.compile("\\bcafé\\b").matcher().find(bytes("le café noir")))
                .isTrue();
        assertThat(BytePattern.compile("caf\\bé").matcher().find(bytes("café"))).isFalse();
        assertThat(BytePattern.compile("(?-u)caf\\bé").matcher().find(bytes("café"))).isTrue();
    }

    @Test
    void matchesUnicodeProperties() {
        assertThat(BytePattern.compile("^\\p{L}+$").matcher().find(bytes("café"))).isTrue();
        assertThat(BytePattern.compile("^\\p{L}+$").matcher().find(bytes("caf3"))).isFalse();
        assertThat(BytePattern.compile("^\\p{Nd}+$").matcher().find(bytes("12345"))).isTrue();
        assertThat(BytePattern.compile("^\\p{IsGreek}+$").matcher().find(bytes("Ωμέγα"))).isTrue();
    }

    /**
     * The two families are kept apart: {@code \p{...}} is Unicode properties and nothing else,
     * while the ASCII POSIX classes have their own bracket spelling. Perl and the JDK let
     * {@code \p{Alpha}} mean the ASCII one, which reads like a Unicode property and is not — the
     * kind of quiet wrongness that surfaces months later as an under-counting report.
     */
    @Test
    void posixClassesAreSpeltWithBracketsAndAreAscii() {
        assertThat(BytePattern.compile("^[[:alpha:]]+$").matcher().find(bytes("abc"))).isTrue();
        assertThat(BytePattern.compile("^[[:alpha:]]+$").matcher().find(bytes("café"))).isFalse();
        assertThat(BytePattern.compile("^[[:digit:][:punct:]]+$").matcher().find(bytes("12.")))
                .isTrue();

        assertThatThrownBy(() -> BytePattern.compile("\\p{Alpha}"))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("[[:alpha:]]");
    }

    /**
     * The shorthands are Unicode by default, with {@code (?-u)} for the ASCII meanings — the
     * opposite of the JDK's default, and chosen because log data is full of accented text that an
     * ASCII {@code \w} silently declines to match.
     */
    @Test
    void shorthandsAreUnicodeUnlessSwitchedOff() {
        assertThat(BytePattern.compile("^\\w+$").matcher().find(bytes("café"))).isTrue();
        assertThat(BytePattern.compile("^(?-u)\\w+$").matcher().find(bytes("café"))).isFalse();
        assertThat(BytePattern.compile("^(?-u)\\w+$").matcher().find(bytes("cafe"))).isTrue();

        // Arabic-Indic digits are decimal digits, so \d covers them.
        assertThat(BytePattern.compile("^\\d+$").matcher().find(bytes("٤٢"))).isTrue();
        assertThat(BytePattern.compile("^(?-u)\\d+$").matcher().find(bytes("٤٢"))).isFalse();

        // A no-break space is whitespace to Unicode but not to ASCII.
        assertThat(BytePattern.compile("^a\\sb$").matcher().find(bytes("a\u00A0b"))).isTrue();
        assertThat(BytePattern.compile("^(?-u)a\\sb$").matcher().find(bytes("a\u00A0b"))).isFalse();
    }

    @Test
    void matchesNonAsciiRanges() {
        // The Cyrillic block spans two byte-sequence alternatives, which is the case a naive
        // range-to-byte-range conversion gets wrong.
        final ByteMatcher matcher = BytePattern.compile("^([\\u0400-\\u052F]+)$").matcher();
        assertThat(matcher.find(bytes("Привет"))).isTrue();
        assertThat(matcher.groupString(1)).isEqualTo("Привет");
        assertThat(matcher.find(bytes("Привет!"))).isFalse();
    }

    @Test
    void consumesWholeCharactersNotBytes() {
        // The bug this replaced: '.' matching one byte would split é and yield a broken span.
        final ByteMatcher matcher = BytePattern.compile("^(.)(.)").matcher();
        assertThat(matcher.find(bytes("étage"))).isTrue();
        assertThat(matcher.groupString(1)).isEqualTo("é");
        assertThat(matcher.groupString(2)).isEqualTo("t");

        final ByteMatcher counted = BytePattern.compile("^.{3}$").matcher();
        assertThat(counted.find(bytes("éàü"))).isTrue();
        assertThat(counted.find(bytes("éàüz"))).isFalse();
    }

    @Test
    void negatedAsciiClassesSpanWholeCharacters() {
        final ByteMatcher matcher = BytePattern.compile("^([^,]+),").matcher();
        assertThat(matcher.find(bytes("café,x"))).isTrue();
        assertThat(matcher.groupString(1)).isEqualTo("café");
    }

    @Test
    void groupSpansDoNotCopy() {
        final byte[] data = bytes("widget,42");
        final ByteMatcher matcher = BytePattern.compile("^([^,]+),").matcher();
        assertThat(matcher.find(data)).isTrue();
        assertThat(matcher.group(1).data()).isSameAs(data);
    }

    // -----------------------------------------------------------------------------------
    // Rejection — every refusal must say which kind of problem it is
    // -----------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "'(a)\\1',                 NOT_RE2",
            "'a(?=b)',                 NOT_RE2",
            "'a(?<=b)',                NOT_RE2",
            "'(?>a|b)',                NOT_RE2",
            "'a*+',                    NOT_RE2",
            "'(unclosed',              SYNTAX",
            "'[unclosed',              SYNTAX",
            "'*',                      SYNTAX"})
    void rejectsWithTheRightReason(final String pattern,
                                   final PatternCompileException.Reason reason) {
        assertThatThrownBy(() -> BytePattern.compile(pattern))
                .isInstanceOf(PatternCompileException.class)
                .extracting(e -> ((PatternCompileException) e).getReason())
                .isEqualTo(reason);
    }

    @Test
    void explainsWhyAPatternIsAmbiguous() {
        // An ambiguous pattern still compiles — it just needs tier 1. The diagnostic is the
        // point: this pattern's author wanted a literal dot and wrote "any character".
        final BytePattern sloppy = BytePattern.compile("(\\d+.\\d+)");
        assertThat(sloppy.tier()).isEqualTo(Engine.SIMULATE.ordinal());
        assertThat(sloppy.ambiguities()).isNotEmpty();
        assertThat(sloppy.ambiguities().getFirst().toString()).contains("can both start with");

        // Escaped, it is unambiguous and compiles to a scan plan.
        final BytePattern precise = BytePattern.compile("(\\d+\\.\\d+)");
        assertThat(precise.tier()).isZero();
        assertThat(precise.ambiguities()).isEmpty();
        assertThat(BytePattern.analyse("(\\d+\\.\\d+)", java.util.Set.of())).isEmpty();
    }

    @Test
    void runsAmbiguousPatternsOnTierOne() {
        // Greedy .+ must take the last colon, which is exactly what a scan plan cannot decide.
        final BytePattern pattern = BytePattern.compile("^(.+):(.+)$");
        assertThat(pattern.tier()).isEqualTo(Engine.SIMULATE.ordinal());

        final ByteMatcher matcher = pattern.matcher();
        assertThat(matcher.find(bytes("a:b:c"))).isTrue();
        assertThat(matcher.groupString(1)).isEqualTo("a:b");
        assertThat(matcher.groupString(2)).isEqualTo("c");
    }

    @Test
    void tierOneHandlesOverlappingAlternation() {
        // Ordered preference: the first branch that leads to an overall match wins.
        final ByteMatcher matcher = BytePattern.compile("^(a|ab|abc)(.*)$").matcher();
        assertThat(matcher.find(bytes("abc"))).isTrue();
        assertThat(matcher.groupString(1)).isEqualTo("a");
        assertThat(matcher.groupString(2)).isEqualTo("bc");
    }

    @Test
    void tierOneIsLinearOnAPathologicalPattern() {
        // (a+)+b against a long run of 'a' is the classic catastrophic-backtracking case. A
        // backtracking engine takes exponential time; the simulation must simply not match.
        final BytePattern pattern = BytePattern.compile("^(a+)+b$");
        assertThat(pattern.tier()).isEqualTo(Engine.SIMULATE.ordinal());
        final ByteMatcher matcher = pattern.matcher();
        final byte[] data = "a".repeat(2000).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        final long start = System.nanoTime();
        assertThat(matcher.find(data)).isFalse();
        final long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(millis)
                .as("2000 characters took %d ms — that is not linear time", millis)
                .isLessThan(2000);
    }

    // -----------------------------------------------------------------------------------
    // Plan selection — a silent loss of specialisation should fail the build
    // -----------------------------------------------------------------------------------

    @Test
    void specialisesClassTestsByCardinality() {
        // Complement of one byte becomes a scan-until, not a table lookup.
        assertThat(BytePattern.compile("^([^,]+),").explain())
                .contains("SCAN_UNTIL_BYTE ','")
                .doesNotContain("SCAN_WHILE_CLASS");

        // A single byte becomes an equality scan.
        assertThat(BytePattern.compile("^a+b").explain())
                .contains("SCAN_WHILE_BYTE 'a'");

        // A general class falls back to the table.
        assertThat(BytePattern.compile("^[a-z]+=").explain())
                .contains("SCAN_WHILE_CLASS");
    }

    @Test
    void compilesTheMotivatingPatternsToPlans() {
        assertThat(BytePattern.compile("^([^,]+),(\\d+)$").explain()).contains("tier:    0");
        assertThat(BytePattern.compile("^(\\S+) (\\S+)").explain()).contains("tier:    0");
        assertThat(BytePattern.compile("[a-z]+=[^,]+").explain()).contains("tier:    0");
    }
}
