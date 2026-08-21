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

import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.Engine;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.Flag;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Does the order of alternation branches affect throughput?
 * <p>
 * For a backtracking engine it does, and substantially: branches are tried in order and a failed
 * attempt is wasted work, so putting the common case last means paying for the rare ones on
 * every record. That is the received wisdom about regex performance, and it is the behaviour
 * {@code java.util.regex} has.
 * <p>
 * Neither tier here works that way, so the question is whether the received wisdom still
 * applies:
 * <ul>
 *   <li><b>Tier 0</b> compiles an alternation to a 256-entry dispatch table indexed by the next
 *       byte. One lookup selects the branch, so order should be irrelevant.</li>
 *   <li><b>Tier 1</b> advances every branch simultaneously as parallel threads. There is no
 *       "try and fail", so order should decide only <em>which</em> match wins, not the work
 *       done to find it.</li>
 * </ul>
 * Each pattern is measured with the frequently-matching branch first and last, over input where
 * that branch matches 95% of records.
 * <p>
 * Results: see {@code design/03-baseline-results.md}.
 */
@Fork(1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class BranchOrderBenchmark {

    private static final int LINES = 5_000;

    /** Disjoint branches, so this compiles to a tier 0 dispatch table. */
    private static final String DISJOINT_COMMON_FIRST = "^(COMMON|RARE|SELDOM|UNUSUAL|ODD) (.*)$";
    private static final String DISJOINT_COMMON_LAST = "^(RARE|SELDOM|UNUSUAL|ODD|COMMON) (.*)$";

    /**
     * Irreducibly overlapping branches, so these need the tier 1 simulation. Both begin with the
     * same class and differ only at their end, which no amount of prefix factoring can separate.
     */
    private static final String OVERLAP_COMMON_FIRST = "^([a-z]+X|[a-z]+Y) (.*)$";
    private static final String OVERLAP_COMMON_LAST = "^([a-z]+Y|[a-z]+X) (.*)$";

    private byte[] disjointData;
    private byte[] overlapData;
    private String disjointText;
    private String overlapText;

    private BytePattern disjointFirst;
    private BytePattern disjointLast;
    private BytePattern overlapFirst;
    private BytePattern overlapLast;
    private Pattern javaDisjointFirst;
    private Pattern javaDisjointLast;
    private Pattern javaOverlapFirst;
    private Pattern javaOverlapLast;

    /** The same pattern on both engines, to separate engine cost from pattern difficulty. */
    private BytePattern sameAsPlan;
    private BytePattern sameAsNfa;
    /** The same pattern with no capture groups, to isolate the cost of tracking them. */
    private BytePattern sameAsNfaNoCaptures;

    @Setup
    public void setup() {
        final Random random = new Random(7);
        final String[] rare = {"RARE", "SELDOM", "UNUSUAL", "ODD"};
        final StringBuilder disjoint = new StringBuilder();
        final StringBuilder overlap = new StringBuilder();
        for (int i = 0; i < LINES; i++) {
            final boolean common = random.nextInt(100) < 95;
            disjoint.append(common
                            ? "COMMON"
                            : rare[random.nextInt(rare.length)])
                    .append(" the rest of the record\n");
            overlap.append(common
                            ? "abcdefghX"
                            : "abcdefghY")
                    .append(" the rest of the record\n");
        }
        disjointText = disjoint.toString();
        overlapText = overlap.toString();
        disjointData = disjointText.getBytes(StandardCharsets.UTF_8);
        overlapData = overlapText.getBytes(StandardCharsets.UTF_8);

        disjointFirst = BytePattern.compile(DISJOINT_COMMON_FIRST, Flag.MULTILINE);
        disjointLast = BytePattern.compile(DISJOINT_COMMON_LAST, Flag.MULTILINE);
        overlapFirst = BytePattern.compile(OVERLAP_COMMON_FIRST, Flag.MULTILINE);
        overlapLast = BytePattern.compile(OVERLAP_COMMON_LAST, Flag.MULTILINE);
        javaDisjointFirst = Pattern.compile(DISJOINT_COMMON_FIRST, Pattern.MULTILINE);
        javaDisjointLast = Pattern.compile(DISJOINT_COMMON_LAST, Pattern.MULTILINE);
        javaOverlapFirst = Pattern.compile(OVERLAP_COMMON_FIRST, Pattern.MULTILINE);
        javaOverlapLast = Pattern.compile(OVERLAP_COMMON_LAST, Pattern.MULTILINE);

        sameAsPlan = disjointFirst;
        sameAsNfa = BytePattern.compileForcingNfa(DISJOINT_COMMON_FIRST,
                java.util.EnumSet.of(Flag.MULTILINE));
        sameAsNfaNoCaptures = BytePattern.compileForcingNfa(
                "^(?:COMMON|RARE|SELDOM|UNUSUAL|ODD) (?:.*)$",
                java.util.EnumSet.of(Flag.MULTILINE));

        // Named engines, not tier ordinals. This guard was written against ordinals and went
        // stale at D32, when the bounded backtracker stopped being chosen per search and the
        // simulation's ordinal moved from 1 to 2 — after which the guard threw on every run and
        // this whole class silently dropped out of the recorded results.
        if (disjointFirst.engine() != Engine.SCAN_PLAN || overlapFirst.engine() != Engine.SIMULATE) {
            throw new IllegalStateException(
                    "benchmark assumes a scan plan and an NFA simulation respectively, got "
                    + disjointFirst.engine() + " and " + overlapFirst.engine());
        }
    }

    private static long countMatches(final BytePattern pattern, final byte[] data) {
        final ByteMatcher matcher = pattern.matcher();
        long hash = 1L;
        int pos = 0;
        while (pos < data.length && matcher.match(data, pos, data.length, Anchoring.UNANCHORED)) {
            hash = hash * 31L + matcher.end(1) - matcher.start(1);
            pos = matcher.end() == matcher.start()
                    ? matcher.end() + 1
                    : matcher.end();
        }
        return hash;
    }

    private static long countMatches(final Pattern pattern, final String text) {
        final Matcher matcher = pattern.matcher(text);
        long hash = 1L;
        while (matcher.find()) {
            hash = hash * 31L + matcher.end(1) - matcher.start(1);
        }
        return hash;
    }

    @Benchmark
    public long tier0CommonFirst() {
        return countMatches(disjointFirst, disjointData);
    }

    @Benchmark
    public long tier0CommonLast() {
        return countMatches(disjointLast, disjointData);
    }

    @Benchmark
    public long tier1CommonFirst() {
        return countMatches(overlapFirst, overlapData);
    }

    @Benchmark
    public long tier1CommonLast() {
        return countMatches(overlapLast, overlapData);
    }

    @Benchmark
    public long javaRegexCommonFirst() {
        return countMatches(javaDisjointFirst, disjointText);
    }

    @Benchmark
    public long javaRegexCommonLast() {
        return countMatches(javaDisjointLast, disjointText);
    }

    /** The same overlapping pattern the tier 1 benchmarks use, for a like-for-like comparison. */
    @Benchmark
    public long javaRegexOverlapCommonFirst() {
        return countMatches(javaOverlapFirst, overlapText);
    }

    @Benchmark
    public long javaRegexOverlapCommonLast() {
        return countMatches(javaOverlapLast, overlapText);
    }

    /** Identical pattern, identical data, scan plan. */
    @Benchmark
    public long samePatternTier0() {
        return countMatches(sameAsPlan, disjointData);
    }

    /** Identical pattern, identical data, NFA simulation — the difference is engine cost alone. */
    @Benchmark
    public long samePatternTier1() {
        return countMatches(sameAsNfa, disjointData);
    }

    @Benchmark
    public long samePatternTier1NoCaptures() {
        final ByteMatcher matcher = sameAsNfaNoCaptures.matcher();
        long hash = 1L;
        int pos = 0;
        while (pos < disjointData.length
               && matcher.match(disjointData, pos, disjointData.length, Anchoring.UNANCHORED)) {
            hash = hash * 31L + matcher.end() - matcher.start();
            pos = matcher.end() == matcher.start()
                    ? matcher.end() + 1
                    : matcher.end();
        }
        return hash;
    }

    public static void main(final String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(BranchOrderBenchmark.class.getSimpleName())
                .build())
                .run();
    }
}
