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

package stroom.shapeshifter.xmlbench;

import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.config.ProjectReader;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One case in each of its configuration shapes (design 37 phase 8), the shapes gated
 * byte-identical against Saxon by {@link CaseShapesTest}. Not part of the {@code jmh} task:
 * the question here is not shapeshifter against Saxon but one shape against another, and it
 * is asked by hand — {@code org.openjdk.jmh.Main CaseShapesBenchmark -p shape=...} — when a
 * shape is written, not on every run. The shapes stay as fixtures; their numbers are in
 * design 37 §9.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(value = 2, jvmArgsAppend = {"-Xmx6g"})
@State(Scope.Benchmark)
public class CaseShapesBenchmark {

    @Param({"keys_lookup"})
    public String benchCase;

    @Param({"challenger.project.json", "challenger-positions.project.json",
            "challenger-lists.project.json", "challenger-pairs.project.json"})
    public String shape;

    @Param({"100000"})
    public int units;

    private byte[] input;
    private CompiledProject compiled;

    @Setup
    public void setup() {
        input = CaseCorpus.amplify(benchCase, units);
        compiled = Shapeshifter.compile(ProjectReader.read(new String(
                CaseCorpus.read(benchCase, shape), StandardCharsets.UTF_8)));
    }

    @Benchmark
    public int transform() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 2);
        final List<Message> messages = Shapeshifter.run(compiled, new ByteArrayInputStream(input),
                new XmlByteSink(out));
        for (final Message message : messages) {
            if (message.severity() == Severity.FATAL) {
                throw new IllegalStateException(benchCase + "/" + shape + ": " + message.text());
            }
        }
        return out.size();
    }
}
