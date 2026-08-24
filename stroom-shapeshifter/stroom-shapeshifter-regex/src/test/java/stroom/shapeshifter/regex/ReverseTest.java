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
import java.util.EnumSet;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins for the reverse start-finder (§6 Phase 4). The two-pass design makes a wrong
 * proposal harmless — the forward attempt refuses it and the search falls back — but a
 * finder that <em>misses</em> a start is a silent wrong NO_MATCH, so the warrant here is
 * differential: every reverse-qualified shape is run against the JDK on adversarial and
 * randomised inputs, misses included. Selection is pinned too: {@code explain()} names the
 * strategy, and the patterns v1 refuses (non-ASCII classes outside the byte-safe licence)
 * fall back and still answer correctly.
 */
class ReverseTest {

    private static final String[] QUALIFIED = {
            "([^\\\\]+)$",
            "([a-z ]+)=[^ ]+$",
            "([0-9]{3}) ([0-9]+)$",
            ".*=([^ ]+)$",
            "[ab]*c$",
            "(x|yy|zzz)+$",
    };

    @Test
    void qualifiedPatternsNameTheStrategy() {
        for (final String pattern : QUALIFIED) {
            assertThat(BytePattern.compile(pattern).explain())
                    .as(pattern)
                    .contains("reverse start-finder");
        }
    }

    @Test
    void theExclusionsDoNotNameIt() {
        // Bounded gets the tail window instead; input-anchored gets the early exit; a
        // multiline tail can end mid-region; a non-ASCII class is not cleanly reversible.
        // \d and \S are Unicode classes — neither ASCII-only nor containing every
        // non-ASCII code point — so v1 refuses them; the ASCII spellings qualify. A
        // sharpening candidate, recorded in the plan.
        for (final String pattern : new String[]{
                "(\\d{3}) (\\d{1,9})$", "(\\d{3}) (\\d+)$", ".*=(\\S+)$",
                "^a.*b$", "a", "[é]+$"}) {
            assertThat(BytePattern.compile(pattern).explain())
                    .as(pattern)
                    .doesNotContain("reverse start-finder");
        }
        // (?m)$ is END_LINE, not END_INPUT.
        assertThat(BytePattern.compile("(\\d+)$", Flag.MULTILINE).explain())
                .doesNotContain("reverse start-finder");
    }

    @Test
    void refusedPatternsStillAnswerCorrectly() {
        // The fallback is the whole point of refuse-and-fall-back: correct, unaccelerated.
        final ByteMatcher m = BytePattern.compile("[é]+$").matcher();
        final byte[] hit = "xéé".getBytes(StandardCharsets.UTF_8);
        assertThat(m.match(hit, 0, hit.length, Anchoring.UNANCHORED)).isTrue();
        final byte[] miss = "xéx".getBytes(StandardCharsets.UTF_8);
        assertThat(m.match(miss, 0, miss.length, Anchoring.UNANCHORED)).isFalse();
    }

    @Test
    void adversarialEdgesAgreeWithTheJdk() {
        // Matches at the region start, empty-capable tails, a match that is the whole
        // region, misses that differ only in the last byte, and sub-regions.
        final String[][] cases = {
                {"([^\\\\]+)$", "report.txt"},          // match == whole region
                {"([^\\\\]+)$", "a\\"},                 // miss: ends on the excluded byte
                {"([^\\\\]+)$", "\\a"},                 // match at the last byte only
                {"[ab]*c$", "c"},                        // minimal match at region start
                {"[ab]*c$", "abababc"},
                {"[ab]*c$", "abababca"},                 // miss: one byte past the c
                {"(x|yy|zzz)+$", "xyyzzzx"},
                {"(x|yy|zzz)+$", "xyyzzz!"},             // miss
                {".*=([^ ]+)$", "a=b c=d"},
                {".*=([^ ]+)$", "a=b c=d "},             // miss: trailing space
        };
        for (final String[] c : cases) {
            agree(c[0], c[1]);
        }
    }

    @Test
    void randomisedInputsAgreeWithTheJdkMissesIncluded() {
        final Random random = new Random(20260824);
        final char[] alphabet = "ab \\=x.z0".toCharArray();
        for (final String pattern : QUALIFIED) {
            final ByteMatcher ours = BytePattern.compile(pattern).matcher();
            final Pattern theirs = Pattern.compile(pattern);
            for (int i = 0; i < 400; i++) {
                final StringBuilder sb = new StringBuilder();
                final int length = random.nextInt(40);
                for (int j = 0; j < length; j++) {
                    sb.append(alphabet[random.nextInt(alphabet.length)]);
                }
                agree(ours, theirs, pattern, sb.toString());
            }
        }
    }

