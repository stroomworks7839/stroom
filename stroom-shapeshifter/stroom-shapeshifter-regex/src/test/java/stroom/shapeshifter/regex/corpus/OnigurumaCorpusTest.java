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
import stroom.shapeshifter.regex.Engine;
import stroom.shapeshifter.regex.Flag;
import stroom.shapeshifter.regex.PatternCompileException;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oniguruma's UTF-8 test suite, run against this engine.
 * <p>
 * The complement to {@link RustCorpusTest}: Rust's corpus is authoritative for the linear
 * subset and by design contains not one backreference, while Oniguruma is a backtracking
 * engine, so its suite is dense in exactly the constructs of the fancy tier — backreferences,
 * lookaround, atomic groups. Expected spans are byte offsets over UTF-8, this engine's native
 * coordinate system, so no translation sits between the corpus and the assertion. Source,
 * licence and conversion are recorded in {@code src/test/resources/oniguruma/README.md}.
 * <p>
 * The dialect is Ruby's, where {@code ^} and {@code $} are always line anchors, so every case
 * compiles under MULTILINE. Refusing a pattern is a documented scope boundary, counted by
 * reason below rather than hidden. A case that compiles but asks a question the two dialects
 * answer differently — Unicode against Ruby's ASCII shorthands, for instance — is listed in
 * {@code oniguruma/ignore} with its reason, the same mechanism fancy-regex maintains for the
 * same corpus.
 */
class OnigurumaCorpusTest {

    private record Case(String name, String pattern, String input, int group, int start, int end) {

        boolean expectsMatch() {
            return start >= 0;
        }
    }

    @Test
    void agreesWithTheOnigurumaCorpus() {
        final List<Case> cases = load();
        assertThat(cases).as("corpus failed to load").hasSizeGreaterThan(700);
        final Map<String, String> ignored = loadIgnores();

        final Map<String, Integer> outcomes = new TreeMap<>();
        final Map<String, Integer> refusals = new TreeMap<>();
        final Map<String, Integer> ignoredByReason = new TreeMap<>();
        final List<String> failures = new ArrayList<>();
        int run = 0;

        for (final Case testCase : cases) {
            final String ignoreReason = ignored.get(testCase.name());
            if (ignoreReason != null) {
                ignoredByReason.merge(ignoreReason, 1, Integer::sum);
                continue;
            }
            final BytePattern pattern;
            try {
                pattern = BytePattern.compile(testCase.pattern(), EnumSet.of(Flag.MULTILINE));
            } catch (final PatternCompileException e) {
                outcomes.merge("rejected: " + e.reason(), 1, Integer::sum);
                refusals.merge(e.getMessage().split("\n")[0], 1, Integer::sum);
                continue;
            }

            outcomes.merge(pattern.engine().description(), 1, Integer::sum);
            run++;
            final String failure = check(pattern, testCase);
            if (failure != null) {
                failures.add(failure);
            }

            // The same case through the tree engine, which D30 requires proven against this
            // corpus before the tier map is redrawn. Every case the flat engines execute, the
            // tree engine must answer identically.
            final BytePattern treeForced = BytePattern.compileForcing(
                    Engine.TREE, testCase.pattern(), java.util.EnumSet.of(Flag.MULTILINE));
            final String treeFailure = check(treeForced, testCase);
            if (treeFailure != null) {
                failures.add("[tree] " + treeFailure);
            }
        }

        report(cases.size(), run, outcomes, refusals, ignoredByReason, failures);

        assertThat(failures).as("%d cases disagreed with the Oniguruma corpus", failures.size())
                .isEmpty();
        assertThat(run).as("cases actually executed").isGreaterThan(500);
    }

