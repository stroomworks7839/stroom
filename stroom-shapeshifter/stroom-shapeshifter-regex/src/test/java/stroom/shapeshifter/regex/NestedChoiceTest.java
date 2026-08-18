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
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deeply nested and overlapping alternation — the shape real DS3 configs use most, and the one
 * the one-pass analysis is most likely to reject.
 * <p>
 * Nesting itself is never a problem: an alternation whose branches have disjoint first-sets
 * compiles to a byte dispatch table however deep it is, and one whose branches overlap falls to
 * the NFA simulation, which considers every branch simultaneously. Both are checked against
 * {@code java.util.regex} here, group by group, because with nested choice the interesting
 * question is not whether it matches but <em>which branch won</em>.
 */
class NestedChoiceTest {

    /** The Apache-log pattern from this repository's DS3 configs: 19 groups, 5 nested choices. */
    private static final String APACHE_LOG =
            "^([^ ]+) (\"([^\"]*)\"|([^ ]+)) (\"([^\"]*)\"|([^ ]+)) \\[([^\\]]+)\\] "
            + "(\"([^\"]+)\"|([^ ]+)) ([^ ]+) ([^ ]+) (\"([^\"]+)\"|([^ ]+)) (\"([^\"]+)\"|([^ ]+))";

    private static final List<String> NESTED_PATTERNS = List.of(
            // Overlapping branches — only the NFA can decide these.
            "^((a|ab)(c|bc))$",
            "^((a|b)(c|d)|(e|f)(g|h))$",
            "^(x(y|z)?|w)+$",
            "^((a|(b|(c|(d|e))))+)$",
            "^(-|(\\d+|\\w+))(,(-|(\\d+|\\w+)))*$",
            "^(\"([^\"]*)\"|([^,]*))(,(\"([^\"]*)\"|([^,]*)))*$",
            // Disjoint branches nested several deep — these should stay on the scan plan.
            "^(a(b(c|d)|e(f|g))|h(i(j|k)|l(m|n)))$",
            "^(GET|POST|PUT|DELETE) (/\\S*) (HTTP/(1\\.0|1\\.1|2))$");

    @Test
    void apacheLogPatternMatchesLikeJavaRegex() {
        final String line = "192.168.1.1 \"-\" \"user agent\" [17/Aug/2026:14:30:00 +0000] "
                            + "\"GET /index.html HTTP/1.1\" 200 4213 \"http://ref\" \"Mozilla/5.0\"";
        assertAgree(APACHE_LOG, line);

        // The same line with the quoted fields unquoted, so the other branches win.
        assertAgree(APACHE_LOG, "192.168.1.1 - agent [17/Aug/2026:14:30:00 +0000] "
                                + "GET 200 4213 ref Mozilla");
    }

    @Test
    void apacheLogPatternCapturesTheRightBranch() {
        final String line = "192.168.1.1 \"-\" \"user agent\" [17/Aug/2026:14:30:00 +0000] "
                            + "\"GET /index.html HTTP/1.1\" 200 4213 \"http://ref\" \"Mozilla/5.0\"";
        final ByteMatcher matcher = BytePattern.compile(APACHE_LOG).matcher();
        assertThat(matcher.find(line.getBytes(StandardCharsets.UTF_8))).isTrue();

        assertThat(matcher.groupString(1)).isEqualTo("192.168.1.1");
        // Group 2 is the whole choice, 3 the quoted branch's inner capture, 4 the unquoted branch.
        assertThat(matcher.groupString(2)).isEqualTo("\"-\"");
        assertThat(matcher.groupString(3)).isEqualTo("-");
        assertThat(matcher.matchedGroup(4)).as("the unquoted branch must not have taken part").isFalse();
        assertThat(matcher.groupString(8)).isEqualTo("17/Aug/2026:14:30:00 +0000");
        // Group 9 is the whole choice including its quotes; 10 is the quoted branch's contents.
        assertThat(matcher.groupString(9)).isEqualTo("\"GET /index.html HTTP/1.1\"");
        assertThat(matcher.groupString(10)).isEqualTo("GET /index.html HTTP/1.1");
    }