    /**
     * The audit's catch, pinned forever: the first cut compiled byte-safe repeats as
     * character tries, the backwards walk died at the first continuation byte, and non-ASCII
     * input turned trusted misses into wrong answers and starts into too-late ones. Every
     * case here failed before {@code NfaCompiler.compileByteLevel}.
     */
    @Test
    void nonAsciiInputAgreesWithTheJdk() {
        final String[][] cases = {
                {"([^\\\\]+)$", "café"},                // was: wrong NO_MATCH, trusted
                {"([^\\\\]+)$", "Cé.txt"},              // was: wrong span ".txt"
                {"([^\\\\]+)$", "a\\bé"},
                {"([a-z ]+)=[^ ]+$", "key=vé"},         // was: wrong NO_MATCH
                {"([^=]+)$", "a=béc"},                   // was: wrong match "c"
                {".*=([^ ]+)$", "é a=b"},                // was: wrong leftmost, group 0 " a=b"
                {".*=([^ ]+)$", "aé=b"},
                {"[ab]*c$", "éc"},
                {"(x|yy|zzz)+$", "éxyy"},
        };
        for (final String[] c : cases) {
            agree(c[0], c[1]);
        }
    }

    @Test
    void randomisedNonAsciiInputsAgreeWithTheJdk() {
        final Random random = new Random(20260825);
        // Two- and three-byte characters mixed with the ASCII the patterns actually target.
        final String[] alphabet = {"a", "b", " ", "\\", "=", "x", ".", "é", "ß", "€"};
        for (final String pattern : QUALIFIED) {
            final ByteMatcher ours = BytePattern.compile(pattern).matcher();
            final Pattern theirs = Pattern.compile(pattern);
            for (int i = 0; i < 400; i++) {
                final StringBuilder sb = new StringBuilder();
                final int length = random.nextInt(24);
                for (int j = 0; j < length; j++) {
                    sb.append(alphabet[random.nextInt(alphabet.length)]);
                }
                agree(ours, theirs, pattern, sb.toString());
            }
        }
    }

    /** {@code \G} anchors to the search start; neither acceleration may touch it. The
     * forced-TREE case pinned the audit's crash: a reverse program containing {@code \G}
     * reached the finder, whose assertion evaluator throws on it. */
    @Test
    void searchStartAnchorsAreExcludedAndStillAnswer() {
        assertThat(BytePattern.compile("\\Gabc$").explain())
                .doesNotContain("reverse start-finder");
        final byte[] data = "xxabc".getBytes(StandardCharsets.UTF_8);
        assertThat(BytePattern.compile("\\Gabc$").matcher()
                .match(data, 0, data.length, Anchoring.UNANCHORED)).isFalse();
        final ByteMatcher forcedTree = BytePattern
                .compileForcing(Engine.TREE, "\\G[a-z]+$",
                        EnumSet.noneOf(Flag.class))
                .matcher();
        final byte[] abc = "abc".getBytes(StandardCharsets.UTF_8);
        assertThat(forcedTree.match(abc, 0, abc.length, Anchoring.UNANCHORED)).isTrue();
    }

    /** Tree-carrying patterns report the strategy too — the audit caught the accel line
     * sitting below the tree early-return, which also weakened this class's exclusion pins. */
    @Test
    void treeCarryingQualifiedPatternsNameTheStrategy() {
        assertThat(BytePattern
                .compileForcing(Engine.TREE, "([^\\\\]+)$",
                        EnumSet.noneOf(Flag.class))
                .explain())
                .contains("reverse start-finder");
    }

    @Test
    void aSubRegionSearchFindsTheSameMatchAsTheJdkRegion() {
        final byte[] data = "aa\\bb\\cc".getBytes(StandardCharsets.UTF_8);
        final ByteMatcher m = BytePattern.compile("([^\\\\]+)$").matcher();
        assertThat(m.match(data, 0, 5, Anchoring.UNANCHORED)).isTrue();
        assertThat(new String(m.groupBytes(1), StandardCharsets.UTF_8)).isEqualTo("bb");
    }

    private static void agree(final String pattern, final String input) {
        agree(BytePattern.compile(pattern).matcher(), Pattern.compile(pattern), pattern, input);
    }

    private static void agree(final ByteMatcher ours,
                              final Pattern jdk,
                              final String pattern,
                              final String input) {
        final byte[] data = input.getBytes(StandardCharsets.UTF_8);
        final boolean ourMatch = ours.match(data, 0, data.length, Anchoring.UNANCHORED);
        final Matcher theirs = jdk.matcher(input);
        final boolean theirMatch = theirs.find();
        assertThat(ourMatch)
                .as("%s over %s", pattern, input)
                .isEqualTo(theirMatch);
        if (ourMatch) {
            assertThat(new String(ours.groupBytes(0), StandardCharsets.UTF_8))
                    .as("%s over %s: group 0", pattern, input)
                    .isEqualTo(theirs.group(0));
        }
    }
}
