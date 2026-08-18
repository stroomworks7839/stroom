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

import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.JdkOracle;
import stroom.shapeshifter.regex.PatternCompileException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Patterns synthesised from the grammar, matched against synthesised input, and compared with
 * {@code java.util.regex}.
 * <p>
 * A hand-written corpus only contains combinations someone thought of. This covers the ones
 * nobody would: quantified groups inside alternations inside repeats, empty alternatives,
 * nested optionals, classes that overlap what follows them. Those interactions are exactly
 * where a two-tier engine can diverge, because the tier decision depends on precisely such
 * structural detail.
 * <p>
 * The alphabet is deliberately tiny, so generated patterns and generated inputs collide often.
 * A large alphabet would mostly produce non-matches, which agree trivially and prove little.
 */
class GeneratedPatternTest {

    private static final String[] ATOMS = {
            "a", "b", "c", "x", ",", ":", "-", "0", "1",
            "[ab]", "[^a]", "[a-c]", "[0-9]", "\\d", "\\w", "\\s", "\\S", "."};

    private static final String[] QUANTIFIERS = {
            "", "", "", "?", "*", "+", "??", "*?", "+?", "{2}", "{1,2}", "{0,3}"};

    @ParameterizedTest
    @ValueSource(ints = {101, 102, 103, 104, 105, 106, 107, 108})
    void generatedPatternsAgreeWithJavaRegex(final int seed) {
        final Random random = new Random(seed);
        int compared = 0;
        int rejected = 0;

        for (int i = 0; i < 300; i++) {
            final String pattern = generatePattern(random, 3);

            final Pattern reference;
            try {
                reference = JdkOracle.compile(pattern);
            } catch (final RuntimeException e) {
                continue; // the generator produced something the JDK dislikes; not our concern
            }

            final BytePattern compiled;
            try {
                compiled = BytePattern.compile(pattern);
            } catch (final PatternCompileException e) {
                rejected++;
                continue; // outside the supported subset, which is a scope fact rather than a bug
            }

            for (int j = 0; j < 12; j++) {
                assertAgree(compiled, reference, pattern, generateInput(random));
                compared++;
            }
        }

        // If almost everything were rejected this test would pass while checking nothing.
        assertThat(compared).as("comparisons made (rejected %d patterns)", rejected)
                .isGreaterThan(1000);
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
        final String context = "pattern=" + pattern + " input=\"" + input + "\" tier="
                               + bytePattern.tier();

        assertThat(weMatched).as("match/no-match: %s", context).isEqualTo(theyMatched);
        if (!weMatched) {
            return;
        }
        assertThat(ours.start()).as("start: %s", context).isEqualTo(theirs.start());
        assertThat(ours.end()).as("end: %s", context).isEqualTo(theirs.end());
        for (int group = 1; group <= theirs.groupCount(); group++) {
            assertThat(ours.matchedGroup(group))
                    .as("group %d participation: %s", group, context)
                    .isEqualTo(theirs.start(group) >= 0);
            if (theirs.start(group) >= 0) {
                assertThat(ours.groupString(group))
                        .as("group %d: %s", group, context)
                        .isEqualTo(theirs.group(group));
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Generation
    // -----------------------------------------------------------------------------------

    private static String generatePattern(final Random random, final int depth) {
        final StringBuilder sb = new StringBuilder();
        if (random.nextInt(6) == 0) {
            sb.append('^');
        }
        sb.append(generateAlternation(random, depth));
        if (random.nextInt(6) == 0) {
            sb.append('$');
        }
        return sb.toString();
    }

    private static String generateAlternation(final Random random, final int depth) {
        final int branches = random.nextInt(3) == 0
                ? 2 + random.nextInt(2)
                : 1;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < branches; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(generateSequence(random, depth));
        }
        return sb.toString();
    }

    private static String generateSequence(final Random random, final int depth) {
        final int items = 1 + random.nextInt(3);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items; i++) {
            sb.append(generateQuantified(random, depth));
        }
        return sb.toString();
    }

    private static String generateQuantified(final Random random, final int depth) {
        final String atom = generateAtom(random, depth);
        final String quantifier = QUANTIFIERS[random.nextInt(QUANTIFIERS.length)];
        // Two shapes are excluded because what their capture groups hold is a documented
        // divergence rather than a property worth generating thousands of cases about; both are
        // pinned by KnownDivergenceTest. Everything else is fair game.
        if (!quantifier.isEmpty() && (matchesEmpty(atom) || containsQuantifiedCapture(atom))) {
            return atom;
        }
        return atom + quantifier;
    }

    private static String generateAtom(final Random random, final int depth) {
        if (depth > 0 && random.nextInt(4) == 0) {
            final String inner = generateAlternation(random, depth - 1);
            return random.nextBoolean()
                    ? "(" + inner + ")"
                    : "(?:" + inner + ")";
        }
        return ATOMS[random.nextInt(ATOMS.length)];
    }

    /** A capture inside a repetition, about to be placed inside a second one. */
    private static boolean containsQuantifiedCapture(final String atom) {
        if (!atom.startsWith("(") || atom.startsWith("(?:")) {
            return false;
        }
        return atom.contains("*") || atom.contains("+") || atom.contains("?")
               || atom.contains("{");
    }

    /** Uses the JDK as the oracle for whether an atom can match nothing. */
    private static boolean matchesEmpty(final String atom) {
        try {
            return JdkOracle.compile(atom).matcher("").lookingAt();
        } catch (final RuntimeException e) {
            return true; // unparseable, so do not compound it with a quantifier
        }
    }

    private static String generateInput(final Random random) {
        final char[] alphabet = "abcx,:-01 \t".toCharArray();
        final int length = random.nextInt(10);
        final StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }
}
