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
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.fixture.FixtureLedger;

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
 * including 209 escaping transforms; {@code progressive} is the step interpreter alone.
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

    @Param({"regex_lines", "csv_header", "ausearch", "apache_httpd",
            "win_sec", "win_sec_xml", "progressive"})
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
            case "apache_httpd" -> streamed("projects/apache_httpd/project.json",
                    FixtureLedger.bytes("projects/apache_httpd/input.txt"));
            case "win_sec" -> streamed("projects/win_sec/project.json",
                    FixtureLedger.bytes("projects/win_sec/input.txt"));
            case "win_sec_xml" -> streamed("projects/win_sec_xml/project.json",
                    FixtureLedger.bytes("projects/win_sec_xml/input.xml"));
            case "progressive" -> {
                streamed("projects/progressive_len_records/project.json",
                        FixtureLedger.bytes("projects/progressive_len_records/input.bin"));
                // Binary inputs are addressed whole; the harness and the fixtures do the same.
                wholeBuffer = true;
            }
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
        final OutputSink sink = new OutputSink() {
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