    /** Returns a description of the disagreement, or null. */
    private static String check(final BytePattern pattern, final Case testCase) {
        final byte[] data = testCase.input().getBytes(StandardCharsets.UTF_8);
        final ByteMatcher matcher = pattern.matcher();
        final boolean matched = matcher.find(data);

        if (!testCase.expectsMatch()) {
            return matched
                    ? String.format("%s: expected no match, got %d-%d%n    pattern=%s input=%s",
                            testCase.name(), matcher.start(), matcher.end(),
                            testCase.pattern(), quote(testCase.input()))
                    : null;
        }
        if (!matched) {
            return String.format("%s: expected %d-%d of group %d, got no match%n"
                                 + "    pattern=%s input=%s",
                    testCase.name(), testCase.start(), testCase.end(), testCase.group(),
                    testCase.pattern(), quote(testCase.input()));
        }
        final int group = testCase.group();
        final boolean participated = group == 0 || matcher.matchedGroup(group);
        final int start = participated
                ? matcher.start(group)
                : -1;
        final int end = participated
                ? matcher.end(group)
                : -1;
        return start == testCase.start() && end == testCase.end()
                ? null
                : String.format("%s: group %d expected %d-%d, got %s%n    pattern=%s input=%s",
                        testCase.name(), group, testCase.start(), testCase.end(),
                        participated
                                ? start + "-" + end
                                : "no participation",
                        testCase.pattern(), quote(testCase.input()));
    }

    // -----------------------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------------------

    private static List<Case> load() {
        final List<Case> cases = new ArrayList<>();
        String name = null;
        String pattern = null;
        String input = null;
        int group = 0;
        for (final String line : readLines("oniguruma/oniguruma.cases")) {
            if (line.startsWith("test ")) {
                name = line.substring(5);
            } else if (line.startsWith("pattern ")) {
                pattern = unescape(line.substring(8));
            } else if (line.startsWith("input ")) {
                input = unescape(line.substring(6));
            } else if (line.startsWith("group ")) {
                group = Integer.parseInt(line.substring(6));
            } else if (line.startsWith("span ")) {
                final String span = line.substring(5);
                if ("none".equals(span)) {
                    cases.add(new Case(name, pattern, input, group, -1, -1));
                } else {
                    final int dash = span.indexOf('-', 1);
                    cases.add(new Case(name, pattern, input, group,
                            Integer.parseInt(span.substring(0, dash)),
                            Integer.parseInt(span.substring(dash + 1))));
                }
            }
        }
        return cases;
    }

    /** The ignore file: {@code name<tab>reason} per line, comments and blanks skipped. */
    private static Map<String, String> loadIgnores() {
        final Map<String, String> ignored = new LinkedHashMap<>();
        for (final String line : readLines("oniguruma/ignore")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            final int tab = line.indexOf('\t');
            ignored.put(line.substring(0, tab), line.substring(tab + 1).trim());
        }
        return ignored;
    }

    private static String unescape(final String text) {
        final StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            final char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                sb.append(c);
                i++;
                continue;
            }
            final char next = text.charAt(i + 1);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case '\\' -> sb.append('\\');
                case 'x' -> {
                    sb.append((char) Integer.parseInt(text.substring(i + 2, i + 4), 16));
                    i += 2;
                }
                default -> throw new IllegalStateException("bad escape in cases file: \\" + next);
            }
            i += 2;
        }
        return sb.toString();
    }

    private static List<String> readLines(final String resource) {
        final InputStream stream =
                OnigurumaCorpusTest.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) {
            return List.of();
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            final List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -----------------------------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------------------------

    private static void report(final int total,
                               final int run,
                               final Map<String, Integer> outcomes,
                               final Map<String, Integer> refusals,
                               final Map<String, Integer> ignoredByReason,
                               final List<String> failures) {
        final StringBuilder sb = new StringBuilder("\nOniguruma corpus\n")
                .append(total).append(" cases loaded, ").append(run).append(" executed\n\n");
        outcomes.forEach((outcome, count) ->
                sb.append(String.format("  %-40s %5d%n", outcome, count)));
        if (!ignoredByReason.isEmpty()) {
            sb.append("\nIgnored, by documented reason:\n");
            ignoredByReason.forEach((reason, count) ->
                    sb.append(String.format("  %-70s %5d%n", reason, count)));
        }
        if (!refusals.isEmpty()) {
            sb.append("\nRefusals, grouped:\n");
            refusals.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(15)
                    .forEach(entry -> sb.append(String.format("  %5d  %s%n",
                            entry.getValue(), entry.getKey())));
        }
        if (!failures.isEmpty()) {
            sb.append("\nDisagreements:\n");
            failures.stream().limit(40).forEach(failure ->
                    sb.append("  ").append(failure).append('\n'));
        }
        System.out.println(sb);
    }

    private static String quote(final String text) {
        return "\"" + text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
