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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fancy tier: backreferences, lookaround, atomic groups and possessive quantifiers,
 * {@code \Q...\E} and {@code \G} — the constructs beyond the RE2 subset, running on the
 * unbounded backtracker.
 * <p>
 * These are the shape checks; agreement with {@code java.util.regex} over generated inputs is
 * in {@link DifferentialTest}, which is where the semantics are actually established.
 */
class FancyTest {

    // -----------------------------------------------------------------------------------
    // Engine selection
    // -----------------------------------------------------------------------------------

    @Test
    void backrefSelectsTheFancyTier() {
        // Since D31 the fancy tier's primary engine is the tree walker, with the flat
        // backtracker as its structural fallback; engine() names what runs first.
        final BytePattern pattern = BytePattern.compile("(\\w+) \\1");
        assertThat(pattern.engine()).isEqualTo(Engine.TREE);
        assertThat(pattern.tier()).isEqualTo(Engine.TREE.ordinal());
    }

    @Test
    void everyFancyConstructSelectsTheFancyTier() {
        assertThat(BytePattern.compile("foo(?=bar)").engine()).isEqualTo(Engine.TREE);
        assertThat(BytePattern.compile("(?<=foo)bar").engine()).isEqualTo(Engine.TREE);
        assertThat(BytePattern.compile("(?>a|ab)c").engine()).isEqualTo(Engine.TREE);
        assertThat(BytePattern.compile("a*+b").engine()).isEqualTo(Engine.TREE);
        assertThat(BytePattern.compile("\\Gab").engine()).isEqualTo(Engine.TREE);
    }

    @Test
    void quotingAloneIsNotFancy() {
        // \Q...\E is pure syntax — it compiles to the same literals the unquoted spelling
        // would, so it costs nothing and runs wherever the pattern would otherwise run.
        final BytePattern pattern = BytePattern.compile("\\Qa.b\\E,c");
        assertThat(pattern.engine()).isEqualTo(Engine.SCAN_PLAN);
    }

    // -----------------------------------------------------------------------------------
    // Backreferences
    // -----------------------------------------------------------------------------------

    @Test
    void matchesABackreference() {
        final ByteMatcher matcher = BytePattern.compile("(\\w+) \\1").matcher();
        assertThat(matcher.find(bytes("say hey hey now"))).isTrue();
        assertThat(matcher.groupString(0)).isEqualTo("hey hey");
        assertThat(matcher.groupString(1)).isEqualTo("hey");
        assertThat(matcher.find(bytes("no repetition here"))).isFalse();
    }

    @Test
    void matchesANamedBackreference() {
        final ByteMatcher matcher =
                BytePattern.compile("(?<tag><(\\w+)>).*?</\\2>").matcher();
        assertThat(matcher.find(bytes("<b>bold</b>"))).isTrue();
        assertThat(matcher.groupString(2)).isEqualTo("b");

        final ByteMatcher named = BytePattern.compile("(?<word>\\w+)-\\k<word>").matcher();
        assertThat(named.find(bytes("re-do no-no"))).isTrue();
        assertThat(named.groupString(0)).isEqualTo("no-no");
    }

    @Test
    void referenceToAnUnmatchedGroupFails() {
        // The group did not participate, so there is nothing to re-match — failure, not the
        // empty string. The JDK and fancy-regex both read it this way.
        final ByteMatcher matcher = BytePattern.compile("(?:(a)|b)\\1").matcher();
        assertThat(matcher.find(bytes("aa"))).isTrue();
        assertThat(matcher.find(bytes("bb"))).isFalse();
    }

    @Test
    void forwardReferencesAreLegal() {
        // The classic: the reference runs in an earlier iteration than its group's capture.
        final ByteMatcher matcher = BytePattern.compile("^(\\2two|(one))+$").matcher();
        assertThat(matcher.find(bytes("oneonetwo"))).isTrue();
    }

    @Test
    void referenceToAMissingGroupIsACompileError() {
        assertThatThrownBy(() -> BytePattern.compile("(a)\\2"))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("no group 2");
        assertThatThrownBy(() -> BytePattern.compile("(a)\\k<name>"))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("no group named 'name'");
    }

