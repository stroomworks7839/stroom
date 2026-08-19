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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differential testing against {@code java.util.regex}.
 * <p>
 * For the subset both engines share — ASCII input, no lookaround, no backreferences — the JDK
 * is a mature oracle, so a scan plan can be proved correct against it without the tier 1 engine
 * existing. That is why the phasing changed: plans no longer have to wait for a Pike VM to be
 * checked against.
 * <p>
 * Both tiers are covered: patterns the compiler decides are unambiguous run as scan plans, and
 * deliberately ambiguous ones run on the NFA simulation. Since the JDK is the oracle for both,
 * agreeing with it means the two tiers also agree with each other — which is the invariant the
 * whole two-tier design depends on.
 */
class DifferentialTest {

    /** Patterns that are one-pass, so this engine compiles them, and that the JDK also accepts. */
    private static final List<String> SHARED_PATTERNS = List.of(
            "^([^,]+),([^,]+),([^,]+)$",
            "^([^,]*),([^,]*)$",
            "^(\\S+) (\\S+) (\\S+)$",
            "^(\\w+)=(\\w+)$",
            "^([A-Z]+) +(\\S+) - (.*)$",
            "^\\[([^\\]]*)\\] (.*)$",
            "^([0-9]+) \\[([^\\]]*)\\] (.*)$",
            "^(error|INFO) (.*)$",
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}$",
            "^(?:ab)+c$",
            "^a*b$",
            "^x?y+z$",
            "^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$",
            "[0-9]+",
            "[a-z]+=[^,]+",
            "hello",
            "^(a|bb|ccc)$");

    private static final List<String> INPUTS = List.of(
            "",
            "a",
            "abc",
            "one,two,three",
            "one,,three",
            ",,",
            "server01 ERROR something happened",
            "WARN  disk - nearly full",
            "[2026-08-17] the message",
            "42 [thread-1] started",
            "INFO started",
            "error stopped",
            "2026-08-17",
            "ababababc",
            "aaab",
            "xyyyz",
            "yz",
            "192.168.1.1",
            "no digits here",
            "key=value",
            "a,b",
            "bb",
            "ccc",
            "hello there",
            "say hello");

    @Test
    void agreesWithJavaRegexOnCuratedInputs() {
        int compared = 0;
        for (final String pattern : SHARED_PATTERNS) {
            final BytePattern bytePattern = BytePattern.compile(pattern);
            final Pattern javaPattern = JdkOracle.compile(pattern);
            for (final String input : INPUTS) {
                assertAgree(bytePattern, javaPattern, pattern, input);
                compared++;
            }
        }
        assertThat(compared).isEqualTo(SHARED_PATTERNS.size() * INPUTS.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void agreesWithJavaRegexOnGeneratedInputs(final int seed) {
        final Random random = new Random(seed);
        for (final String pattern : SHARED_PATTERNS) {
            final BytePattern bytePattern = BytePattern.compile(pattern);
            final Pattern javaPattern = JdkOracle.compile(pattern);
            for (int i = 0; i < 200; i++) {
                assertAgree(bytePattern, javaPattern, pattern, randomAscii(random));
            }
        }
    }

    /**
     * Every pattern this engine accepts must agree with the JDK. A pattern it rejects is not a
     * failure — it means tier 1 or the java dialect is needed — but acceptance is a promise of
     * identical behaviour.
     */
    @Test
    void acceptedCorpusPatternsAgreeWithJavaRegex() {
        final List<String> accepted = new ArrayList<>();
        for (final String pattern : List.of(
                "(^\\S+) - (.*)",
                "^([0-9]+) \\[([^\\]]*)\\] (.*)$",
                "^([A-Z]+) +([\\S]+) - (.*)",
                "^([^ ]+) ([^ ]+) ([^ /]*)/([^ ]*)",
                "^(error|INFO) (.*)$",
                "^----\\n",
                "^INFO (.*)\\n",
                "^\\[([^\\]]*)\\] (.*)$")) {
            final BytePattern bytePattern = BytePattern.compile(pattern);
            final Pattern javaPattern = JdkOracle.compile(pattern);
            accepted.add(pattern);
            for (final String input : INPUTS) {
                assertAgree(bytePattern, javaPattern, pattern, input);
            }
            final Random random = new Random(pattern.hashCode());
            for (int i = 0; i < 200; i++) {
                assertAgree(bytePattern, javaPattern, pattern, randomAscii(random));
            }
        }
        // These are the tier 0 patterns the corpus analysis found in this repository's DS3
        // configs; all of them must compile and behave identically.
        assertThat(accepted).hasSize(8);
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

        // Compare the matched text rather than offsets: theirs are UTF-16 char indices and ours
        // are byte offsets, which differ as soon as the input is not ASCII.
        assertThat(ours.groupString(0)).as("match text: %s", context).isEqualTo(theirs.group());
        for (int group = 1; group <= javaPattern.matcher("").groupCount(); group++) {
            assertThat(ours.matchedGroup(group))
                    .as("group %d participation: %s", group, context)
                    .isEqualTo(theirs.start(group) >= 0);
            if (theirs.start(group) >= 0) {
                assertThat(ours.groupString(group)).as("group %d text: %s", group, context)
                        .isEqualTo(theirs.group(group));
            }
        }

        final boolean ascii = input.chars().allMatch(c -> c < 0x80);
        if (ascii) {
            // Where a char index and a byte offset coincide, check the offsets too.
            assertThat(ours.start()).as("match start: %s", context).isEqualTo(theirs.start());
            assertThat(ours.end()).as("match end: %s", context).isEqualTo(theirs.end());
        }
    }

    /**
     * The same comparison over non-ASCII input, which the byte engine can only pass if character
     * classes are compiled through UTF-8 and matches consume whole characters. Before that work
     * these patterns silently returned spans that split a character.
     */
    @ParameterizedTest
    @ValueSource(ints = {11, 12, 13, 14})
    void agreesWithJavaRegexOnNonAsciiInput(final int seed) {
        final List<String> patterns = List.of(
                "^(.)(.)",
                "^(.*)$",
                "^(.{3})$",
                "^([^,]+),([^,]+)$",
                "^(\\S+) (\\S+)$",
                "^([a-zé]+)=(\\d+)$",
                "^([\\u00C0-\\u00FF]+)$",
                "^([\\u0400-\\u052F]+)$",
                "^(\\w+)-(.*)$",
                "café=(\\d+)");
        final List<String> inputs = new ArrayList<>(List.of(
                "café=42",
                "café,thé",
                "étage",
                "éàü",
                "éàüz",
                "Привет",
                "Привет мир",
                "naïve-approach",
                "a-é",
                "€100",
                "😀 emoji",
                ""));
        final Random random = new Random(seed);
        for (int i = 0; i < 150; i++) {
            inputs.add(randomUnicode(random));
        }

        for (final String pattern : patterns) {
            final BytePattern bytePattern = BytePattern.compile(pattern);
            final Pattern javaPattern = JdkOracle.compile(pattern);
            for (final String input : inputs) {
                assertAgree(bytePattern, javaPattern, pattern, input);
            }
        }
    }

    /**
     * Every code point, through every shorthand, against the JDK.
     * <p>
     * The Unicode definitions are easy to get <em>nearly</em> right — {@code Character.isLetter}
     * is not {@code Alphabetic}, and {@code Character.isWhitespace} is not the {@code White_Space}
     * property — and a near miss shows up as a handful of characters behaving differently, which
     * no sampled corpus would ever hit. Both of those were in fact wrong when the shorthands
     * first became Unicode, and this test is what found them.
     */
    @Test
    void unicodeShorthandsAgreeWithTheJdkOnEveryCodePoint() {
        for (final String shorthand : List.of("\\w", "\\d", "\\s")) {
            final ByteMatcher ours = BytePattern.compile("^" + shorthand + "$").matcher();
            final Pattern theirs = JdkOracle.compile("^" + shorthand + "$");

            for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
                if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                    continue; // Not encodable, so neither engine can be asked about it.
                }
                final String character = new String(Character.toChars(codePoint));
                assertThat(ours.find(character.getBytes(StandardCharsets.UTF_8)))
                        .as("%s on U+%04X", shorthand, codePoint)
                        .isEqualTo(theirs.matcher(character).find());
            }
        }
    }

    /**
     * Ambiguous patterns, which fall to the tier 1 NFA simulation. These are the ones the scan
     * plan cannot decide with a single byte of lookahead — greedy repeats over permissive
     * classes, and alternations with overlapping first-sets — so they exercise the part of the
     * engine that genuinely has to consider several possibilities at once.
     */
    @Test
    void tierOnePatternsAgreeWithJavaRegex() {
        final List<String> ambiguous = List.of(
                "^(.+):(.+)$",
                "^(.*),(.*)$",
                ".*,",
                "^([0-9]+) \\[([^\\]]*)\\] (\\(.*\\))$",
                "argc=(.+) a0=(\\S+) (.+)",
                "\"\\s*(\\d+.\\d+.\\d+.\\d+) - ([^\"]+)",
                "^(\\s*node=)?(\\S+) type=(\\S+)",
                "^([^ ]+) (\"([^\"]*)\"|([^ ]+)) (.*)$",
                "^(a|ab|abc)(.*)$",
                "(x+)(x+)$");

        final List<String> inputs = new ArrayList<>(List.of(
                "", "a:b", "a:b:c", "one,two", "one,two,three", "abc", "abd",
                "42 [thread-1] (detail here)",
                "argc=3 a0=/bin/sh rest of it",
                "\" 192.168.1.1 - the message\"",
                "node= host type=EXECVE",
                "1.2.3.4",
                "host \"quoted value\" trailing text",
                "host unquoted trailing text",
                "xxxx", "x", "abcdef"));
        final Random random = new Random(99);
        for (int i = 0; i < 300; i++) {
            inputs.add(randomAscii(random));
        }

        for (final String pattern : ambiguous) {
            final BytePattern bytePattern = BytePattern.compile(pattern);
            assertThat(bytePattern.tier())
                    .as("%s should need an automaton", pattern)
                    .isEqualTo(Engine.SIMULATE.ordinal());
            final Pattern javaPattern = JdkOracle.compile(pattern);
            for (final String input : inputs) {
                assertAgree(bytePattern, javaPattern, pattern, input);
            }
        }
    }

    /**
     * The invariant the whole design rests on, over every engine that can run a pattern.
     * <p>
     * A scan plan, a bounded backtracker, an NFA simulation and the unbounded backtracker must
     * produce identical results —
     * same match, same span, same groups, same non-participation — on every input. Two of them
     * are from different algorithm families entirely, depth-first with backtracking against
     * breadth-first in lockstep, so a bug that produced the same wrong answer in both would have
     * to be a mistake in the shared program rather than in either engine. That is what makes
     * agreement evidence rather than coincidence.
     * <p>
     * Each engine is pinned rather than chosen, because the compiler would otherwise never run
     * the simulation on inputs this short and it would go untested.
     */
    @ParameterizedTest
    @ValueSource(ints = {31, 32, 33})
    void allThreeEnginesAgreeWithEachOther(final int seed) {
        final Random random = new Random(seed);
        for (final String pattern : SHARED_PATTERNS) {
            final BytePattern backtrack =
                    BytePattern.compileForcing(Engine.BACKTRACK, pattern, Set.of());
            final BytePattern simulate =
                    BytePattern.compileForcing(Engine.SIMULATE, pattern, Set.of());
            final BytePattern fancy =
                    BytePattern.compileForcing(Engine.FANCY, pattern, Set.of());
            final BytePattern treeWalk =
                    BytePattern.compileForcing(Engine.TREE, pattern, Set.of());
            final Pattern javaPattern = JdkOracle.compile(pattern);
            final BytePattern scanPlan = BytePattern.compile(pattern);

            for (int i = 0; i < 150; i++) {
                final String input = randomAscii(random);
                assertAgree(backtrack, javaPattern, pattern, input);
                assertAgree(simulate, javaPattern, pattern, input);
                assertSameResult(backtrack, simulate, pattern, input);
                assertSameResult(simulate, fancy, pattern, input);
                assertSameResult(fancy, treeWalk, pattern, input);
                if (scanPlan.tier() == Engine.SCAN_PLAN.ordinal()) {
                    assertSameResult(scanPlan, backtrack, pattern, input);
                }
            }
        }
    }

    /** The same, over the deliberately ambiguous patterns, which no scan plan can run. */
    @Test
    void bothAutomatonEnginesAgreeOnAmbiguousPatterns() {
        final List<String> ambiguous = List.of(
                "^(.+):(.+)$",
                "^(.*),(.*)$",
                "^([^ ]+) (\"([^\"]*)\"|[^ ]+) (.*)$",
                "^(a|ab|abc)(.*)$",
                "(x+)(x+)$",
                "^(\\S+)(?:\\s+(\\S+))?$");
        final Random random = new Random(77);
        for (final String pattern : ambiguous) {
            final BytePattern backtrack =
                    BytePattern.compileForcing(Engine.BACKTRACK, pattern, Set.of());
            final BytePattern simulate =
                    BytePattern.compileForcing(Engine.SIMULATE, pattern, Set.of());
            final Pattern javaPattern = JdkOracle.compile(pattern);
            for (int i = 0; i < 400; i++) {
                final String input = randomAscii(random);
                assertAgree(backtrack, javaPattern, pattern, input);
                assertSameResult(backtrack, simulate, pattern, input);
            }
        }
    }

    /**
     * The constructs beyond the RE2 subset, against the JDK — which supports every one of them
     * natively, so it is a true oracle here rather than a translation. This is where the fancy
     * tier's semantics are established: backreference participation, lookbehind lengths, atomic
     * commitment, the folding rules — all as the JDK means them.
     */
    @ParameterizedTest
    @ValueSource(ints = {51, 52, 53})
    void fancyConstructsAgreeWithJavaRegex(final int seed) {
        final List<String> patterns = List.of(
                "(\\w+) \\1",
                "^(a+)\\1$",
                "(a|b)\\1",
                "(?:(a)|b)\\1",
                "^(\\2two|(one))+$",
                "(x+)(x+)\\2",
                "(?i)(\\w+) \\1",
                "(a)(?i:\\1)",
                "foo(?=bar)",
                "foo(?!bar)",
                "(?=(a+))a*b",
                "(?<=a)b",
                "(?<!a)b",
                "(?<=ab|x)c",
                "(?<=(a{1,3}))b",
                "(?>a|ab)c",
                "(?>a+)ab",
                "a*+b",
                "a?+ab",
                "([\\w ]+)((?>,|$))",
                "\\Qa.b\\E.");
        final Random random = new Random(seed);
        for (final String pattern : patterns) {
            final BytePattern bytePattern = BytePattern.compile(pattern);
            if (!pattern.contains("\\Q")) { // quoting alone is not fancy
                assertThat(bytePattern.engine())
                        .as("%s should need the fancy tier", pattern)
                        .isEqualTo(Engine.TREE); // the fancy tier's primary engine since D31
            }
            final BytePattern treeWalk =
                    BytePattern.compileForcing(Engine.TREE, pattern, Set.of());
            final Pattern javaPattern = JdkOracle.compile(pattern);
            for (int i = 0; i < 300; i++) {
                final String input = randomAscii(random);
                assertAgree(bytePattern, javaPattern, pattern, input);
                assertAgree(treeWalk, javaPattern, pattern, input);
            }
        }
    }

    /**
     * The bounded backtracker's visited set survives its generation wrap.
     * <p>
     * The set is generation-stamped rather than cleared per search, and the wrap-time clear
     * was once sized to the current search — so a small search's wrap left stale marks above
     * its cells, and 126 searches later a different input met its own stale generation and
     * lost a real match to a false "already visited". One matcher, hundreds of mixed-size
     * searches: a pipeline's exact shape, and no other test's.
     */
    @Test
    void visitedSetSurvivesItsGenerationWrap() {
        final ByteMatcher matcher = BytePattern
                .compileForcing(Engine.BACKTRACK, "^(.+):(.+)$", Set.of()).matcher();
        final byte[] a = bytesOf("key:value with some padding to make it long");
        final byte[] b = bytesOf("another_longer_key_here:and_its_value_xyzzy");
        final byte[] tiny = bytesOf("a:b");

        for (int i = 1; i <= 126; i++) {
            assertThat(matcher.find(a)).as("warm search %d", i).isTrue();
        }
        assertThat(matcher.find(b)).isTrue();   // generation 127 marks B's cells
        assertThat(matcher.find(tiny)).isTrue(); // the wrap
        for (int g = 2; g <= 126; g++) {
            assertThat(matcher.find(a)).as("post-wrap search at generation %d", g).isTrue();
        }
        // Generation 127 again: B's cells must not remember the last cycle.
        assertThat(matcher.find(b)).as("the wrapped generation meets its stale marks").isTrue();
    }

    private static byte[] bytesOf(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The scan plan against the automaton, kept from when there were only two engines. Narrower
     * than the test above, and retained because it is the one that pins the scan plan itself:
     * the plan is only ever an optimisation, so any disagreement is a bug in it rather than a
     * difference of opinion.
     */
    @ParameterizedTest
    @ValueSource(ints = {21, 22, 23})
    void bothTiersAgreeWithEachOther(final int seed) {
        final Random random = new Random(seed);
        for (final String pattern : SHARED_PATTERNS) {
            final BytePattern tierZero = BytePattern.compile(pattern);
            if (tierZero.tier() != 0) {
                continue;
            }
            final BytePattern tierOne = BytePattern.compileForcingNfa(pattern, Set.of());
            assertThat(tierOne.tier()).isEqualTo(Engine.SIMULATE.ordinal());
            final Pattern javaPattern = JdkOracle.compile(pattern);

            for (int i = 0; i < 150; i++) {
                final String input = randomAscii(random);
                assertAgree(tierZero, javaPattern, pattern, input);
                assertAgree(tierOne, javaPattern, pattern, input);
                assertSameResult(tierZero, tierOne, pattern, input);
            }
        }
    }

    /** Compares the two engines against each other, group by group. */
    private static void assertSameResult(final BytePattern a,
                                         final BytePattern b,
                                         final String pattern,
                                         final String input) {
        final byte[] data = input.getBytes(StandardCharsets.UTF_8);
        final ByteMatcher first = a.matcher();
        final ByteMatcher second = b.matcher();
        final String context = "pattern=" + pattern + " input=" + quote(input);

        final boolean firstMatched = first.find(data);
        assertThat(second.find(data)).as("tier agreement on match: %s", context)
                .isEqualTo(firstMatched);
        if (!firstMatched) {
            return;
        }
        assertThat(second.start()).as("tier agreement on start: %s", context)
                .isEqualTo(first.start());
        assertThat(second.end()).as("tier agreement on end: %s", context).isEqualTo(first.end());
        for (int group = 1; group <= a.groupCount(); group++) {
            assertThat(second.matchedGroup(group))
                    .as("tier agreement on group %d participation: %s", group, context)
                    .isEqualTo(first.matchedGroup(group));
            if (first.matchedGroup(group)) {
                assertThat(second.groupString(group))
                        .as("tier agreement on group %d: %s", group, context)
                        .isEqualTo(first.groupString(group));
            }
        }
    }

    private static String randomUnicode(final Random random) {
        // Deliberately mixes byte lengths so boundaries between them are exercised.
        final String[] alphabet = {
                "a", "z", "0", ",", "-", "=", " ", "\n",
                "é", "à", "ü", "ß",          // 2-byte
                "Ω", "Привет".substring(0, 1), "€",  // 2- and 3-byte
                "日", "本",                   // 3-byte
                "😀"};                       // 4-byte
        final int length = random.nextInt(12);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    private static String randomAscii(final Random random) {
        // A small alphabet makes accidental matches likely, which is where disagreements hide.
        final char[] alphabet = "abcxyz01, []-=\n\tERINFO/".toCharArray();
        final int length = random.nextInt(24);
        final StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    private static String quote(final String input) {
        return "\"" + input.replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }
}
