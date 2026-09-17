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

import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.fixture.AvroContainers;
import stroom.shapeshifter.engine.fixture.FixtureLedger;
import stroom.shapeshifter.engine.fixture.ProtobufMessages;
import stroom.shapeshifter.engine.graph.CompiledProject;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Parser;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.SeekableByteArrayInput;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * The binary formats' head-to-head (design 40 §6): the same Avro container and the same
 * protobuf stream through the Java libraries that own those formats and through the engine's
 * library-free configurations. Run by name; not one of {@link EngineBenchmark}'s rows.
 *
 * <p>Two engine rows per format. <em>Parse only</em> keeps every match, read and dispatch and
 * writes nothing — the record templates' output ops removed — which is the like-for-like
 * against a library decoding every field into Java values and writing nothing. <em>With
 * output</em> is the fixture as written, emitting its XML, which is what the engine does in
 * a pipeline. The libraries read generically — {@code GenericDatumReader}, {@code
 * DynamicMessage} — as an engine with no schema class would; a generated class would be
 * faster still, and is noted rather than run.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class BinaryParseBenchmark {

    private static final int TARGET_SIZE = 256 * 1024;

    private byte[] avro;
    private byte[] protobuf;
    private Schema userSchema;
    private Descriptors.Descriptor eventDescriptor;
    private Parser<DynamicMessage> eventParser;
    private CompiledProject avroFull;
    private CompiledProject avroParseOnly;
    private CompiledProject protobufFull;
    private CompiledProject protobufParseOnly;

    @Setup
    public void setup() throws Exception {
        avro = AvroContainers.sized(TARGET_SIZE, 100, 38L).bytes();
        protobuf = ProtobufMessages.sized(TARGET_SIZE, 40L).bytes();
        userSchema = new Schema.Parser().parse("""
                {"type":"record","name":"User","fields":[
                  {"name":"name","type":"string"},{"name":"age","type":"long"},
                  {"name":"score","type":"double"},{"name":"active","type":"boolean"}]}""");
        eventDescriptor = ProtobufMessages.eventDescriptor();
        eventParser = DynamicMessage.getDefaultInstance(eventDescriptor).getParserForType();
        final String avroJson = FixtureLedger.text("projects/avro_users/project.json");
        final String protobufJson = FixtureLedger.text("projects/protobuf_events/project.json");
        avroFull = Shapeshifter.compile(ProjectReader.read(avroJson));
        avroParseOnly = Shapeshifter.compile(ProjectReader.read(parseOnly(avroJson)));
        protobufFull = Shapeshifter.compile(ProjectReader.read(protobufJson));
        protobufParseOnly = Shapeshifter.compile(ProjectReader.read(parseOnly(protobufJson)));
    }

    /**
     * The configuration with its output taken out and its parse left in: every body keeps
     * only the ops that dispatch or bind — {@code apply-templates}, {@code variable} — so
     * matching, reads, captures and the walk over the structure all still happen.
     */
    static String parseOnly(final String json) {
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode root = (ObjectNode) mapper.readTree(json);
        for (final JsonNode template : root.get("templates")) {
            final ArrayNode body = (ArrayNode) template.get("body");
            final ArrayNode kept = mapper.createArrayNode();
            for (final JsonNode op : body) {
                final String key = op.propertyNames().iterator().next();
                if (key.equals("apply-templates") || key.equals("variable")) {
                    kept.add(op);
                }
            }
            ((ObjectNode) template).set("body", kept);
        }
        return mapper.writeValueAsString(root);
    }

    @Benchmark
    public long avroJavaGeneric(final Blackhole blackhole) throws IOException {
        long sum = 0;
        try (DataFileReader<GenericRecord> reader = new DataFileReader<>(
                new SeekableByteArrayInput(avro), new GenericDatumReader<>(userSchema))) {
            GenericRecord user = null;
            while (reader.hasNext()) {
                user = reader.next(user);
                sum += ((CharSequence) user.get("name")).length() + (Long) user.get("age");
                blackhole.consume(user.get("score"));
                blackhole.consume(user.get("active"));
            }
        }
        return sum;
    }

    @Benchmark
    public long shapeshifterAvroParseOnly() {
        return run(avroParseOnly, avro);
    }

    @Benchmark
    public long shapeshifterAvroWithOutput() {
        return run(avroFull, avro);
    }

    @Benchmark
    public long protobufJavaDynamic(final Blackhole blackhole) throws IOException {
        final Descriptors.FieldDescriptor id = eventDescriptor.findFieldByName("id");
        final Descriptors.FieldDescriptor kind = eventDescriptor.findFieldByName("kind");
        final Descriptors.FieldDescriptor ok = eventDescriptor.findFieldByName("ok");
        final ByteArrayInputStream in = new ByteArrayInputStream(protobuf);
        long sum = 0;
        DynamicMessage message;
        while ((message = eventParser.parseDelimitedFrom(in)) != null) {
            sum += (Integer) message.getField(id) + ((String) message.getField(kind)).length();
            blackhole.consume(message.getField(ok));
        }
        return sum;
    }

    @Benchmark
    public long shapeshifterProtobufParseOnly() {
        return run(protobufParseOnly, protobuf);
    }

    @Benchmark
    public long shapeshifterProtobufWithOutput() {
        return run(protobufFull, protobuf);
    }

    private static long run(final CompiledProject compiled, final byte[] input) {
        final long[] written = {0};
        final OutputSink sink = new OutputSink() {
            @Override
            public void write(final byte[] data, final int offset, final int length) {
                written[0] += length;
            }

            @Override
            public long position() {
                return written[0];
            }
        };
        Shapeshifter.runWhole(compiled, input, sink);
        return written[0];
    }
}
