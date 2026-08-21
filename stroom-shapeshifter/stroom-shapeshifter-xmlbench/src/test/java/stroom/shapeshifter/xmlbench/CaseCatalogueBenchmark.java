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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.config.ProjectReader;

/**
 * The catalogue at benchmark scale: every case with a challenger, amplified by
 * {@link CaseCorpus} and run A/B — Saxon against the shapeshifter engine, whole-file,
 * single-shot, both compiled in setup per the fair-test ruling. {@code CaseAmplifierTest}
 * is the licence for these rows: it holds amplified inputs byte-identical, so a number here
 * is a measurement of the same job the correctness catalogue proved, per capability family
 * rather than one workload's blend. {@code input.length} is reported per case and size in
 * the JSON output's params for MiB/s arithmetic offline.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(value = 2, jvmArgsAppend = {"-Xmx6g",
        "-Djdk.xml.maxGeneralEntitySizeLimit=0", "-Djdk.xml.totalEntitySizeLimit=0"})
@State(Scope.Benchmark)
public class CaseCatalogueBenchmark {

    @Param({"adjacent_groups", "analyze_string", "computed_names", "modes",
            "nasty_xml", "reference", "string_functions"})
    public String benchCase;

    @Param({"10000", "100000"})
    public int units;

    private byte[] input;
    private Templates incumbent;
    private CompiledProject challenger;

    @Setup
    public void setup() throws Exception {
        input = CaseCorpus.amplify(benchCase, units);
        final TransformerFactory factory = new net.sf.saxon.TransformerFactoryImpl();
        incumbent = factory.newTemplates(new StreamSource(
                new ByteArrayInputStream(CaseCorpus.read(benchCase, "transform.xsl"))));
        challenger = Shapeshifter.compile(ProjectReader.read(new String(
                CaseCorpus.read(benchCase, "challenger.project.json"), StandardCharsets.UTF_8)));
    }

    @Benchmark
    public int saxonTransform() throws Exception {
        final Transformer transformer = incumbent.newTransformer();
        final ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 2);
        transformer.transform(
                new StreamSource(new ByteArrayInputStream(input)),
                new StreamResult(out));
        return out.size();
    }

    @Benchmark
    public int shapeshifterTransform() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 2);
        Shapeshifter.run(challenger, new ByteArrayInputStream(input), OutputSink.of(out));
        return out.size();
    }

    public static void main(final String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(CaseCatalogueBenchmark.class.getSimpleName())
                .build()).run();
    }
}
