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
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.Engine;
import stroom.shapeshifter.regex.Flag;

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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The cost of an unanchored search for an {@code END_INPUT}-anchored pattern — the gate for
 * the end-anchor programme ({@code design/06-performance-plan.md} §6), and the
 * failure-shaped twin of {@link AnchoredSearchBenchmark}: every corpus workload measures
 * searches that mostly match, so the search that must walk a large region to reach (or fail
 * to reach) a match pinned to its far end was never on the board.
 *
 * <p>The shapes are mined from real patterns, not invented. {@code WEBLOG_*} is the corpus's
 * bounded tail {@code (\d{3}) (\d+)$} — finite maximum length, so the tail-window jump
 * (§6 Phase 2) is what should move it. {@code FILENAME_*} is filename_extract's unbounded
 * {@code ([^\\]+)$}, where every non-backslash byte is a candidate start and only reverse
 * matching (§6 Phase 4) can help. {@code KV_*} is the workload that motivated Stroom DS's
 * reverse feature: key=value text where the key before the last {@code =} is only separable
 * walking backwards — leftmost-first semantics capture {@code "bob create time"} here, the
 * JDK agrees, and the many failed forward attempts along the way are exactly the cost being
 * priced.
 *
 * <p>The tree engine is absent from the {@code FILENAME_*} rows by its own design: at this
 * region size the unbounded shape exceeds its step budget and it refuses with
 * {@code MatchLimitException} rather than backtrack catastrophically — the same manner of
 * absence as the bounded backtracker's from {@link AnchoredSearchBenchmark}. The refusal is
 * pinned by the fixture test, so if a future phase makes the shape feasible the pin says so.
 *
 * <p>One operation is one whole-region search over ~256 KiB. The {@code javaRegex} rows are
 * the untouched-code drift control ({@code design/benchmarks/README.md}): they must not move
 * when a phase lands, and cross-run comparisons are validated against them first. Every
 * shape's pattern and expected outcome is pinned on all engines and the JDK by
 * {@code EndAnchoredSearchBenchmarkFixtureTest}, so a shape that quietly stops matching
 * fails the build instead of silently measuring something else wearing the same name.
 * Run with:
 * <pre>
 * ./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh --args='.*EndAnchoredSearch.*'
 * </pre>
 */