    @Test
    void nestedChoicesCompileAndAgree() {
        final List<String> inputs = List.of(
                "", "a", "ab", "abc", "ac", "abbc", "ad", "bc", "bd", "eg", "eh", "fg",
                "x", "xy", "xz", "w", "wxy", "xyxzw",
                "abcde", "e", "d", "cde",
                "-", "1", "a1", "-,-", "1,2,-", "a,-,3",
                "\"q\",plain", "plain,\"q\"", "\"a,b\",c", ",,",
                "abcd", "abef", "hijk", "hilm", "aef", "hij",
                "GET /index.html HTTP/1.1", "POST / HTTP/2", "PATCH / HTTP/1.1");

        for (final String pattern : NESTED_PATTERNS) {
            for (final String input : inputs) {
                assertAgree(pattern, input);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {31, 32, 33, 34})
    void nestedChoicesAgreeOnGeneratedInput(final int seed) {
        final Random random = new Random(seed);
        final List<String> inputs = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            inputs.add(randomInput(random));
        }
        for (final String pattern : NESTED_PATTERNS) {
            for (final String input : inputs) {
                assertAgree(pattern, input);
            }
        }
    }

    @Test
    void disjointNestingStaysOnTheScanPlan() {
        // Depth does not force tier 1 — only ambiguity does. This nests three deep.
        assertThat(BytePattern.compile("^(a(b(c|d)|e(f|g))|h(i(j|k)|l(m|n)))$").tier()).isZero();

        // POST and PUT share a first byte, and so do 1\.0 and 1\.1, but factoring the shared
        // prefix out makes both decidable one byte at a time.
        assertThat(BytePattern.compile("^(GET|POST|PUT|DELETE) (/\\S*) (HTTP/(1\\.0|1\\.1|2))$").tier())
                .isZero();

        // Sharing a whole prefix is not ambiguity — factoring resolves it.
        assertThat(BytePattern.compile("(abc|abd)").tier()).isZero();
        assertThat(BytePattern.compile("^(INFO|WARN|ERROR|DEBUG|TRACE): (.*)$").tier()).isZero();

        // Genuinely overlapping branches still need tier 1, however shallow.
        assertThat(BytePattern.compile("^((a|ab)(c|bc))$").tier()).isEqualTo(1);
        assertThat(BytePattern.compile(APACHE_LOG).tier()).isEqualTo(1);
    }

    @Test
    void factoringSharedPrefixesPreservesPreferenceOrder() {
        // Factoring must not reorder branches: with leftmost-first semantics, (ab|a) prefers the
        // longer branch and (a|ab) the shorter, and both must survive the rewrite.
        final ByteMatcher longerFirst = BytePattern.compile("^(ab|a)").matcher();
        assertThat(longerFirst.find("ab".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(longerFirst.groupString(1)).isEqualTo("ab");

        final ByteMatcher shorterFirst = BytePattern.compile("^(a|ab)").matcher();
        assertThat(shorterFirst.find("ab".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(shorterFirst.groupString(1)).isEqualTo("a");

        // And a group boundary blocks factoring, because the atom cannot move out of the capture.
        assertAgree("^((a)b|(a)c)$", "ab");
        assertAgree("^((a)b|(a)c)$", "ac");

        // (a|ab) is the case that exposed the preference rule: a preferred empty branch after
        // factoring competes with what follows, so this must not reach the scan plan.
        assertThat(BytePattern.compile("^(a|ab)").tier()).isEqualTo(1);
    }

    private static String randomInput(final Random random) {
        final String[] alphabet = {"a", "b", "c", "d", "e", "f", "g", "h", "x", "y", "z", "w",
                "-", ",", "\"", "1", "2", " ", "/", "GET", "HTTP/1.1"};
        final int length = random.nextInt(10);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    private static void assertAgree(final String pattern, final String input) {
        final byte[] data = input.getBytes(StandardCharsets.UTF_8);
        final ByteMatcher ours = BytePattern.compile(pattern).matcher();
        final Matcher theirs = JdkOracle.compile(pattern).matcher(input);

        final boolean weMatched = ours.find(data);
        final boolean theyMatched = theirs.find();
        final String context = "pattern=" + pattern + " input=\"" + input + "\"";

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
}
