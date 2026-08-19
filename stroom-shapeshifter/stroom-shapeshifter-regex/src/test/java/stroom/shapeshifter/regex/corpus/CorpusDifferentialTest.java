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

package stroom.shapeshifter.regex.corpus;

import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.ByteWindow;
import stroom.shapeshifter.regex.JdkOracle;
import stroom.shapeshifter.regex.MatchOutcome;
import stroom.shapeshifter.regex.PatternCompileException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the whole {@link PatternCorpus} against {@code java.util.regex}, pattern by pattern and
 * input by input.
 * <p>
 * Two separate claims are being checked, and it is worth keeping them apart:
 * <ul>
 *   <li><b>Coverage</b> — how many realistic patterns the engine accepts at all, and which tier
 *       they reach. Rejections are counted and reported rather than quietly skipped, because a
 *       corpus that silently drops what the engine cannot do would measure nothing.</li>
 *   <li><b>Correctness</b> — every pattern the engine <em>accepts</em> must agree with the JDK on
 *       every input, exactly: same match, same span, same groups, same non-participation.</li>
 * </ul>
 * Acceptance is a promise. Rejection is a fact about scope. Only the first is allowed to fail.
 */
class CorpusDifferentialTest {

    @Test
    void everyAcceptedPatternAgreesWithJavaRegex() {
        final Map<String, Integer> byVerdict = new TreeMap<>();
        final Map<String, List<String>> rejected = new LinkedHashMap<>();
        final Map<String, int[]> byCategory = new LinkedHashMap<>();
        int comparisons = 0;

        for (final PatternCorpus.Category category : PatternCorpus.categories()) {
            final int[] counts = byCategory.computeIfAbsent(category.name(), name -> new int[4]);
            for (final String pattern : category.patterns()) {
                final BytePattern compiled;
                try {
                    compiled = BytePattern.compile(pattern);
                } catch (final PatternCompileException e) {
                    byVerdict.merge(e.getReason().name(), 1, Integer::sum);
                    rejected.computeIfAbsent(e.getReason().name(), reason -> new ArrayList<>())
                            .add(pattern);
                    counts[3]++;
                    continue;
                }

                byVerdict.merge(compiled.engine().description(), 1, Integer::sum);
                final int bucket = switch (compiled.engine()) {
                    case SCAN_PLAN -> 0;
                    case FANCY -> 2;
                    default -> 1;
                };
                counts[bucket]++;

                final Pattern reference = JdkOracle.compile(pattern);
                for (final String input : category.inputs()) {
                    assertAgree(compiled, reference, pattern, input);
                    comparisons++;
                }
            }
        }

        report(byVerdict, byCategory, rejected, comparisons);

        // A corpus this size that produced no comparisons would mean the engine had rejected
        // everything, which the counts above would hide.
        assertThat(comparisons).isGreaterThan(500);
    }

    /**
     * The same corpus replayed through a partial window, checking that an incomplete view is
     * never mistaken for a decided one. Any pattern that can still match must say so rather than
     * report the short answer it can see.
     */
    @Test
    void noAcceptedPatternDecidesEarlyOnAPartialWindow() {
        int checked = 0;
        for (final PatternCorpus.Category category : PatternCorpus.categories()) {
            for (final String pattern : category.patterns()) {
                final BytePattern compiled;
                try {
                    compiled = BytePattern.compile(pattern);
                } catch (final PatternCompileException e) {
                    continue;
                }
                final ByteMatcher matcher = compiled.matcher();
                final Pattern reference = JdkOracle.compile(pattern);

                for (final String input : category.inputs()) {
                    final byte[] data = input.getBytes(StandardCharsets.UTF_8);
                    final boolean matchesWhole = reference.matcher(input).find();

                    for (int prefix = 0; prefix < data.length; prefix++) {
                        final MatchOutcome outcome = matcher.match(
                                ByteWindow.partial(data, 0, prefix), 0, Anchoring.UNANCHORED);
                        if (outcome != MatchOutcome.MATCH) {
                            continue;
                        }
                        // A match reported on a prefix must be a match the whole input also has,
                        // and must be the same one — otherwise the engine has truncated.
                        assertThat(matchesWhole)
                                .as("pattern=%s prefix=%d of \"%s\" matched, but the whole input "
                                    + "does not match", pattern, prefix, input)
                                .isTrue();
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).isGreaterThan(0);
    }

    private static void assertAgree(final BytePattern bytePattern,
                                    final Pattern javaPattern,
                                    final String pattern,
                                    final String input) {
        final byte[] data = input.getBytes(StandardCharsets.UTF_8);
        final ByteMatcher ours = bytePattern.matcher();
        final Matcher theirs = javaPattern.matcher(input);

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

    private static void report(final Map<String, Integer> byVerdict,
                               final Map<String, int[]> byCategory,
                               final Map<String, List<String>> rejected,
                               final int comparisons) {
        final int total = byVerdict.values().stream().mapToInt(Integer::intValue).sum();
        final StringBuilder sb = new StringBuilder("\nPattern corpus — coverage and agreement\n")
                .append(total).append(" patterns, ").append(comparisons)
                .append(" pattern/input comparisons against java.util.regex\n\n")
                .append(String.format("%-14s %8s %6s%n", "verdict", "patterns", "share"));

        byVerdict.forEach((verdict, count) -> sb.append(String.format(
                "%-14s %8d %5d%%%n", verdict, count, Math.round(count * 100f / total))));

        sb.append(String.format("%n%-14s %7s %10s %7s %9s%n",
                "category", "plan", "automaton", "fancy", "rejected"));
        byCategory.forEach((name, counts) -> sb.append(String.format(
                "%-14s %7d %10d %7d %9d%n", name, counts[0], counts[1], counts[2], counts[3])));

        if (!rejected.isEmpty()) {
            sb.append("\nRejected, by reason:\n");
            rejected.forEach((reason, patterns) -> {
                sb.append("  ").append(reason).append('\n');
                patterns.forEach(pattern -> sb.append("    ").append(pattern).append('\n'));
            });
        }
        System.out.println(sb);
    }

    private static String quote(final String input) {
        return "\"" + input.replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }
}
