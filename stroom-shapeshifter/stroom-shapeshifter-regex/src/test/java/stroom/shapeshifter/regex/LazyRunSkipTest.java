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
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lazy-run skip: a lazy unbounded class repetition whose continuation must begin with one
 * known ASCII byte skips the positions that byte is not at, instead of offering every position
 * to the continuation in turn.
 *
 * <p>The optimisation is a filter on <i>where</i> to try and never a statement about what a
 * try does, so the whole of its correctness is one claim: <b>every position it skips is one
 * the unfiltered walk would have tried and failed at</b>. These tests attack that claim from
 * the directions it could be wrong — a candidate byte the class rejects, a run that must stop
 * before the candidate, multi-byte characters between the two, a literal that appears more
 * than once (leftmost-first must still pick the first), a continuation that is not a literal
 * at all, and case-insensitivity, where the lowered form must either carry one byte or turn
 * the filter off.
 *
 * <p>The JDK is the oracle throughout, reached through {@link JdkOracle} so that {@code $}
 * is compared as this dialect means it — {@code END_INPUT} without {@code (?m)}, which the
 * oracle spells {@code \z}. What is new here
 * is the pattern shape, which that suite does not contain — every lazy pattern in it is
 * lazy-with-no-literal or greedy, so none of them would have exercised a line of this.
 */
class LazyRunSkipTest {

    /** Lazy runs with a literal continuation, and the ways that can be awkward. */
    private static final List<String> LAZY_PATTERNS = List.of(
            "(.*?)b",                       // the plain shape
            "(?s)(.*?)b",                   // dot-all, the nasty_xml shape
            "(?s)(.*?)</batch>",            // a multi-byte literal
            "([^b]*?)b",                    // the class excludes the candidate byte
            "([a-z]*?)X",                   // the class stops before the candidate
            "([a-z]*?)x",                   // candidate inside the class
            "(?s)(.*?)(?s).*?b",            // two lazy runs in a row
            "a(.*?)b(.*?)c",                // two runs with captures between literals
            "(?s)(.*?)\\n",                 // the newline terminator the line idiom uses
            "(.*?)$",                       // continuation is an assertion, not a literal
            "(.*?)",                        // continuation is the end of the pattern
            "(.*?)[bc]",                    // continuation is a class, not one byte
            "(?i)(.*?)B",                    // case-insensitive continuation
            "(?s)(.*?)é",                   // a non-ASCII literal: the filter must stay off
            "(?s)(.{0,4}?)b",               // bounded, so a different node entirely
            "((?s).*?  </batch>\\n)");      // the case's own pattern, captured tail and all

    private static final List<String> INPUTS = List.of(
            "", "b", "ab", "aaab", "aaa", "abab", "xyzb", "X", "aX", "abcX", "ab1X",
            "a\nb", "\n", "aaa\nbbb\n", "é", "aéb", "ééébéé", "aébéc",
            "  </batch>\n", "x  </batch>\ny  </batch>\n", "no terminator here",
            "abc</batch>", "</batch>  </batch>\n", "B", "aB", "AaB");

