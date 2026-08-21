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
import stroom.shapeshifter.regex.Flag;
import stroom.shapeshifter.regex.MatchOutcome;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Rust {@code regex} crate's test corpus, run against this engine.
 * <p>
 * Worth having because of what it contains that no corpus written here could: cases distilled
 * from bugs other people hit in production, and cases that <em>define</em> semantics at the
 * points where engines legitimately disagree. It is also the closest available match in design —
 * RE2-style, leftmost-first, linear time, byte-oriented — so where it disagrees with us the
 * disagreement is informative rather than a difference of dialect.
 * <p>
 * Source and licence are recorded in {@code src/test/resources/rust-regex/README.txt}, and the
 * conversion is {@code tools/convert-rust-corpus.py} so that re-taking the corpus is a command
 * rather than an archaeology exercise.
 * <p>
 * <b>665 of the 771 upstream tests convert; 645 load and 409 run here.</b> The conversion drops
 * only tests that ask a different question — leftmost-longest semantics, earliest or overlapping
 * search, match limits, regex sets, configurable line terminators, haystacks that are not UTF-8,
 * and Rust's byte-oriented mode over non-ASCII input, where a match may split a character. It
 * deliberately does <em>not</em> drop tests this engine merely refuses to compile: a refusal is a
 * fact about scope, and the report below counts refusals by reason so that the scope boundary
 * stays visible rather than being filtered away. Those are dominated by {@code (?R)} and
 * {@code \b{...}}, neither of which this dialect claims ({@code 01-regex-language.md} §2.4).
 * <p>
 * Anything this engine <em>accepts</em> must produce exactly the expected spans.
 * <p>
 * It has found ten defects so far, five of them wrong answers rather than missing features: a
 * match could begin inside a character; a repetition followed by {@code \b} was wrongly judged
 * one-pass; {@code (?-u)} was not applied to case folding; a repetition whose body matched empty
 * carried on consuming; and an asserted epsilon path shadowed an unasserted one, so
 * {@code (?:\b|)a} against {@code "ba"} found nothing at all.
 * {@code design/05-engine-benchmarks.md} §6 lists them all.
 */
class RustCorpusTest {

    /**
     * The one reason a case is still excluded at run time rather than during conversion, listed
     * case by case so the exclusion cannot quietly widen.
     * <p>
     * Rust restricts a search to a span while still letting look-around see the bytes outside it,
     * so {@code \b} at the span start can see the word character before it. This engine has no
     * notion of context outside its region: the region start <em>is</em> the start of the input,
     * which is what makes a streaming window self-contained. Worth revisiting if a caller ever
     * needs to re-scan a buffer from a mid-point without losing the boundary before it.
     */
    private static final String SPAN_CONTEXT =
            "Rust evaluates look-around against the whole haystack even when the search is "
            + "restricted to a span; this engine's region start is the start of the input";

    private static final Map<String, String> EXCLUDED = Map.ofEntries(
            Map.entry("substring/unicode-word-start", SPAN_CONTEXT),
            Map.entry("substring/unicode-word-end", SPAN_CONTEXT),
            Map.entry("word-boundary/alt-with-assertion-repetition", SPAN_CONTEXT));

    private record Span(int start, int end) {

        static final Span ABSENT = new Span(-1, -1);
    }

    private record Case(String name,
                        String regex,
                        String haystack,
                        boolean anchored,
                        boolean caseless,
                        int boundsStart,
                        int boundsEnd,
                        List<List<Span>> expected) {

    }

    @Test
    void agreesWithTheRustCorpus() {
        final List<Case> cases = load();
        assertThat(cases).as("corpus failed to load").hasSizeGreaterThan(250);

        final Map<String, Integer> outcomes = new TreeMap<>();
        final Map<String, Integer> refusals = new TreeMap<>();
        final List<String> failures = new ArrayList<>();
        int run = 0;

        for (final Case testCase : cases) {
            final BytePattern pattern;
            try {
                pattern = BytePattern.compile(testCase.regex(), testCase.caseless()
                        ? EnumSet.of(Flag.CASE_INSENSITIVE)
                        : EnumSet.noneOf(Flag.class));
            } catch (final PatternCompileException e) {
                // Refusing a pattern is a documented scope boundary, not a failure.
                outcomes.merge("rejected: " + e.reason(), 1, Integer::sum);
                // The message carries the offending pattern and a caret under it; the first
                // line alone is the reason, which is what these are grouped by.
                refusals.merge(e.getMessage().split("\n")[0], 1, Integer::sum);
                continue;
            }

            outcomes.merge("tier " + pattern.tier(), 1, Integer::sum);
            run++;
            final String failure = check(pattern, testCase);
            if (failure != null) {
                failures.add(failure);
            }
        }

        report(cases.size(), run, outcomes, refusals, failures);

        assertThat(failures).as("%d cases disagreed with the Rust corpus", failures.size())
                .isEmpty();
        assertThat(run).as("cases actually executed").isGreaterThan(250);
    }

