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

package stroom.shapeshifter.engine.bench;

import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.fixture.AvroContainers;
import stroom.shapeshifter.engine.fixture.FixtureLedger;
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.engine.output.XmlByteSink;

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
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The engine measured on whole configurations over realistic inputs — the corpus as the
 * workload, not a flattering subset (D22). Results: {@code design/benchmarks/}, and the status
 * they measure against: {@code design/10-engine-compilation.md}.
 *
 * <p>Each workload is a fixture configuration run over its own input repeated to
 * {@link #TARGET_SIZE}, so one op is a fixed volume of real records and workloads are comparable
 * as throughput. The pairs are chosen to isolate questions: {@code win_sec} and
 * {@code win_sec_xml} parse the same events unanchored and anchored, which is a direct A/B on
 * dispatch cost under {@code (A|B|C)*}; {@code apache_httpd} carries the heaviest bodies,
 * including 209 escaping transforms; {@code progressive} is the match sequence alone — a
 * varint length cast and a take, over a binary feed held whole (design 38 §3b; the row keeps
 * the name the step interpreter's row had, so the ledger reads across the retirement).
 * {@code progressive_text} is the pattern tree over text — tags, take-whiles, a take-until
 * and a regex node, compiled to the regex library as one pattern. {@code avro_users} is the
 * real binary row (design 38 §7): an Avro object container the Avro library wrote, parsed with
 * no library as a state machine of templates — header, metadata entries, blocks, each block's
 * length-prefixed records dispatched to the record template — so it is the match sequence,
 * the casts and nested dispatch over raw bytes together. {@code log_sessions} is the
 * reference-heavy row: its matching is a delimiter split and its work is iteration, grouping,
 * keys and sequences, so what it measures is variable resolution and the engine's own
 * iteration variables (design 30).
 *
 * <p>{@code compile} is measured too, because a configuration that compiles per stream would be
 * paying it per stream — and because the compilation stage is where the optimisation work is
 * about to happen, its cost needs a before.
 */
@Fork(5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class EngineBenchmark {

    /** One op processes this much input, so ops/s reads as quarter-MiB/s of real records. */
    private static final int TARGET_SIZE = 256 * 1024;

    /**
     * The rows a run measures. A fixture's alternative shapes (design 37 phase 8) are fixtures,
     * parity-gated on every test run, and measured only when named ({@code -p
     * workload=ausearch_switch}) — except {@code ausearch_dispatch}, which stands beside
     * {@code ausearch} as {@code win_sec_strict} stands beside {@code win_sec}: the same records
     * and the same bytes in the two idioms, a map against a template per key, so the pair is a
     * live comparison and the map row keeps its history.
     */
    @Param({"regex_lines", "csv_header", "ausearch", "ausearch_dispatch", "apache_httpd",
            "win_sec", "win_sec_strict", "win_sec_xml", "progressive", "progressive_text",
            "avro_users", "log_sessions", "element_storm"})
    public String workload;

    private Project project;
    private CompiledProject compiled;
    private byte[] input;
    private boolean wholeBuffer;

    @Setup
    public void setup() {
        switch (workload) {
            case "regex_lines" -> streamed("native/004_simple_regex/project.json",
                    FixtureLedger.bytes("legacy/004_simple_regex.in"));
            case "csv_header" -> csv();
            case "ausearch" -> streamed("projects/ausearch/project.json",
                    FixtureLedger.bytes("projects/ausearch/input.txt"));
            // The same records, the same output bytes, in two shapes without the map (design
            // 37 phase 8): a switch on the key into four scalars (on request), and a template
            // per key (a standing row).
            case "ausearch_switch" -> streamed("projects/ausearch/challenger-switch.project.json",
                    FixtureLedger.bytes("projects/ausearch/input.txt"));
            case "ausearch_dispatch" -> streamed("projects/ausearch/challenger-dispatch.project.json",
                    FixtureLedger.bytes("projects/ausearch/input.txt"));
            case "apache_httpd" -> streamed("projects/apache_httpd/project.json",
                    FixtureLedger.bytes("projects/apache_httpd/input.txt"));
            // The month table as a switch rather than a map refilled per line (design 37
            // phase 8), on request.
            case "apache_httpd_switch" -> streamed("projects/apache_httpd/challenger-switch.project.json",
                    FixtureLedger.bytes("projects/apache_httpd/input.txt"));
            case "win_sec" -> streamed("projects/win_sec/project.json",
                    FixtureLedger.bytes("projects/win_sec/input.txt"));
            // The labelled fields as a template per label in the strict dispatch, no map (design
            // 37 phase 8), on request.
            case "win_sec_perkey" -> streamed("projects/win_sec/challenger-perkey.project.json",
                    FixtureLedger.bytes("projects/win_sec/input.txt"));
            case "win_sec_switch" -> streamed("projects/win_sec/challenger-switch.project.json",
                    FixtureLedger.bytes("projects/win_sec/input.txt"));
            // The same events, the same output bytes, the strict idiom (D36): the direct A/B
            // on what dissolving the search is worth.
            case "win_sec_strict" -> streamed("projects/win_sec_strict/project.json",
                    FixtureLedger.bytes("projects/win_sec/input.txt"));
            case "win_sec_xml" -> streamed("projects/win_sec_xml/project.json",
                    FixtureLedger.bytes("projects/win_sec_xml/input.xml"));
            case "progressive" -> {
                streamed("projects/progressive_len_records/project.json",
                        FixtureLedger.bytes("projects/progressive_len_records/input.bin"));
                // Binary inputs are addressed whole; the harness and the fixtures do the same.
                wholeBuffer = true;
            }
            // A pattern tree over a text feed, so this one streams as the other text rows do.
            case "progressive_text" -> streamed("projects/progressive_text_steps/project.json",
                    FixtureLedger.bytes("projects/progressive_text_steps/input.txt"));
            // The container is written by the Avro library rather than repeated: a repeated
            // file would be a header and a sync marker per copy, which is not a container.
            // Blocks of a hundred records, sized from a sample to land just past the target as
            // the repeated rows do, so an op is the same volume here as everywhere else; the
            // parity test (AvroAmplifiedTest) holds the engine to the library's reading.
            case "avro_users" -> {
                project = ProjectReader.read(FixtureLedger.text("projects/avro_users/project.json"));
                compiled = Shapeshifter.compile(project);
                input = AvroContainers.sized(TARGET_SIZE, 100, 38L).bytes();
                wholeBuffer = true;
            }
            // References, and the frames behind them: five iterations, a grouping, four keys and
            // four sequences over delimited lines whose matching is trivial. The row where a
            // variable lookup is the work rather than a rounding error (design 30 §5).
            case "log_sessions" -> streamed("projects/log_sessions/project.json",
                    FixtureLedger.bytes("projects/log_sessions/input.txt"));
            // The same records and bytes without the status map (design 37 phase 8), on
            // request: the failures filtered at capture, or found by one scan at use.
            case "log_sessions_filtered" -> streamed("projects/log_sessions/challenger-filtered.project.json",
                    FixtureLedger.bytes("projects/log_sessions/input.txt"));
            case "log_sessions_scan" -> streamed("projects/log_sessions/challenger-scan.project.json",
                    FixtureLedger.bytes("projects/log_sessions/input.txt"));
            // The sinks under load: one one-pass regex per record, then twenty-one element,
            // attribute and namespace calls. win_sec_xml writes structure too, but spends 40% of
            // itself in the regex engine, so what the sinks cost is below its noise (design 29
            // phase 5, which was built unmeasured for want of this row).
            case "element_storm" -> streamed("projects/element_storm/project.json",
                    FixtureLedger.bytes("projects/element_storm/input.txt"));
            default -> throw new IllegalArgumentException(workload);
        }
    }

    private void streamed(final String configPath, final byte[] unit) {
        project = ProjectReader.read(FixtureLedger.text(configPath));
        compiled = Shapeshifter.compile(project);
        input = repeat(unit, new byte[0]);
    }

    /** Repeat a unit to the target size. */
    private static byte[] repeat(final byte[] unit, final byte[] prefix) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(TARGET_SIZE + unit.length);
        out.writeBytes(prefix);
        while (out.size() < TARGET_SIZE) {
            out.writeBytes(unit);
        }
        return out.toByteArray();
    }

    /**
     * CSV grows by repeating its data rows under one header — repeating the whole file would
     * turn later headers into data and change what the configuration is doing.
     */
    private void csv() {
        project = ProjectReader.read(FixtureLedger.text("native/001_csv_with_header/project.json"));
        compiled = Shapeshifter.compile(project);
        final byte[] whole = FixtureLedger.bytes("legacy/001_csv_with_header.in");
        int firstLine = 0;
        while (whole[firstLine] != '\n') {
            firstLine++;
        }
        input = repeat(
                java.util.Arrays.copyOfRange(whole, firstLine + 1, whole.length),
                java.util.Arrays.copyOfRange(whole, 0, firstLine + 1));
    }

    @Benchmark
    public long run(final Blackhole blackhole) {
        final long[] position = {0};
        final OutputSink counting = new OutputSink() {
            @Override
            public void write(final byte[] data, final int offset, final int length) {
                position[0] += length;
                blackhole.consume(data);
            }

            @Override
            public long position() {
                return position[0];
            }
        };
        // A bare sink refuses structure — its startElement throws — so a configuration that
        // writes elements has to be given a sink that carries them, exactly as the harness and
        // the pipeline do. Every row here was a text one until element_storm, which is why the
        // structured sinks were absent from this benchmark rather than merely quiet in it.
        final OutputSink sink = compiled.structured()
                ? new XmlByteSink(new OutputStream() {
                    @Override
                    public void write(final int b) {
                        position[0]++;
                    }

                    @Override
                    public void write(final byte[] data, final int offset, final int length) {
                        position[0] += length;
                        blackhole.consume(data);
                    }
                })
                : counting;
        final List<Message> messages = wholeBuffer
                ? Shapeshifter.runWhole(compiled, input, sink)
                : Shapeshifter.run(compiled, new ByteArrayInputStream(input), sink);
        blackhole.consume(messages);
        return position[0];
    }

    @Benchmark
    public CompiledProject compile() {
        return Shapeshifter.compile(project);
    }

    public static void main(final String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(EngineBenchmark.class.getSimpleName())
                .build()).run();
    }
}
