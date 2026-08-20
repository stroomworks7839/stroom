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

/**
 * The cost of an unanchored search that cannot succeed, for a pattern whose leading anchor
 * already proves it — the missing early exit on the performance plan
 * ({@code design/06-performance-plan.md} §1).
 *
 * <p>Every engine's search loop gates attempt positions on {@code Nfa.startAnchor()}: for a
 * {@code ^}-anchored pattern, no position past the region start is ever attempted. But the
 * gate only ever says {@code continue} — after the one viable attempt has failed, the loop
 * still walks the remaining region position by position, confirming at each one a decision
 * that was final at position one. {@code anchored_miss} is that case: a 256 KiB region, a
 * {@code ^}-anchored pattern that fails at the start, and — until the exit lands — a quarter
 * of a million iterations of nothing.
 *
 * <p>The other three shapes are the controls the fix must not move. {@code anchored_hit}
 * matches at the region start, so the exit is never reached. {@code line_miss} is
 * line-anchored, where later line starts are genuinely viable and the walk is honest work.
 * {@code floating_miss} has no anchor at all — every position is viable.
 *
 * <p>One operation is one whole-region search. Ops/s for {@code anchored_miss} should move by
 * orders of magnitude when the exit lands; the controls should not move at all. Run with:
 * <pre>
 * ./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh --args='.*AnchoredSearch.*'
 * </pre>
 */
@Fork(5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class AnchoredSearchBenchmark {

    /** One whole-region search processes this much input. */
    private static final int TARGET_SIZE = 256 * 1024;

    /**
     * The four engines that can be forced. The fancy tier is not separately measurable — it
     * delegates to the tree engine with the flat backtracker as fallback, both measured here.
     */
    @Param({"tree", "scan_plan", "backtrack", "simulate"})
    public String engine;

    @Param({"anchored_miss", "anchored_hit", "line_miss", "floating_miss"})
    public String shape;

    private ByteMatcher matcher;
    private byte[] data;

    @Setup
    public void setup() {
        final String line = "Aug 20 12:00:00 host proc[1]: routine message, nothing to see\n";
        final StringBuilder text = new StringBuilder(TARGET_SIZE + line.length() + 16);
        if ("anchored_hit".equals(shape)) {
            text.append("BEGIN:record\n");
        }
        while (text.length() < TARGET_SIZE) {
            text.append(line);
        }
        data = text.toString().getBytes(StandardCharsets.UTF_8);

        final String pattern = switch (shape) {
            case "line_miss" -> "(?m)^BEGIN:";
            case "floating_miss" -> "BEGIN:";
            default -> "^BEGIN:";
        };
        final Engine forced = switch (engine) {
            case "tree" -> Engine.TREE;
            case "scan_plan" -> Engine.SCAN_PLAN;
            case "backtrack" -> Engine.BACKTRACK;
            default -> Engine.SIMULATE;
        };
        matcher = BytePattern.compileForcing(forced, pattern, EnumSet.noneOf(Flag.class)).matcher();
    }

    @Benchmark
    public boolean search() {
        return matcher.match(data, 0, data.length, Anchoring.UNANCHORED);
    }

    public static void main(final String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(AnchoredSearchBenchmark.class.getSimpleName())
                .build()).run();
    }
}