    /** Returns a description of the disagreement, or null. */
    private static String check(final BytePattern pattern, final Case testCase) {
        final byte[] data = testCase.haystack().getBytes(StandardCharsets.UTF_8);
        final int from = testCase.boundsStart() >= 0
                ? testCase.boundsStart()
                : 0;
        final int to = testCase.boundsEnd() >= 0
                ? testCase.boundsEnd()
                : data.length;

        // The window carries the whole haystack while the search advances within it, so that a
        // zero-width assertion at the search position can still see the byte before it. Passing
        // the position as a region start instead would make every iteration look like a fresh
        // input, and \b would report a boundary that is not there.
        final ByteWindow window = ByteWindow.complete(data, from, to);
        final ByteMatcher matcher = pattern.matcher();
        final List<List<Span>> actual = new ArrayList<>();
        int pos = from;
        int previousEnd = -1;

        while (pos <= to && actual.size() <= testCase.expected().size() + 4) {
            if (matcher.match(window, pos, testCase.anchored()
                    ? Anchoring.ANCHORED
                    : Anchoring.UNANCHORED) != MatchOutcome.MATCH) {
                break;
            }
            if (matcher.start() == matcher.end() && matcher.start() == previousEnd) {
                // An empty match abutting the previous one is the same position reported twice;
                // a match iterator yields it once.
                pos = matcher.end() + 1;
                continue;
            }
            final List<Span> spans = new ArrayList<>();
            for (int group = 0; group <= pattern.groupCount(); group++) {
                spans.add(matcher.matchedGroup(group)
                        ? new Span(matcher.start(group), matcher.end(group))
                        : Span.ABSENT);
            }
            actual.add(spans);
            previousEnd = matcher.end();
            // Iteration advances past the match, or by one byte for an empty one, which is what
            // the corpus assumes of a match iterator.
            pos = matcher.end() == matcher.start()
                    ? matcher.end() + 1
                    : matcher.end();
        }

        return describeIfDifferent(testCase, actual);
    }

