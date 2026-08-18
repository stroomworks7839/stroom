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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Places where this engine does not match {@code java.util.regex}, pinned so the behaviour is a
 * decision rather than an accident — and, where a divergence has since been fixed, so the
 * agreement stays pinned too.
 * <p>
 * They are recorded here rather than quietly excluded from the differential suite, because a
 * suite that hides its disagreements is not evidence of anything.
 *
 * <h2>One of these turned out to be a defect, and has been fixed</h2>
 * The empty-repetition case was originally filed here as a difference of opinion, on the grounds
 * that {@code java.util.regex} looked idiosyncratic. Running the Rust {@code regex} corpus
 * settled it: <b>Rust agreed with the JDK against this engine</b>, and two independent references
 * agreeing is not a difference of opinion. It was also broader than first recorded — it changed
 * <em>whether a match was found at all</em>, not merely what a group held.
 * <p>
 * It is now fixed, by an empty-iteration guard resolved at compile time
 * ({@code Nfa.MARK}/{@code Nfa.PROGRESS}), and the test that pinned the divergence has become the
 * test that pins the agreement.
 */
class KnownDivergenceTest {

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * <b>Was a defect, now fixed — a repetition whose body can match empty.</b>
     * <p>
     * An iteration that consumes nothing ends the loop, in this engine as in both references. The
     * guard is resolved entirely at compile time: exactly one byte is consumed between one
     * epsilon closure and the next, so an iteration consumed nothing precisely when its
     * {@code MARK} lies on the same closure path, and the loop-back edge is then compiled to the
     * loop exit instead.
     * <p>
     * Routing to the <em>exit</em> rather than killing the path is the part that matters:
     * {@code (?:|a)*} against {@code "aaa"} must match nothing, and it does so because the path
     * that matched nothing is the preferred one.
     */
    @Test
    void repetitionsWithAnEmptyBodyNowAgree() {
        assertAgrees("(a*)+", "aa");
        assertAgrees("(a?)*", "ab");
        assertAgrees("(a|)+", "ab");
        assertAgrees("((a*)*)*", "aab");
        assertAgrees("(?:x?)*y", "aaay");

        // The one that changed which match is found, not merely what a group holds.
        final ByteMatcher empty = BytePattern.compile("(?:|a)*").matcher();
        assertThat(empty.find(bytes("aaa"))).isTrue();
        assertThat(empty.end()).as("an iteration that consumes nothing must end the loop")
                .isZero();

        // The construct is still redundant, so the warning stays.
        assertThat(BytePattern.compile("(a*)+").warnings())
                .singleElement()
                .asString()
                .contains("can match nothing");
        assertThat(BytePattern.compile("(a+)+").warnings()).isEmpty();
    }

    /**
     * An assertion on one branch used to hide a later branch that had none: the closure kept only
     * the first arrival at each instruction, so when the assertion failed at run time the thread
     * was never added at all and the match was lost entirely. Only an unasserted path may close a
     * target off now.
     */
    @Test
    void anAssertedBranchNoLongerHidesAnUnassertedOne() {
        assertAgrees("(?:\\b|)a", "ba");
        assertAgrees("(?:\\b|)a", " a");
        assertAgrees("(?:$|)a", "ba");
        assertAgrees("(?:\\B|)a", "ba");
        assertAgrees("(?:^|)a", "ba");
    }

    /**
     * <b>Divergence — a capture nested inside two repetitions.</b>
     * <p>
     * This engine reports the last iteration that matched, which is the rule everywhere else.
     * {@code java.util.regex} reports an earlier one, apparently as a consequence of how it
     * restores group boundaries while backtracking. Seven other nested-capture shapes were
     * checked and agree exactly, so this is narrow rather than systematic.
     */
    @Test
    void capturesUnderDoublyNestedRepetitionDiffer() {
        // Iteration 2 of the outer group matched "cd", so its inner capture last held "d".
        assertDiffers("((\\S){0,3}x){2}", "abxcdx", 2, "d", "b");
    }

    /** Everything adjacent to the remaining shape agrees, which is what makes it narrow. */
    @Test
    void neighbouringShapesAgree() {
        assertAgrees("((a)(b))+", "abab");
        assertAgrees("((a){1,2})+", "aa");
        assertAgrees("(a(b)?)+", "aba");
        assertAgrees("((a|b)+c)+", "abcbac");
        assertAgrees("(x(y){2})+", "xyyxyy");
        assertAgrees("((a)*b)+", "aabab");
        assertAgrees("(\\S){0,3}", "abc");
        assertAgrees("(a+)*", "aaa");
    }

    private static void assertDiffers(final String pattern,
                                      final String input,
                                      final int group,
                                      final String ourValue,
                                      final String javaValue) {
        final ByteMatcher ours = BytePattern.compile(pattern).matcher();
        final Matcher theirs = JdkOracle.compile(pattern).matcher(input);

        assertThat(ours.find(bytes(input))).isTrue();
        assertThat(theirs.find()).isTrue();

        // The match itself agrees; only the group differs.
        assertThat(ours.groupString(0))
                .as("the overall match must still agree for %s", pattern)
                .isEqualTo(theirs.group());

        assertThat(ours.groupString(group)).as("our value for %s", pattern).isEqualTo(ourValue);
        assertThat(theirs.group(group)).as("the JDK value for %s", pattern).isEqualTo(javaValue);
    }

    private static void assertAgrees(final String pattern, final String input) {
        final BytePattern compiled = BytePattern.compile(pattern);
        final ByteMatcher ours = compiled.matcher();
        final Matcher theirs = JdkOracle.compile(pattern).matcher(input);

        assertThat(ours.find(bytes(input))).isEqualTo(theirs.find());
        assertThat(ours.groupString(0)).isEqualTo(theirs.group());
        for (int group = 1; group <= theirs.groupCount(); group++) {
            assertThat(ours.matchedGroup(group))
                    .as("group %d participation for %s", group, pattern)
                    .isEqualTo(theirs.start(group) >= 0);
            if (theirs.start(group) >= 0) {
                assertThat(ours.groupString(group))
                        .as("group %d for %s", group, pattern)
                        .isEqualTo(theirs.group(group));
            }
        }
    }
}
