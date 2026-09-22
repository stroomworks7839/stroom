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

package stroom.shapeshifter.pipeline.bench;

import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.xml.converter.ds3.RootFactory;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.ds3.Ds3Migration;
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.engine.output.XmlByteSink;
import stroom.shapeshifter.pipeline.Ds3Oracle;
import stroom.shapeshifter.pipeline.SaxToSink;

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
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shapeshifter against the engine it replaces, on the same configuration over the same input
 * (design 45). The legacy fixtures carry a DS3 config, its input and Stroom's own golden output;
 * {@link Ds3Migration} turns that config into a project, so both engines run what is by
 * construction the same job — the part of a fair comparison that is usually missing.
 *
 * <p><b>Both rows end in the same serialiser.</b> DS3 emits SAX and shapeshifter writes bytes, so
 * a naive pairing would compare different amounts of work. Here DS3's events go through
 * {@link SaxToSink} into {@link XmlByteSink} — the pairing {@code XmlByteSinkDs3GoldenTest}
 * proves byte-identical to Stroom's goldens — and shapeshifter writes to the same sink.
 * {@link #sink} is the floor they share: the recorded events replayed with no parsing at all, so
 * what differs can be read off the part that differs rather than the part they have in common.
 *
 * <p>Each row's config is compiled in {@link #setup()}, as a pipeline holds it, and the per-op
 * work is one stream: DS3 makes a parser instance with its own variables, shapeshifter runs the
 * compiled project. One op is {@link #TARGET_SIZE} of input, the engine suite's unit, so the two
 * benchmarks can be read together and rows compare across fixtures as throughput.
 *
 * <p>The rows are the legacy fixtures whose meaning survives repetition: no header that would
 * repeat into data, and no min, max or only limit, which counts per stream and would mean
 * something different at a hundred times the length.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class Ds3HeadToHeadBenchmark {

    /** One op processes this much input, so ops/s reads as quarter-MiB/s of real records. */
    private static final int TARGET_SIZE = 256 * 1024;

    private static final Path LEGACY = Paths.get(
            "..", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures", "legacy");

    @Param({"002_csv_without_header", "004_simple_regex", "006_single_line_delimited",
            "019_single_line_split", "020_escaped_values", "021_trimmed_values"})
    public String fixture;

    private RootFactory ds3Factory;
    private CompiledProject compiled;
    private byte[] input;
    private List<Object[]> events;

    @Setup
    public void setup() throws Exception {
        final String config = Files.readString(LEGACY.resolve(fixture + ".ds3.xml"));
        input = repeat(Files.readAllBytes(LEGACY.resolve(fixture + ".in")));

        ds3Factory = Ds3Oracle.factory(config);
        final Project project = Ds3Migration.importXml(config);
        compiled = Shapeshifter.compile(project);

        // The guard the whole comparison rests on: two engines that do not agree are doing two
        // different jobs, and their throughputs mean nothing side by side. Paid once per row,
        // and it fails the run rather than reporting a flattering number. The goldens hold this
        // on the fixture's own input; what is checked here is the amplified one.
        final ByteArrayOutputStream fromDs3 = new ByteArrayOutputStream();
        parseWithDs3(new SaxToSink(new XmlByteSink(fromDs3)));
        final ByteArrayOutputStream fromShapeshifter = new ByteArrayOutputStream();
        Shapeshifter.run(compiled, new ByteArrayInputStream(input), new XmlByteSink(fromShapeshifter));
        if (!Arrays.equals(fromDs3.toByteArray(), fromShapeshifter.toByteArray())) {
            throw new IllegalStateException(fixture + ": the engines disagree on the amplified input, so"
                                            + " their rows would not be measuring the same job\n"
                                            + firstDifference(fromDs3.toString(StandardCharsets.UTF_8),
                    fromShapeshifter.toString(StandardCharsets.UTF_8)));
        }

        // The floor's script, recorded from the run that was just checked.
        final Recorder recorder = new Recorder();
        parseWithDs3(recorder);
        events = recorder.ops;
    }

    /** DS3 over the input: a parser instance per stream, its events serialised by the shared sink. */
    @Benchmark
    public long ds3(final Blackhole blackhole) throws Exception {
        final Counting out = new Counting(blackhole);
        parseWithDs3(new SaxToSink(new XmlByteSink(out)));
        return out.written;
    }

    /** Shapeshifter over the same input, into the same sink. */
    @Benchmark
    public long shapeshifter(final Blackhole blackhole) {
        final Counting out = new Counting(blackhole);
        blackhole.consume(Shapeshifter.run(compiled, new ByteArrayInputStream(input), new XmlByteSink(out)));
        return out.written;
    }

    /**
     * DS3's floor: the same events across the SAX bridge and through the sink, with no input
     * parsed. This is what the {@link #ds3} row stands on, bridge included — {@link SaxToSink}
     * builds a String per text node, which is a cost of crossing SAX and belongs to that side.
     */
    @Benchmark
    public long sinkViaSax(final Blackhole blackhole) {
        final Counting out = new Counting(blackhole);
        replayViaSax(new SaxToSink(new XmlByteSink(out)));
        return out.written;
    }

    /**
     * Shapeshifter's floor: the same document written into the same sink the way the engine
     * writes it — {@code write(byte[], off, len)} for content, no SAX and no String per node.
     *
     * <p>Two floors rather than one because the engines do not stand on the same one, which the
     * first cut of this benchmark got wrong: subtracting the SAX floor from the shapeshifter row
     * takes away a bridge that row never crosses, and flatters it. The difference between the
     * two floors is what crossing SAX costs, and it belongs to DS3's side of the comparison.
     */
    @Benchmark
    public long sinkDirect(final Blackhole blackhole) {
        final Counting out = new Counting(blackhole);
        replayDirect(new XmlByteSink(out));
        return out.written;
    }

    private void parseWithDs3(final ContentHandler handler) throws Exception {
        final XMLReader parser = Ds3Oracle.parser(ds3Factory);
        parser.setContentHandler(handler);
        parser.setErrorHandler(Ds3Oracle.errorHandler("DS3Parser", new LoggingErrorReceiver()));
        parser.parse(new InputSource(
                new InputStreamReader(new ByteArrayInputStream(input), StandardCharsets.UTF_8)));
    }

    private void replayViaSax(final SaxToSink handler) {
        for (final Object[] op : events) {
            switch ((Recorder.Op) op[0]) {
                case START_DOCUMENT -> handler.startDocument();
                case PREFIX -> handler.startPrefixMapping((String) op[1], (String) op[2]);
                case START -> handler.startElement("", "", (String) op[1], (Attributes) op[2]);
                case END -> handler.endElement("", "", (String) op[1]);
                case TEXT -> {
                    final char[] text = (char[]) op[1];
                    handler.characters(text, 0, text.length);
                }
                default -> throw new IllegalStateException(String.valueOf(op[0]));
            }
        }
    }

    /** The same document, written the way the engine writes it: qNames, and content as bytes. */
    private void replayDirect(final XmlByteSink sink) {
        final List<String[]> pendingNamespaces = new ArrayList<>();
        for (final Object[] op : events) {
            switch ((Recorder.Op) op[0]) {
                case START_DOCUMENT -> sink.write(SaxToSink.DECLARATION);
                case PREFIX -> pendingNamespaces.add(new String[]{(String) op[1], (String) op[2]});
                case START -> {
                    sink.startElement((String) op[1]);
                    for (final String[] ns : pendingNamespaces) {
                        sink.namespace(ns[0], ns[1]);
                    }
                    pendingNamespaces.clear();
                    final Attributes atts = (Attributes) op[2];
                    for (int i = 0; i < atts.getLength(); i++) {
                        final String name = atts.getQName(i);
                        if (name.equals("xmlns") || name.startsWith("xmlns:")) {
                            continue;
                        }
                        sink.startAttribute(name);
                        sink.write(atts.getValue(i));
                        sink.endAttribute();
                    }
                }
                case END -> sink.endElement();
                case TEXT -> {
                    final byte[] text = (byte[]) op[2];
                    sink.write(text, 0, text.length);
                }
                default -> throw new IllegalStateException(String.valueOf(op[0]));
            }
        }
    }

    /** Repeat the fixture's input to the target size. */
    private static byte[] repeat(final byte[] unit) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(TARGET_SIZE + unit.length);
        while (out.size() < TARGET_SIZE) {
            out.writeBytes(unit);
        }
        return out.toByteArray();
    }

    private static String firstDifference(final String expected, final String actual) {
        final List<String> e = expected.lines().toList();
        final List<String> a = actual.lines().toList();
        for (int i = 0; i < Math.max(e.size(), a.size()); i++) {
            final String x = i < e.size()
                    ? e.get(i)
                    : "<end>";
            final String y = i < a.size()
                    ? a.get(i)
                    : "<end>";
            if (!x.equals(y)) {
                return "  line " + (i + 1) + "\n    ds3:          [" + x + "]\n    shapeshifter: [" + y + "]";
            }
        }
        return "  identical lines, different bytes";
    }

    /** DS3's events, kept so the floor can replay them without parsing anything. */
    private static final class Recorder implements ContentHandler {

        private enum Op {
            START_DOCUMENT, PREFIX, START, END, TEXT
        }

        private final List<Object[]> ops = new ArrayList<>();

        @Override
        public void startDocument() {
            ops.add(new Object[]{Op.START_DOCUMENT});
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri) {
            ops.add(new Object[]{Op.PREFIX, prefix, uri});
        }

        @Override
        public void startElement(final String uri, final String localName, final String qName,
                                 final Attributes atts) {
            ops.add(new Object[]{Op.START, qName, new AttributesImpl(atts)});
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            ops.add(new Object[]{Op.END, qName});
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            // Both shapes of the same text: chars for the SAX bridge, UTF-8 bytes for the call
            // the engine actually makes. Recorded once in setup, so neither floor pays for the
            // other's representation.
            final char[] chars = Arrays.copyOfRange(ch, start, start + length);
            ops.add(new Object[]{Op.TEXT, chars, new String(chars).getBytes(StandardCharsets.UTF_8)});
        }

        @Override
        public void setDocumentLocator(final Locator locator) {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void endPrefixMapping(final String prefix) {
        }

        @Override
        public void ignorableWhitespace(final char[] ch, final int start, final int length) {
        }

        @Override
        public void processingInstruction(final String target, final String data) {
        }

        @Override
        public void skippedEntity(final String name) {
        }
    }

    /** Counts the bytes and keeps the JIT honest, without the cost of keeping them. */
    private static final class Counting extends OutputStream {

        private final Blackhole blackhole;
        private long written;

        private Counting(final Blackhole blackhole) {
            this.blackhole = blackhole;
        }

        @Override
        public void write(final int b) {
            written++;
        }

        @Override
        public void write(final byte[] data, final int offset, final int length) {
            written += length;
            blackhole.consume(data);
        }
    }

    public static void main(final String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(Ds3HeadToHeadBenchmark.class.getSimpleName())
                .build()).run();
    }
}