    private static String describeIfDifferent(final Case testCase, final List<List<Span>> actual) {
        final List<List<Span>> expected = testCase.expected();
        if (expected.size() != actual.size()) {
            return String.format("%s: expected %d match(es), got %d%n    regex=%s haystack=%s%n"
                                 + "    expected=%s%n    actual  =%s",
                    testCase.name(), expected.size(), actual.size(),
                    testCase.regex(), quote(testCase.haystack()),
                    render(expected), render(actual));
        }
        for (int i = 0; i < expected.size(); i++) {
            final List<Span> want = expected.get(i);
            final List<Span> got = actual.get(i);
            // The corpus records only the groups it cares about; compare the ones it lists.
            for (int g = 0; g < want.size(); g++) {
                final Span wanted = want.get(g);
                final Span actualSpan = g < got.size()
                        ? got.get(g)
                        : Span.ABSENT;
                if (!wanted.equals(actualSpan)) {
                    return String.format("%s: match %d group %d expected %s, got %s%n"
                                         + "    regex=%s haystack=%s",
                            testCase.name(), i, g, show(wanted), show(actualSpan),
                            testCase.regex(), quote(testCase.haystack()));
                }
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------------------

    private static List<Case> load() {
        final List<Case> cases = new ArrayList<>();
        for (final String file : readLines("rust-regex/index")) {
            if (file.isBlank()) {
                continue;
            }
            parseInto(readLines("rust-regex/" + file.trim() + ".cases"), cases);
        }
        return cases;
    }

    private static void parseInto(final List<String> lines, final List<Case> cases) {
        String name = null;
        String regex = null;
        String haystack = "";
        boolean anchored = false;
        boolean caseless = false;
        int boundsStart = -1;
        int boundsEnd = -1;
        List<List<Span>> expected = null;

        for (final String line : lines) {
            if (line.isBlank()) {
                if (name != null && regex != null && expected != null && !isExcluded(name)) {
                    cases.add(new Case(name, regex, haystack, anchored, caseless,
                            boundsStart, boundsEnd, expected));
                }
                name = null;
                regex = null;
                haystack = "";
                anchored = false;
                caseless = false;
                boundsStart = -1;
                boundsEnd = -1;
                expected = null;
                continue;
            }
            final int space = line.indexOf(' ');
            final String key = space < 0
                    ? line
                    : line.substring(0, space);
            final String value = space < 0
                    ? ""
                    : line.substring(space + 1);
            switch (key) {
                case "test" -> name = value;
                case "regex" -> regex = unescape(value);
                case "haystack" -> haystack = unescape(value);
                case "anchored" -> anchored = true;
                case "caseless" -> caseless = true;
                case "bounds" -> {
                    final String[] parts = value.split(" ");
                    boundsStart = Integer.parseInt(parts[0]);
                    boundsEnd = Integer.parseInt(parts[1]);
                }
                case "matches" -> expected = parseMatches(value);
                default -> throw new IllegalStateException("Unknown key '" + key + "'");
            }
        }
        if (name != null && regex != null && expected != null && !isExcluded(name)) {
            cases.add(new Case(name, regex, haystack, anchored, caseless,
                    boundsStart, boundsEnd, expected));
        }
    }

    private static boolean isExcluded(final String name) {
        return EXCLUDED.containsKey(name);
    }

    private static List<List<Span>> parseMatches(final String value) {
        final List<List<Span>> matches = new ArrayList<>();
        if ("-".equals(value)) {
            return matches;
        }
        for (final String match : value.split(";")) {
            final List<Span> spans = new ArrayList<>();
            for (final String span : match.split(",")) {
                if ("?".equals(span)) {
                    spans.add(Span.ABSENT);
                } else {
                    final int dash = span.indexOf('-', 1);
                    spans.add(new Span(Integer.parseInt(span.substring(0, dash)),
                            Integer.parseInt(span.substring(dash + 1))));
                }
            }
            matches.add(spans);
        }
        return matches;
    }

    private static String unescape(final String text) {
        final StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                sb.append(c);
                continue;
            }
            final char next = text.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case '\\' -> sb.append('\\');
                case 'x' -> {
                    sb.append((char) Integer.parseInt(text.substring(i + 1, i + 3), 16));
                    i += 2;
                }
                default -> sb.append('\\').append(next);
            }
        }
        return sb.toString();
    }

    private static List<String> readLines(final String resource) {
        try (InputStream in = RustCorpusTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + resource);
            }
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -----------------------------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------------------------

    private static void report(final int loaded,
                               final int run,
                               final Map<String, Integer> outcomes,
                               final Map<String, Integer> refusals,
                               final List<String> failures) {
        final StringBuilder sb = new StringBuilder("\nRust regex corpus\n")
                .append(loaded).append(" cases loaded, ").append(run).append(" executed\n\n");
        outcomes.forEach((outcome, count) ->
                sb.append(String.format("  %-28s %d%n", outcome, count)));
        // What the refusals are actually for. A count of rejections says how much is out of
        // scope; only the reasons say whether that scope is the intended one.
        sb.append("\n  refused, by reason:\n");
        refusals.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(entry -> sb.append(String.format("  %5d  %s%n",
                        entry.getValue(), entry.getKey())));

        if (!EXCLUDED.isEmpty()) {
            final Map<String, Long> byReason = new TreeMap<>();
            EXCLUDED.values().forEach(reason -> byReason.merge(reason, 1L, Long::sum));
            sb.append('\n');
            byReason.forEach((reason, count) ->
                    sb.append("  ").append(count).append(" excluded:\n    ")
                            .append(reason).append('\n'));
        }
        if (!failures.isEmpty()) {
            sb.append("\n  disagreements:\n");
            failures.forEach(failure -> sb.append("    ").append(failure).append('\n'));
        }
        System.out.println(sb);
    }

    private static String render(final List<List<Span>> matches) {
        final StringBuilder sb = new StringBuilder();
        for (final List<Span> match : matches) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            for (int i = 0; i < match.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(show(match.get(i)));
            }
        }
        return sb.length() == 0
                ? "(none)"
                : sb.toString();
    }

    private static String show(final Span span) {
        return span.start() < 0
                ? "?"
                : span.start() + "-" + span.end();
    }

    private static String quote(final String text) {
        return "\"" + text.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r") + "\"";
    }
}
