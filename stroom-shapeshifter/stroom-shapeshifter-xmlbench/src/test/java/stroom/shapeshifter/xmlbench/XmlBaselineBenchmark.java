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
import java.io.StringReader;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.config.ProjectReader;

/**
 * The incumbent, decomposed (design/13, Phase 2). Three rows so the eventual comparison
 * teaches where time goes, not just who won:
 *
 * <ol>
 *   <li>{@link #saxParse} — a namespace-aware SAX parse into a counting handler: the floor,
 *       what the XML machinery alone costs;</li>
 *   <li>{@link #identityTransform} — Saxon identity: parse plus tree plus re-serialise, no
 *       real transform;</li>
 *   <li>{@link #eventsTransform} — the adapted EVENTS stylesheet: the incumbent proper.</li>
 * </ol>
 *
 * <p>Both stylesheets are compiled to {@link Templates} in setup — outside the measured
 * region, per the fair-test ruling. Whole-file operations, single-shot mode; MiB/s is
 * {@code bytes ÷ ms ÷ 1048.576}.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(value = 2, jvmArgsAppend = {"-Xmx6g",
        "-Djdk.xml.maxGeneralEntitySizeLimit=0", "-Djdk.xml.totalEntitySizeLimit=0"})
@State(Scope.Benchmark)
public class XmlBaselineBenchmark {

    private static final String IDENTITY = """
            <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
              <xsl:template match="@*|node()">
                <xsl:copy><xsl:apply-templates select="@*|node()"/></xsl:copy>
              </xsl:template>
            </xsl:stylesheet>""";

    @Param({"10000", "100000", "1000000"})
    public int records;

    private byte[] input;
    private SAXParserFactory saxFactory;
    private Templates identity;
    private Templates events;
    private CompiledProject challenger;

    @Setup
    public void setup() throws Exception {
        input = RecordsGenerator.generate(records);
        saxFactory = SAXParserFactory.newInstance();
        saxFactory.setNamespaceAware(true);
        final TransformerFactory factory = new net.sf.saxon.TransformerFactoryImpl();
        identity = factory.newTemplates(new StreamSource(new StringReader(IDENTITY)));
        try (var xsl = getClass().getResourceAsStream("/xmlbench/events-adapted.xsl")) {
            events = factory.newTemplates(new StreamSource(xsl));
        }
        try (var config = getClass().getResourceAsStream("/xmlbench/challenger.project.json")) {
            challenger = Shapeshifter.compile(ProjectReader.read(
                    new String(config.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    /** Counts elements so the parse cannot be optimised away. */
    private static final class Counting extends DefaultHandler {

        private int elements;

        @Override
        public void startElement(final String uri, final String localName, final String qName,
                                 final Attributes attributes) {
            elements++;
        }
    }

    @Benchmark
    public int saxParse() throws Exception {
        final Counting handler = new Counting();
        saxFactory.newSAXParser().parse(new ByteArrayInputStream(input), handler);
        return handler.elements;
    }

    @Benchmark
    public int identityTransform() throws Exception {
        return transform(identity);
    }

    @Benchmark
    public int eventsTransform() throws Exception {
        return transform(events);
    }

    /**
     * The challenger (design/13, Phase 3): the same job through the shapeshifter engine,
     * compiled in setup exactly as the stylesheets are, parity-gated byte-identical by
     * {@code ChallengerParityTest} before it was allowed here.
     */
    @Benchmark
    public int shapeshifterTransform() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 2);
        Shapeshifter.run(challenger, new ByteArrayInputStream(input), OutputSink.of(out));
        return out.size();
    }

    private int transform(final Templates templates) throws Exception {
        final Transformer transformer = templates.newTransformer();
        final ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 2);
        transformer.transform(
                new StreamSource(new ByteArrayInputStream(input)),
                new StreamResult(out));
        return out.size();
    }

    public static void main(final String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(XmlBaselineBenchmark.class.getSimpleName())
                .build()).run();
    }
}
