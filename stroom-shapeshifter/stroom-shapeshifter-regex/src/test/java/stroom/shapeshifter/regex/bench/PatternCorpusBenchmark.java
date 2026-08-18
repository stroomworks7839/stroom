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

package stroom.shapeshifter.regex.bench;

import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.JdkOracle;
import stroom.shapeshifter.regex.PatternCompileException;
import stroom.shapeshifter.regex.corpus.PatternCorpus;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

/**
 * The whole {@link PatternCorpus} — every pattern, over every input — measured against
 * {@code java.util.regex}.
 *
 * <h2>Why this exists alongside {@link CorpusBenchmark}</h2>
 * They measure different things, and quoting either alone is misleading:
 * <ul>
 *   <li>{@link CorpusBenchmark} runs <b>ten hand-picked patterns over 2,000-record buffers</b>. It
 *       measures sustained scanning, and the patterns are ones the author chose — which is exactly
 *       the criticism levelled at any corpus written by the engine's own author.</li>
 *   <li>This one runs <b>106 patterns over short inputs</b>, so it is dominated by per-attempt
 *       overhead rather than scanning, and it covers the breadth the correctness suite covers —
 *       including the shapes deliberately included to be unflattering.</li>
 * </ul>
 * A change that helps one and not the other is telling you something, and neither number is the
 * headline on its own.
 *
 * <h2>What is timed</h2>
 * Compilation happens in {@link #setup} on both sides — parsing, plan or NFA construction and the
 * epsilon-closure precomputation are all inside {@code BytePattern.compile} — so the measurement
 * is execution only, over patterns and matchers that stay hot across millions of operations.
 * <p>
 * The matchers are built in setup too, and the JDK's are reused with {@code reset}. Creating one
 * per operation would charge this engine for an allocation proportional to program size — two
 * thread lists sized to the NFA — amortised over only the twenty-odd short inputs in a category,
 * where a pipeline creates a matcher once per thread and reuses it across millions of records.
 * Measured before it was removed, that asymmetry was worth about 3%: enough to be wrong about,
 * not enough to explain anything.
 *
 * <h2>Fairness</h2>
 * The reference is compiled through {@link JdkOracle}, so both engines are asked the same
 * question — which includes {@code UNICODE_CHARACTER_CLASS}, and therefore charges the JDK for
 * the same Unicode shorthands this engine now pays for. Patterns this engine refuses (the eight
 * needing the java dialect) are excluded from both sides and counted, so the comparison is never
 * quietly measuring a smaller job on one side.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(5)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class PatternCorpusBenchmark {

    /**
     * The corpus categories. Literal because JMH needs them at compile time; {@link #setup}
     * fails loudly if one stops matching the corpus, so the two cannot drift apart silently.
     */
    @Param({"csv", "syslog", "weblog", "keyvalue", "datetime", "network", "quoted",
            "numbers", "identifiers", "structured", "fixedwidth", "stress"})
    private String category;

    private List<BytePattern> ours;
    private List<ByteMatcher> ourMatchers;
    private List<Matcher> theirMatchers;
    private List<byte[]> inputBytes;
    private List<String> inputText;

    @Setup
    public void setup() {
        final PatternCorpus.Category found = PatternCorpus.categories().stream()
                .filter(c -> c.name().equals(category))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no corpus category named '" + category + "' — the @Param list has "
                        + "drifted from PatternCorpus"));

        ours = new ArrayList<>();
        ourMatchers = new ArrayList<>();
        theirMatchers = new ArrayList<>();
        for (final String pattern : found.patterns()) {
            final BytePattern compiled;
            try {
                compiled = BytePattern.compile(pattern);
            } catch (final PatternCompileException e) {
                continue; // needs the java dialect; excluded from both sides
            }
            ours.add(compiled);
            ourMatchers.add(compiled.matcher());
            theirMatchers.add(JdkOracle.compile(pattern).matcher(""));
        }

        inputText = found.inputs();
        inputBytes = inputText.stream()
                .map(input -> input.getBytes(StandardCharsets.UTF_8))
                .toList();
    }

    /** Patterns actually measured, reported so a category's weight is visible. */
    public int patterns() {
        return ours.size();
    }

    @Benchmark
    public long shapeshifter() {
        long hash = 1L;
        for (int i = 0; i < ours.size(); i++) {
            final BytePattern pattern = ours.get(i);
            final ByteMatcher matcher = ourMatchers.get(i);
            for (final byte[] input : inputBytes) {
                if (matcher.find(input)) {
                    for (int group = 0; group <= pattern.groupCount(); group++) {
                        hash = hash * 31L + (matcher.matchedGroup(group)
                                ? matcher.end(group) - matcher.start(group)
                                : -1);
                    }
                } else {
                    hash = hash * 31L - 7L;
                }
            }
        }
        return hash;
    }

    /** The honest end-to-end comparison for a byte pipeline: the JDK needs characters first. */
    @Benchmark
    public long javaRegexFromBytes() {
        long hash = 1L;
        for (final Matcher matcher : theirMatchers) {
            for (final byte[] input : inputBytes) {
                matcher.reset(new String(input, StandardCharsets.UTF_8));
                if (matcher.find()) {
                    for (int group = 0; group <= matcher.groupCount(); group++) {
                        hash = hash * 31L + (matcher.start(group) >= 0
                                ? matcher.end(group) - matcher.start(group)
                                : -1);
                    }
                } else {
                    hash = hash * 31L - 7L;
                }
            }
        }
        return hash;
    }

    /** Characters already in hand, which is the upper bound on what the JDK can do. */
    @Benchmark
    public long javaRegex() {
        long hash = 1L;
        for (final Matcher matcher : theirMatchers) {
            for (final String input : inputText) {
                matcher.reset(input);
                if (matcher.find()) {
                    for (int group = 0; group <= matcher.groupCount(); group++) {
                        hash = hash * 31L + (matcher.start(group) >= 0
                                ? matcher.end(group) - matcher.start(group)
                                : -1);
                    }
                } else {
                    hash = hash * 31L - 7L;
                }
            }
        }
        return hash;
    }
}
