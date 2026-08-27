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
 * The cost of a lazy run terminated by a literal — {@code (?s).*?</batch>} — which is the
 * shape a block-structured document is matched with, and the one the XML head-to-head's only
 * loss to Saxon turned out to be made of.
 *
 * <p>The gate for {@code design/06-performance-plan.md} §1's reopened literal-skip row. That
 * row was deprioritised in 2026-08 because SPARSE measured 2.80× ahead of the JDK without it,
 * with the condition "revisit only if a sparse workload ever loses"; one did, two layers up,
 * and the finding arrived with no benchmark in this module able to see it. Pricing
 * {@code nasty_xml} by variant put <b>54% of its runtime</b> in this shape — rewriting the
 * configuration to scan by line rather than by byte took the case from 940 ms to 435 ms on
 * 100k units with byte-identical output — but that measurement is a whole XSLT job two
 * modules away. This benchmark is the same question asked where the code is.
 *
 * <p>The shapes are the two patterns from that case, unmodified, each in two forms:
 *
 * <ul>
 *   <li><b>{@code *_DOTALL}</b> — {@code ((?s).*?)} as an author naturally writes it. The
 *       engine tries the terminating literal at <i>every byte</i> of the block.</li>
 *   <li><b>{@code *_LINE}</b> — {@code ((?:[^\n]*\n)*?)}, the same language on this input,
 *       tried only at line starts. Not a rival implementation but the <b>target</b>: it is
 *       what the DOTALL rows should cost once the engine finds the literal instead of
 *       stepping onto it, and the gap between the two forms is the size of the prize.</li>
 * </ul>
 *
 * <p>Matching is <b>anchored</b>, which is what makes these rows faithful rather than
 * decorative: the case is a version-4 configuration, so dispatch is strict and asks the
 * anchored question at the block start (D36/E20). An unanchored search would measure a
 * different thing, and for the miss shape a catastrophically different one — every start
 * position rescanning to the end.
 *
 * <p>Sizes are the real ones: an entry block is 269 bytes here (276 in the case) and a
 * batch is 612, with lines averaging about 55 bytes, so the line form asks roughly 50× fewer
 * questions. {@code FAR} and {@code MISS} inflate a batch to 64 KiB to show how the cost
 * scales and to keep the failure path measured — the standing method note from the early-exit
 * miss is that every corpus workload measures searches that mostly match.
 *
 * <p><b>Read the {@code natural} row; the rest are diagnostics.</b> {@code natural} compiles
 * the way a caller does, so it runs what the template engine runs — and what that is, is not
 * what {@code BytePattern.engine()} reports. {@code engine()} answers {@code SIMULATE} for
 * these patterns because the simulation is the linear-time <i>guarantee</i>, but
 * {@code ByteMatcher.runLinear} takes <b>the tree engine first at every region size</b> (D32)
 * and falls back to the simulation only on a bailout. A forced-{@code SIMULATE} row therefore
 * measures an engine this workload reaches only when the tree gives up. That distinction cost
 * this benchmark a first draft, and it is the reason the natural row exists.
 *
 * <p>The forced rows stay as diagnostics, because knowing which tier carries the cost is what
 * a fix needs. {@code SCAN_PLAN} and {@code BACKTRACK} are absent for stated reasons rather
 * than oversight: the scan plan refuses the shape outright ("pattern is not one-pass"), and
 * the backtracker runs the 269- and 612-byte blocks but exhausts its bound at 64 KiB. A
 * forced {@code TREE} row cannot exist either — pinned, it raises {@code MatchLimitException}
 * on the line form at 64 KiB, where the natural path would simply fall back. That refusal is
 * worth knowing on its own: the idiom that rescues this shape is one the tree engine cannot
 * run at size unaided.
 *
 * <p>One operation is one anchored match. Blocks differ in size by shape, so <b>compare a row
 * against itself across runs</b>, not against its neighbours. The {@code javaRegex} rows are
 * the untouched-code drift control ({@code design/benchmarks/README.md}). Every shape's
 * pattern and expected outcome is pinned on all engines and the JDK by
 * {@link LazyRunBenchmarkFixtureTest}, so a shape that quietly stops matching fails the build
 * instead of silently measuring a scan-and-fail wearing the same name. Run with:
 * <pre>
 * ./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh --args='.*LazyRun.*'
 * </pre>
 */
