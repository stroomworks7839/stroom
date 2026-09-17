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

import stroom.shapeshifter.engine.ProjectReader;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.engine.output.SaxEventSink;

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
import org.xml.sax.helpers.DefaultHandler;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * JSON lines parsed to a token stream with nothing transformed (design 40 §5): the same
 * records as the XML head-to-head, through Jackson's streaming parser and through the engine,
 * each from bytes and from a {@link String}, into a count of objects, fields and value
 * characters. The engine runs a configuration that tokenises <em>this corpus's shape</em> —
 * one object per line, string and bare values, two escapes decoded — into field events; it
 * is a comparison for this shape, not a claim that the engine is a JSON parser.
 * {@link JsonParseParityTest} holds all four rows to the same records.
 *
 * <p>As in the XML row, the engine's string row encodes to UTF-8 inside the measured region.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(value = 2, jvmArgsAppend = {"-Xmx6g"})
@State(Scope.Benchmark)
public class JsonParseBenchmark {

    @Param({"10000", "100000", "1000000"})
    public int records;

    private byte[] bytes;
    private String text;
    private JsonFactory factory;
    private CompiledProject parser;

    @Setup
    public void setup() throws Exception {
        bytes = JsonRecordsGenerator.generate(records);
        text = new String(bytes, StandardCharsets.UTF_8);
        factory = JsonFactory.builder().build();
        try (var config = getClass().getResourceAsStream("/xmlbench/json-parse.project.json")) {
            parser = Shapeshifter.compile(ProjectReader.read(
                    new String(config.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    @Benchmark
    public long jacksonBytes() throws Exception {
        try (JsonParser json = factory.createParser(bytes)) {
            return tokens(json);
        }
    }

    @Benchmark
    public long jacksonString() throws Exception {
        try (JsonParser json = factory.createParser(text)) {
            return tokens(json);
        }
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

    private static long tokens(final JsonParser json) throws Exception {
        long objects = 0;
        long fields = 0;
        long characters = 0;
        JsonToken token;
        while ((token = json.nextToken()) != null) {
            switch (token) {
                case START_OBJECT -> objects++;
                case PROPERTY_NAME -> fields++;
                // Materialised, as the XML incumbents' attribute values and the engine's are:
                // the length alone would let the parser skip making the string at all.
                case VALUE_STRING, VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> characters += json.getString().length();
                default -> {
                }
            }
        }
        return objects * 1_000_000_000L + fields * 1_000L + characters;
    }

    /** Counts the engine's record and field events the way {@link #tokens} counts Jackson's. */
    private static final class Counting extends DefaultHandler {

        private long objects;
        private long fields;
        private long characters;

        @Override
        public void startElement(final String uri, final String localName, final String qName,
                                 final Attributes attributes) {
            switch (localName) {
                case "record" -> objects++;
                case "field" -> {
                    fields++;
                    characters += attributes.getValue("value").length();
                }
                default -> {
                }
            }
        }

        long total() {
            return objects * 1_000_000_000L + fields * 1_000L + characters;
        }
    }
}
