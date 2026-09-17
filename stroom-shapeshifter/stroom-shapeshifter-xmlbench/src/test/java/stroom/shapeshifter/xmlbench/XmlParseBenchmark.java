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

import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.engine.output.SaxEventSink;

import com.ctc.wstx.stax.WstxInputFactory;
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
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * XML parsed to SAX events with nothing transformed (design 40): the same document through
 * three parsers, each from bytes and from a {@link String}, into a handler that counts
 * elements, attributes and characters so nothing is optimised away.
 *
 * <p>Two of the parsers are incumbents that read XML in general — the JDK's SAX parser and
 * Woodstox's StAX — and the third is the engine running a configuration that tokenises
 * <em>this corpus's shape</em>: elements, attributes and text, entities decoded; no CDATA,
 * comments, processing instructions or DTD. It is a comparison for this document shape, not
 * a claim that the engine is an XML parser. {@link XmlParseParityTest} holds all six rows to
 * identical events.
 *
 * <p>The engine is byte-native, so its string row is a UTF-8 encode and then the byte parse,
 * and the encode is inside the measured region: that is what handing it a string costs. The
 * incumbents read a {@code Reader} directly, and their string rows show what they save by not
 * decoding. Whole-file, single-shot, as the baseline is; MiB/s is bytes ÷ ms ÷ 1048.576.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(value = 2, jvmArgsAppend = {"-Xmx6g",
        "-Djdk.xml.maxGeneralEntitySizeLimit=0", "-Djdk.xml.totalEntitySizeLimit=0"})
@State(Scope.Benchmark)
public class XmlParseBenchmark {

    @Param({"10000", "100000", "1000000"})
    public int records;

    private byte[] bytes;
    private String text;
    private SAXParserFactory saxFactory;
    private WstxInputFactory staxFactory;
    private CompiledProject parser;

    @Setup
    public void setup() throws Exception {
        bytes = RecordsGenerator.generate(records);
        text = new String(bytes, StandardCharsets.UTF_8);
        saxFactory = SAXParserFactory.newInstance();
        saxFactory.setNamespaceAware(true);
        staxFactory = new WstxInputFactory();
        try (var config = getClass().getResourceAsStream("/xmlbench/parse.project.json")) {
            parser = Shapeshifter.compile(ProjectReader.read(
                    new String(config.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    @Benchmark
    public long xercesSaxBytes() throws Exception {
        final Counting handler = new Counting();
        saxFactory.newSAXParser().parse(new InputSource(new ByteArrayInputStream(bytes)), handler);
        return handler.total();
    }

    @Benchmark
    public long xercesSaxString() throws Exception {
        final Counting handler = new Counting();
        saxFactory.newSAXParser().parse(new InputSource(new StringReader(text)), handler);
        return handler.total();
    }

    @Benchmark
    public long woodstoxStaxBytes() throws Exception {
        return stax(staxFactory.createXMLStreamReader(new ByteArrayInputStream(bytes)));
    }

    @Benchmark
    public long woodstoxStaxString() throws Exception {
        return stax(staxFactory.createXMLStreamReader(new StringReader(text)));
    }

    @Benchmark
    public long shapeshifterBytes() {
        final Counting handler = new Counting();
        Shapeshifter.run(parser, new ByteArrayInputStream(bytes), new SaxEventSink(handler));
        return handler.total();
    }

    /** The encode is measured: it is what a string costs a byte-native engine. */
    @Benchmark
    public long shapeshifterString() {
        final Counting handler = new Counting();
        Shapeshifter.run(parser, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new SaxEventSink(handler));
        return handler.total();
    }

    private static long stax(final XMLStreamReader reader) throws Exception {
        long elements = 0;
        long attributes = 0;
        long characters = 0;
        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    elements++;
                    attributes += reader.getAttributeCount();
                }
                case XMLStreamConstants.CHARACTERS -> characters += reader.getTextLength();
                default -> {
                }
            }
        }
        reader.close();
        return elements * 1_000_000_000L + attributes * 1_000L + characters;
    }

    /** Counts what arrives, so the parse cannot be optimised away and the rows can be checked for the same totals. */
    private static final class Counting extends DefaultHandler {

        private long elements;
        private long attributes;
        private long characters;

        @Override
        public void startElement(final String uri, final String localName, final String qName,
                                 final Attributes attrs) {
            elements++;
            attributes += attrs.getLength();
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            characters += length;
        }

        long total() {
            return elements * 1_000_000_000L + attributes * 1_000L + characters;
        }
    }
}
