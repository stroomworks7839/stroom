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

import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.PatternCompileException;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harvests regex patterns from DS3 configurations and reports which execution tier each would
 * land on, by putting them through the real compiler.
 * <p>
 * This measures the engine rather than a model of it: every classification below is the
 * compiler's own verdict, so the report cannot drift away from what the engine actually does.
 * The split decides how much the tier 0 work is worth.
 * <p>
 * <b>The repository's own DS3 configs are a small and simple sample.</b> Production instances
 * hold larger and more deeply nested patterns, which plausibly skew away from tier 0. Point
 * this at a real content export for a representative answer:
 * <pre>
 * ./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:test \
 *     --tests '*CorpusAnalysisTest*' --rerun-tasks -i \
 *     -Dshapeshifter.corpus.dir=/path/to/content
 * </pre>
 * Known gap: DS3 carries {@code dotAll} and {@code caseInsensitive} as separate XML attributes
 * on the {@code <regex>} element and only {@code pattern} is read here, so patterns relying on
 * those flags are analysed with the wrong {@code .} semantics.
 */
class CorpusAnalysisTest {

    private static final Pattern PATTERN_ATTRIBUTE = Pattern.compile("pattern=\"([^\"]*)\"");

    private record Classification(String pattern,
                                  int occurrences,
                                  String verdict,
                                  String detail) {

    }

    @Test
    void classifyCorpus() throws IOException {
        final Path root = Path.of(System.getProperty("shapeshifter.corpus.dir", "../.."))
                .toAbsolutePath()
                .normalize();

        final Map<String, Integer> patterns = harvest(root);
        assertThat(patterns)
                .as("no patterns found under %s — is the corpus directory correct?", root)
                .isNotEmpty();

        final List<Classification> results = new ArrayList<>();
        patterns.forEach((pattern, occurrences) -> results.add(classify(pattern, occurrences)));
        results.sort(Comparator.comparing(Classification::verdict)
                .thenComparing(Classification::pattern));

        report(root, results);

        // Every harvested pattern is a real, working DS3 regex, so any syntax error is a defect
        // in this parser rather than in the corpus.
        assertThat(results.stream().filter(r -> "syntax error".equals(r.verdict())).toList())
                .as("patterns the engine failed to parse")
                .isEmpty();
    }

    private static Classification classify(final String pattern, final int occurrences) {
        try {
            final BytePattern compiled = BytePattern.compile(pattern);
            return switch (compiled.engine()) {
                case SCAN_PLAN -> new Classification(pattern, occurrences, "scan plan",
                        compiled.groupCount() + " groups");
                // The patterns 04-corpus-analysis.md called "java dialect" land here now: the
                // constructs compile natively instead of delegating to the JDK.
                case TREE, FANCY -> new Classification(pattern, occurrences, "fancy",
                        "backreference, lookaround or atomic group");
                default -> new Classification(pattern, occurrences, "automaton",
                        compiled.ambiguities().getFirst().toString());
            };
        } catch (final PatternCompileException e) {
            final String verdict = switch (e.reason()) {
                case UNSUPPORTED -> "unsupported";
                case SYNTAX -> "syntax error";
            };
            return new Classification(pattern, occurrences, verdict, firstLine(e.getMessage()));
        }
    }

    private static String firstLine(final String message) {
        final int newline = message.indexOf('\n');
        return newline < 0
                ? message
                : message.substring(0, newline);
    }

    private static Map<String, Integer> harvest(final Path root) throws IOException {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".xml"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .forEach(p -> extract(p, counts));
        }
        return counts;
    }

    private static void extract(final Path file, final Map<String, Integer> counts) {
        final String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException | RuntimeException e) {
            return; // not readable as UTF-8 text, so not a DS3 config
        }
        if (!content.contains("<dataSplitter")) {
            return;
        }
        final Matcher matcher = PATTERN_ATTRIBUTE.matcher(content);
        while (matcher.find()) {
            counts.merge(unescapeXml(matcher.group(1)), 1, Integer::sum);
        }
    }

    private static String unescapeXml(final String text) {
        return text.replace("&#34;", "\"")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static void report(final Path root, final List<Classification> results) {
        final Map<String, Integer> distinctByVerdict = new TreeMap<>();
        final Map<String, Integer> usesByVerdict = new TreeMap<>();
        for (final Classification result : results) {
            distinctByVerdict.merge(result.verdict(), 1, Integer::sum);
            usesByVerdict.merge(result.verdict(), result.occurrences(), Integer::sum);
        }
        final int distinct = results.size();
        final int uses = results.stream().mapToInt(Classification::occurrences).sum();

        final StringBuilder sb = new StringBuilder("\nDS3 regex corpus analysis\n")
                .append("root: ").append(root).append('\n')
                .append(distinct).append(" distinct patterns, ").append(uses).append(" uses\n\n")
                .append(String.format("%-14s %9s %9s%n", "verdict", "distinct", "uses"));

        distinctByVerdict.forEach((verdict, count) -> sb.append(String.format(
                "%-14s %6d %2d%% %6d %2d%%%n",
                verdict,
                count,
                percent(count, distinct),
                usesByVerdict.get(verdict),
                percent(usesByVerdict.get(verdict), uses))));

        sb.append('\n');
        for (final Classification result : results) {
            sb.append(String.format("[%-12s] %s%n", result.verdict(), result.pattern()))
                    .append("               ").append(result.detail()).append('\n');
        }
        System.out.println(sb);
    }

    private static int percent(final int part, final int total) {
        return total == 0
                ? 0
                : Math.round(part * 100f / total);
    }
}