    @Test
    void agreesWithTheJdkOnEveryShapeAndInput() {
        int compared = 0;
        for (final String pattern : LAZY_PATTERNS) {
            final Pattern jdk = JdkOracle.compile(pattern);
            for (final String input : INPUTS) {
                assertAgree(pattern, jdk, input);
                compared++;
            }
        }
        assertThat(compared).isEqualTo(LAZY_PATTERNS.size() * INPUTS.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void agreesWithTheJdkOnGeneratedInputs(final int seed) {
        final Random random = new Random(seed);
        for (final String pattern : LAZY_PATTERNS) {
            final Pattern jdk = JdkOracle.compile(pattern);
            for (int i = 0; i < 300; i++) {
                assertAgree(pattern, jdk, randomText(random));
            }
        }
    }

    /**
     * The filter must not change which engine answers or what it answers. Every engine that
     * can take the pattern must give the same span, because the skip lives in one of them and
     * a divergence between tiers is exactly what it would look like if the claim were false.
     */
    @Test
    void everyEngineThatCanTakeTheShapeStillAgrees() {
        for (final String pattern : LAZY_PATTERNS) {
            for (final String input : INPUTS) {
                assertEnginesAgree(pattern, input.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /** Every engine that can take the pattern must return the same span for these bytes. */
    private static void assertEnginesAgree(final String pattern, final byte[] data) {
        final List<String> answers = new ArrayList<>();
        for (final Engine engine : Engine.values()) {
            final BytePattern compiled;
            try {
                compiled = BytePattern.compileForcing(
                        engine, pattern, EnumSet.noneOf(Flag.class));
            } catch (final RuntimeException e) {
                continue; // this tier cannot take this shape; not a finding
            }
            final ByteMatcher m = compiled.matcher();
            try {
                answers.add(m.find(data)
                        ? engine + "=" + m.start(0) + ".." + m.end(0)
                        : engine + "=-");
            } catch (final RuntimeException e) {
                continue; // a step budget or structural refusal, not a disagreement
            }
        }
        final List<String> spans = answers.stream()
                .map(a -> a.substring(a.indexOf('=') + 1)).distinct().toList();
        assertThat(spans)
                .as("engines disagree: pattern=%s bytes=%s -> %s",
                        pattern, java.util.Arrays.toString(data), answers)
                .hasSizeLessThanOrEqualTo(1);
    }

    /**
     * Raw bytes, including sequences that are not valid UTF-8 — which log data is full of, and
     * which no {@code String} input can express. This is the only place the skip's ASCII
     * restriction is load-bearing: a literal's first byte is never a continuation byte in a
     * well-formed pattern, so over valid input a wider filter would be harmless, but over
     * malformed input a non-ASCII candidate can sit at an offset the character-wise walk steps
     * straight over, and stopping there would offer the continuation a position the unfiltered
     * loop never reaches. The other engines carry no skip, so they are the oracle.
     */
    @Test
    void agreesWithTheOtherEnginesOnBytesThatAreNotValidText() {
        final List<byte[]> inputs = List.of(
                new byte[]{'A', (byte) 0xC3, (byte) 0xC3, (byte) 0xA9},
                new byte[]{(byte) 0xC3, (byte) 0xA9, (byte) 0xC3, (byte) 0xA9},
                new byte[]{(byte) 0xC3, (byte) 0x41, (byte) 0xC3, (byte) 0xA9},
                new byte[]{(byte) 0xA9, (byte) 0xC3, (byte) 0xA9},
                new byte[]{(byte) 0xE4, (byte) 0xB8, (byte) 0xC3, (byte) 0xA9},
                new byte[]{(byte) 0xFF, (byte) 0xC3, (byte) 0xA9, 'b'},
                new byte[]{'a', (byte) 0x80, (byte) 0xC3, (byte) 0xA9},
                new byte[]{(byte) 0xC3, (byte) 0xA9},
                new byte[]{(byte) 0xF0, (byte) 0x9F, (byte) 0x92, (byte) 0xA9, 'b'},
                // The decoder-strictness edges GreedyRunRawBytesTest convicted decode() with:
                // overlong two- and three-byte forms, a surrogate, two out-of-range four-byte
                // forms. Sequence-compiled engines reject all five by construction.
                new byte[]{(byte) 0xC0, (byte) 0x80, 'b'},
                new byte[]{(byte) 0xE0, (byte) 0x80, (byte) 0x80, 'b'},
                new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80, 'b'},
                new byte[]{(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80, 'b'},
                new byte[]{(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80, 'b'});
        for (final String pattern : List.of("(?s)(.*?)é", "(?s)(.*?)中", "(?s)(.*?)b",
                "(?s)([^\\x00]*?)é", "(?s)(.*?)éb")) {
            for (final byte[] data : inputs) {
                assertEnginesAgree(pattern, data);
            }
        }
    }

    /**
     * Leftmost-first, explicitly: a lazy run stops at the <b>first</b> place its continuation
     * can match. Skipping ahead is only sound if it lands on that same first place, so this is
     * the property most directly at risk, pinned on its own rather than left to the oracle.
     */
    @Test
    void theRunStillStopsAtTheFirstCandidateNotALaterOne() {
        final BytePattern p = BytePattern.compile("(?s)(.*?)  </batch>\\n");
        final String input = "one  </batch>\ntwo  </batch>\n";
        final ByteMatcher m = p.matcher();
        assertThat(m.find(input.getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(m.groupString(1)).isEqualTo("one");
        assertThat(m.groupString(0)).isEqualTo("one  </batch>\n");
    }

    /**
     * A candidate byte the class rejects must end the run, not be skipped past. {@code [a-z]*?}
     * cannot cross the digit, so there is no match even though the terminator is beyond it.
     */
    @Test
    void runThatCannotReachTheCandidateStillFails() {
        final BytePattern p = BytePattern.compile("^([a-z]*?)X$");
        assertThat(p.matcher().find("ab1X".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(p.matcher().find("abX".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    private static void assertAgree(final String pattern, final Pattern jdk, final String input) {
        final byte[] data = input.getBytes(StandardCharsets.UTF_8);
        final ByteMatcher ours = BytePattern.compile(pattern).matcher();
        final Matcher theirs = jdk.matcher(input);

        final boolean weMatched = ours.find(data);
        final boolean theyMatched = theirs.find();
        final String context = "pattern=" + pattern + " input=" + quote(input);

        assertThat(weMatched).as("match/no-match: %s", context).isEqualTo(theyMatched);
        if (!weMatched) {
            return;
        }
        assertThat(ours.groupString(0)).as("match text: %s", context).isEqualTo(theirs.group());
        for (int group = 1; group <= theirs.groupCount(); group++) {
            assertThat(ours.matchedGroup(group))
                    .as("group %d participation: %s", group, context)
                    .isEqualTo(theirs.start(group) >= 0);
            if (theirs.start(group) >= 0) {
                assertThat(ours.groupString(group))
                        .as("group %d text: %s", group, context)
                        .isEqualTo(theirs.group(group));
            }
        }
    }

    /** Text drawn from the alphabet these patterns care about, multi-byte characters included. */
    private static String randomText(final Random random) {
        final String alphabet = "abcxX<>/ \néé中B1";
        final int length = random.nextInt(24);
        final StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }

    private static String quote(final String input) {
        return '"' + input.replace("\n", "\\n") + '"';
    }
}