@Fork(5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class EndAnchoredSearchBenchmark {

    /** One whole-region search processes this much input. */
    private static final int TARGET_SIZE = 256 * 1024;

    /** A shape is a real pattern plus a region built to hit or miss at its far end. */
    public enum Shape {
        /** The corpus tail as written, present — unbounded: {@code \d+} has no maximum,
         * so only reverse matching (Phase 4) can move this row. */
        WEBLOG_HIT("(\\d{3}) (\\d+)$", true),
        /** The corpus tail as written, absent. */
        WEBLOG_MISS("(\\d{3}) (\\d+)$", false),
        /** The bounded tail, present: the same shape with the bound stated, which is what
         * the tail-window jump (Phase 2) needs — and what a config author who knows a size
         * field's width would write. */
        BOUNDED_HIT("(\\d{3}) (\\d{1,9})$", true),
        /** The bounded tail, absent. */
        BOUNDED_MISS("(\\d{3}) (\\d{1,9})$", false),
        /** Unbounded tail, present: a filename follows the last backslash. */
        FILENAME_HIT("([^\\\\]+)$", true),
        /** Unbounded tail, absent: the region ends on a backslash. */
        FILENAME_MISS("([^\\\\]+)$", false),
        /** The DS reverse-feature workload, present: the last pair runs to the end. */
        KV_HIT("([a-z ]+)=[^ ]+$", true),
        /** The DS reverse-feature workload, absent: a trailing space breaks the value. */
        KV_MISS("([a-z ]+)=[^ ]+$", false);

        private final String pattern;
        private final boolean matches;

        Shape(final String pattern, final boolean matches) {
            this.pattern = pattern;
            this.matches = matches;
        }

        public String pattern() {
            return pattern;
        }

        /** Whether a search over {@link #data()} must succeed — pinned by the fixture test. */
        public boolean matches() {
            return matches;
        }

        /** Whether the tree engine can run this shape at all at this region size — the
         * unbounded filename shape exceeds its step budget; see the class note. */
        public boolean treeCanRun() {
            return this != FILENAME_HIT && this != FILENAME_MISS;
        }

        /** Whether this shape's pattern qualifies for the tail-window jump: END_INPUT
         * trailing anchor and a finite maximum. Only the {@code BOUNDED_*} rows do — the
         * fixture test verifies the claim against the published facts, so the benchmark can
         * never again believe a row is bounded when the analysis knows better. */
        public boolean tailWindowed() {
            return this == BOUNDED_HIT || this == BOUNDED_MISS;
        }

        /** The region, deterministic and without a trailing newline (so the JDK's {@code $}
         * and this dialect's {@code END_INPUT} ask the same question). */
        public byte[] data() {
            final StringBuilder text = new StringBuilder(TARGET_SIZE + 128);
            switch (this) {
                case WEBLOG_HIT, WEBLOG_MISS, BOUNDED_HIT, BOUNDED_MISS -> {
                    final String line = "10.31.2.7 - - [24/Aug/2026:08:00:00 +0000] "
                            + "\"GET /store/item?id=1934 HTTP/1.1\" 200 5120\n";
                    while (text.length() < TARGET_SIZE) {
                        text.append(line);
                    }
                    text.append(this == WEBLOG_HIT || this == BOUNDED_HIT
                            ? "10.31.2.7 - - [24/Aug/2026:08:00:01 +0000] "
                              + "\"GET /store/item?id=1935 HTTP/1.1\" 200 12345"
                            : "10.31.2.7 - - [24/Aug/2026:08:00:01 +0000] "
                              + "\"GET /store/item?id=1935 HTTP/1.1\" 200 5120 \"Mozilla/5.0\"");
                }
                case FILENAME_HIT, FILENAME_MISS -> {
                    text.append("C:");
                    int segment = 0;
                    while (text.length() < TARGET_SIZE) {
                        text.append('\\').append("share").append(segment++).append(".dir");
                    }
                    text.append(this == FILENAME_HIT
                            ? "\\report.txt"
                            : "\\");
                }
                case KV_HIT, KV_MISS -> {
                    text.append("ip address=1.1.1.1 name=bob ");
                    int key = 0;
                    while (text.length() < TARGET_SIZE) {
                        text.append("field").append(key).append("=value").append(key).append(' ');
                        key++;
                    }
                    text.append(this == KV_HIT
                            ? "create time=2026-01-01:00:00:00"
                            : "create time= ");
                }
                default -> throw new IllegalStateException(name());
            }
            return text.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    /** State for the three searchers that take every shape. */
    @State(Scope.Benchmark)
    public static class AllShapes {

        @Param({"WEBLOG_HIT", "WEBLOG_MISS", "BOUNDED_HIT", "BOUNDED_MISS",
                "FILENAME_HIT", "FILENAME_MISS", "KV_HIT", "KV_MISS"})
        public Shape shape;

        byte[] data;
        ByteMatcher scanPlanMatcher;
        ByteMatcher simulateMatcher;
        Matcher javaMatcher;

        @Setup
        public void setup() {
            data = shape.data();
            final EnumSet<Flag> none = EnumSet.noneOf(Flag.class);
            scanPlanMatcher =
                    BytePattern.compileForcing(Engine.SCAN_PLAN, shape.pattern(), none).matcher();
            simulateMatcher =
                    BytePattern.compileForcing(Engine.SIMULATE, shape.pattern(), none).matcher();
            javaMatcher = Pattern.compile(shape.pattern())
                    .matcher(new String(data, StandardCharsets.UTF_8));
        }
    }

    /** State for the tree engine: only the shapes its step budget accepts (the class note).
     * The fixture test asserts this list is exactly {@link Shape#treeCanRun()}. */
    @State(Scope.Benchmark)
    public static class TreeShapes {

        @Param({"WEBLOG_HIT", "WEBLOG_MISS", "BOUNDED_HIT", "BOUNDED_MISS",
                "KV_HIT", "KV_MISS"})
        public Shape shape;

        byte[] data;
        ByteMatcher treeMatcher;

        @Setup
        public void setup() {
            data = shape.data();
            treeMatcher = BytePattern
                    .compileForcing(Engine.TREE, shape.pattern(), EnumSet.noneOf(Flag.class))
                    .matcher();
        }
    }

    @Benchmark
    public boolean tree(final TreeShapes state) {
        return state.treeMatcher.match(state.data, 0, state.data.length, Anchoring.UNANCHORED);
    }

    @Benchmark
    public boolean scanPlan(final AllShapes state) {
        return state.scanPlanMatcher.match(state.data, 0, state.data.length,
                Anchoring.UNANCHORED);
    }

    @Benchmark
    public boolean simulate(final AllShapes state) {
        return state.simulateMatcher.match(state.data, 0, state.data.length,
                Anchoring.UNANCHORED);
    }

    @Benchmark
    public boolean javaRegex(final AllShapes state) {
        return state.javaMatcher.reset().find();
    }

    public static void main(final String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(EndAnchoredSearchBenchmark.class.getSimpleName())
                .build()).run();
    }
}