    @Test
    void caseSensitivityIsSettledAtTheReference() {
        // (?i) is lexical, so it governs the reference it encloses, not the group.
        assertThat(BytePattern.compile("(a)\\1").matcher().find(bytes("aA"))).isFalse();
        assertThat(BytePattern.compile("(a)(?i:\\1)").matcher().find(bytes("aA"))).isTrue();
        assertThat(BytePattern.compile("(?i)(\\w+) \\1").matcher()
                .find(bytes("Hey HEY"))).isTrue();
    }

    @Test
    void caseInsensitiveBackrefFoldsAcrossUnicode() {
        // The captured É is one character in two bytes; the é it must equal is another two.
        final ByteMatcher matcher = BytePattern.compile("(?i)(\\w+) \\1").matcher();
        assertThat(matcher.find("CAFÉ café".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    // -----------------------------------------------------------------------------------
    // Lookaround
    // -----------------------------------------------------------------------------------

    @Test
    void lookahead() {
        final ByteMatcher matcher = BytePattern.compile("foo(?=bar)").matcher();
        assertThat(matcher.find(bytes("foobar"))).isTrue();
        assertThat(matcher.end()).isEqualTo(3); // zero-width: bar is not consumed
        assertThat(matcher.find(bytes("foobaz"))).isFalse();

        final ByteMatcher negative = BytePattern.compile("foo(?!bar)").matcher();
        assertThat(negative.find(bytes("foobaz"))).isTrue();
        assertThat(negative.find(bytes("foobar"))).isFalse();
    }

    @Test
    void lookbehind() {
        final ByteMatcher matcher = BytePattern.compile("(?<=foo)bar").matcher();
        assertThat(matcher.find(bytes("foobar"))).isTrue();
        assertThat(matcher.start()).isEqualTo(3);
        assertThat(matcher.find(bytes("bazbar"))).isFalse();

        final ByteMatcher negative = BytePattern.compile("(?<!foo)bar").matcher();
        assertThat(negative.find(bytes("bazbar"))).isTrue();
        assertThat(negative.find(bytes("foobar"))).isFalse();
    }

    @Test
    void lookbehindTriesEveryLengthTheBodyAllows() {
        // The preferred branch a matches one byte back but the c after it then fails; only the
        // two-byte ab branch ends the lookbehind exactly at the cursor.
        final ByteMatcher matcher = BytePattern.compile("(?<=ab|x)c").matcher();
        assertThat(matcher.find(bytes("abc"))).isTrue();
        assertThat(matcher.find(bytes("ac"))).isFalse();
    }

    @Test
    void captureInsideALookaheadIsACapture() {
        final ByteMatcher matcher = BytePattern.compile("(?=(\\d+))\\w+").matcher();
        assertThat(matcher.find(bytes("123abc"))).isTrue();
        assertThat(matcher.groupString(1)).isEqualTo("123");
    }

    @Test
    void anUnboundedLookbehindIsRefused() {
        // The body's length range bounds how far back to try; without a maximum there is no
        // bound. The JDK draws the same line, with the same reasoning.
        assertThatThrownBy(() -> BytePattern.compile("(?<=a+)b"))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("no maximum length");
    }

    // -----------------------------------------------------------------------------------
    // Atomic groups and possessive quantifiers
    // -----------------------------------------------------------------------------------

    @Test
    void anAtomicGroupNeverGivesBack() {
        // (?>a|ab) commits to a, so the c can only follow an a — never the ab branch.
        final ByteMatcher matcher = BytePattern.compile("^(?>a|ab)c$").matcher();
        assertThat(matcher.find(bytes("ac"))).isTrue();
        assertThat(matcher.find(bytes("abc"))).isFalse();
    }

    @Test
    void possessiveQuantifierIsAnAtomicRepeat() {
        // a++ consumes every a and gives none back, so a following a can never match.
        assertThat(BytePattern.compile("a++ab").matcher().find(bytes("aaaab"))).isFalse();
        assertThat(BytePattern.compile("a+ab").matcher().find(bytes("aaaab"))).isTrue();
        assertThat(BytePattern.compile("\\d*+x").matcher().find(bytes("123x"))).isTrue();
    }

    // -----------------------------------------------------------------------------------
    // \Q...\E and \G
    // -----------------------------------------------------------------------------------

    @Test
    void quotedSectionsMatchLiterally() {
        final ByteMatcher matcher = BytePattern.compile("\\Qa.b*\\E").matcher();
        assertThat(matcher.find(bytes("xa.b*x"))).isTrue();
        assertThat(matcher.find(bytes("xaxb*x"))).isFalse(); // the dot is a dot
    }

    @Test
    void previousMatchEndAnchorsAFindLoop() {
        // \G holds where the search started, which for a find loop is the previous match's
        // end — so the loop stops at the first gap instead of skipping it.
        final ByteMatcher matcher = BytePattern.compile("\\G(a|b)").matcher();
        final byte[] data = bytes("abxab");
        int found = 0;
        int at = 0;
        while (matcher.match(data, at, data.length, Anchoring.UNANCHORED)) {
            found++;
            at = matcher.end();
        }
        assertThat(found).isEqualTo(2); // a, b — and nothing after the x
    }

    // -----------------------------------------------------------------------------------
    // Containment
    // -----------------------------------------------------------------------------------

    @Test
    void catastrophicBacktrackingRaisesTheLimitInsteadOfHanging() {
        // (a+)+ against a long run with no b is the classic exponential case. The linear
        // engines are immune by construction; this tier is not, and the budget is what turns
        // two-to-the-forty steps into an exception.
        final ByteMatcher matcher = BytePattern.compile("(a+)+b\\1").matcher();
        final byte[] data = bytes("a".repeat(40));
        assertThatThrownBy(() -> matcher.find(data))
                .isInstanceOf(MatchLimitException.class)
                .hasMessageContaining("steps");
    }

    @Test
    void theMatcherSurvivesALimitException() {
        final ByteMatcher matcher = BytePattern.compile("(a+)+b\\1").matcher();
        assertThatThrownBy(() -> matcher.find(bytes("a".repeat(40))))
                .isInstanceOf(MatchLimitException.class);
        // The same matcher, on a benign record, works normally afterwards.
        assertThat(matcher.find(bytes("aabaa"))).isTrue();
    }

    // -----------------------------------------------------------------------------------
    // The corpus patterns that motivated the tier
    // -----------------------------------------------------------------------------------

    @Test
    void theHarvestedJavaDialectPatternsNowCompileNatively() {
        // The three patterns 04-corpus-analysis.md recorded as needing the delegated JDK
        // engine — the empirical capability gap this tier exists to close. Zero of them use a
        // backreference; the gap in the wild was atomic groups and lookahead.
        final BytePattern blocks = BytePattern.compile(
                "((?>\\n*|^)(?>.*\\n)+?)\\n((?>\\n*|^)(?>.*\\n)+?(?>\\n|$))", Flag.MULTILINE);
        assertThat(blocks.engine()).isEqualTo(Engine.TREE);

        final BytePattern fields = BytePattern.compile("([\\w ]+)((?>,|$))");
        assertThat(fields.engine()).isEqualTo(Engine.TREE);
        final ByteMatcher matcher = fields.matcher();
        assertThat(matcher.find(bytes("alpha beta,gamma"))).isTrue();
        assertThat(matcher.groupString(1)).isEqualTo("alpha beta");

        final BytePattern records = BytePattern.compile(
                "^(?:INFO|DEBUG|WARN|ERROR|TRACE) +.*?"
                + "(?=(?:(?:INFO|DEBUG|WARN|ERROR|TRACE) +)|\\z)", Flag.DOT_ALL);
        assertThat(records.engine()).isEqualTo(Engine.TREE);
        final ByteMatcher splitter = records.matcher();
        assertThat(splitter.find(bytes("INFO first thing WARN second thing"))).isTrue();
        assertThat(splitter.groupString(0)).isEqualTo("INFO first thing ");
    }

    // -----------------------------------------------------------------------------------
    // Streaming
    // -----------------------------------------------------------------------------------

    @Test
    void backrefTruncatedByTheWindowIsUndetermined() {
        final ByteMatcher matcher = BytePattern.compile("(a+)-\\1").matcher();
        // Every byte in hand agrees with the reference, so more input could complete it.
        assertThat(matcher.match(ByteWindow.partial(bytes("aa-a"), 0, 4), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.NEED_MORE_INPUT);
        assertThat(matcher.match(ByteWindow.of("aa-aa"), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.MATCH);
        // A byte that already disagrees is a determined no-match, however much input follows.
        assertThat(matcher.match(ByteWindow.partial(bytes("aa-b"), 0, 4), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.NO_MATCH);
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