@Fork(5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class LazyRunBenchmark {

    /** The inflated block for the scaling and failure shapes. */
    private static final int FAR_SIZE = 64 * 1024;

    /** One entry as the case's own input has it — five lines, 269 bytes. Written out
     * rather than built, because the indentation is part of the pattern being measured. */
    private static final String ENTRY =
            "    <entry seq=\"1\">\n"
            + "      <meta><source><system><name>alpha</name>"
            + "<tier>prod</tier></system></source></meta>\n"
            + "      <detail><![CDATA[select * from users where name = 'x' "
            + "& age > 30 and age < 90]]></detail>\n"
            + "      <note>plain &amp; escaped &lt;text&gt;</note>\n"
            + "    </entry>\n";

    /** A shape is one of the case's two patterns, in one of its two forms. */
    public enum Shape {

        /** The entry block as written — 269 bytes, terminator at the end. */
        ENTRY_DOTALL("    <entry seq=\"([0-9]+)\">\\n((?s).*?    </entry>)\\n", true),
        /** The same block, asked by line: the target cost. */
        ENTRY_LINE("    <entry seq=\"([0-9]+)\">\\n((?:[^\\n]*\\n)*?    </entry>)\\n", true),
        /** The batch block as written — 612 bytes, two entries inside it. */
        BATCH_DOTALL("  <batch id=\"([^\"]*)\">\\n((?s).*?)  </batch>\\n", true),
        /** The same, asked by line. */
        BATCH_LINE("  <batch id=\"([^\"]*)\">\\n((?:[^\\n]*\\n)*?)  </batch>\\n", true),
        /** A batch inflated to 64 KiB: how the per-byte cost scales. */
        FAR_DOTALL("  <batch id=\"([^\"]*)\">\\n((?s).*?)  </batch>\\n", true),
        /** The same, asked by line. */
        FAR_LINE("  <batch id=\"([^\"]*)\">\\n((?:[^\\n]*\\n)*?)  </batch>\\n", true),
        /** 64 KiB with no terminator anywhere — the failure path, which no corpus
         * workload measures and which a dispatching caller produces constantly. */
        MISS_DOTALL("  <batch id=\"([^\"]*)\">\\n((?s).*?)  </batch>\\n", false),
        /** The same, asked by line. */
        MISS_LINE("  <batch id=\"([^\"]*)\">\\n((?:[^\\n]*\\n)*?)  </batch>\\n", false);

        private final String pattern;
        private final boolean matches;

        Shape(final String pattern, final boolean matches) {
            this.pattern = pattern;
            this.matches = matches;
        }

        public String pattern() {
            return pattern;
        }

        /** Whether an anchored match over {@link #data()} must succeed — pinned by the
         * fixture test, because a shape that stopped matching would still score. */
        public boolean matches() {
            return matches;
        }

        /** The block, deterministic, with the match anchored at offset zero. */
        public byte[] data() {
            final StringBuilder text = new StringBuilder(FAR_SIZE + 1024);
            switch (this) {
                case ENTRY_DOTALL, ENTRY_LINE -> text.append(ENTRY);
                case BATCH_DOTALL, BATCH_LINE -> {
                    text.append("  <batch id=\"b-1\">\n").append(ENTRY).append(ENTRY)
                            .append("  </batch>\n");
                }
                case FAR_DOTALL, FAR_LINE -> {
                    text.append("  <batch id=\"b-1\">\n");
                    while (text.length() < FAR_SIZE) {
                        text.append(ENTRY);
                    }
                    text.append("  </batch>\n");
                }
                case MISS_DOTALL, MISS_LINE -> {
                    text.append("  <batch id=\"b-1\">\n");
                    while (text.length() < FAR_SIZE) {
                        text.append(ENTRY);
                    }
                    // Deliberately no closing tag: the run scans the whole block and fails.
                }
                default -> throw new IllegalStateException(name());
            }
            return text.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    @State(Scope.Benchmark)
    public static class AllShapes {

        @Param({"ENTRY_DOTALL", "ENTRY_LINE", "BATCH_DOTALL", "BATCH_LINE",
                "FAR_DOTALL", "FAR_LINE", "MISS_DOTALL", "MISS_LINE"})
        public Shape shape;

        byte[] data;
        ByteMatcher naturalMatcher;
        ByteMatcher simulateMatcher;
        ByteMatcher fancyMatcher;
        Matcher javaMatcher;

        @Setup
        public void setup() {
            data = shape.data();
            final EnumSet<Flag> none = EnumSet.noneOf(Flag.class);
            // Compiled the way a caller compiles: whatever this runs is what the workload runs.
            naturalMatcher = BytePattern.compile(shape.pattern()).matcher();
            simulateMatcher =
                    BytePattern.compileForcing(Engine.SIMULATE, shape.pattern(), none).matcher();
            fancyMatcher =
                    BytePattern.compileForcing(Engine.FANCY, shape.pattern(), none).matcher();
            javaMatcher = Pattern.compile(shape.pattern())
                    .matcher(new String(data, StandardCharsets.UTF_8));
        }
    }

    /** The row that matters: the engine a caller actually gets. */
    @Benchmark
    public boolean natural(final AllShapes state) {
        return state.naturalMatcher.match(state.data, 0, state.data.length, Anchoring.ANCHORED);
    }

    @Benchmark
    public boolean fancy(final AllShapes state) {
        return state.fancyMatcher.match(state.data, 0, state.data.length, Anchoring.ANCHORED);
    }

    @Benchmark
    public boolean simulate(final AllShapes state) {
        return state.simulateMatcher.match(state.data, 0, state.data.length, Anchoring.ANCHORED);
    }

    @Benchmark
    public boolean javaRegex(final AllShapes state) {
        return state.javaMatcher.reset().lookingAt();
    }

    public static void main(final String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(LazyRunBenchmark.class.getSimpleName())
                .build()).run();
    }
}
