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

package stroom.shapeshifter.engine.fixture;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

/**
 * The {@code protobuf_events} messages amplified by protobuf-java from the fixture's own
 * descriptor, and their expected output read back by the same library — the independent
 * writer and reader for the engine's library-free parse (design 40 §6), as the Avro library
 * is for {@link AvroContainers}. Length-delimited messages, as the fixture's stream is;
 * proto3, so a false {@code ok} is not written, which is the case the engine's configuration
 * has to get right without the schema.
 */
public final class ProtobufMessages {

    private static final String[] KINDS = {"login", "click", "logout", "view", "purchase", "error", "heartbeat"};

    private ProtobufMessages() {
    }

    public record Amplified(byte[] bytes, String expected) {

    }

    /** The {@code test.Event} descriptor, from the fixture's {@code schema.desc}. */
    public static Descriptors.Descriptor eventDescriptor() {
        try {
            final DescriptorProtos.FileDescriptorSet set = DescriptorProtos.FileDescriptorSet.parseFrom(
                    FixtureLedger.bytes("projects/protobuf_events/schema.desc"));
            final Descriptors.FileDescriptor file = Descriptors.FileDescriptor.buildFrom(
                    set.getFile(0), new Descriptors.FileDescriptor[0]);
            return file.findMessageTypeByName("Event");
        } catch (final IOException | Descriptors.DescriptorValidationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code count} events from a fixed seed, delimited as the fixture's are. */
    public static Amplified amplify(final int count, final long seed) {
        final Descriptors.Descriptor event = eventDescriptor();
        final Descriptors.FieldDescriptor id = event.findFieldByName("id");
        final Descriptors.FieldDescriptor kind = event.findFieldByName("kind");
        final Descriptors.FieldDescriptor ok = event.findFieldByName("ok");
        final Random random = new Random(seed);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(count * 16);
        try {
            for (int i = 1; i <= count; i++) {
                DynamicMessage.newBuilder(event)
                        .setField(id, i)
                        .setField(kind, KINDS[random.nextInt(KINDS.length)])
                        .setField(ok, random.nextBoolean())
                        .build()
                        .writeDelimitedTo(out);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final byte[] bytes = out.toByteArray();
        return new Amplified(bytes, readBack(bytes, event));
    }

    /** A stream just past {@code targetBytes}, as the benchmark's other inputs are. */
    public static Amplified sized(final int targetBytes, final long seed) {
        final int sample = 1000;
        final int perMessage = amplify(sample, seed).bytes().length / sample;
        return amplify(targetBytes / perMessage + 1, seed);
    }

    private static String readBack(final byte[] bytes, final Descriptors.Descriptor event) {
        final Descriptors.FieldDescriptor id = event.findFieldByName("id");
        final Descriptors.FieldDescriptor kind = event.findFieldByName("kind");
        final Descriptors.FieldDescriptor ok = event.findFieldByName("ok");
        final StringBuilder expected = new StringBuilder();
        try {
            final ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            final com.google.protobuf.Parser<DynamicMessage> parser = DynamicMessage.getDefaultInstance(event)
                    .getParserForType();
            DynamicMessage message;
            while ((message = parser.parseDelimitedFrom(in)) != null) {
                expected.append("<event id=\"").append(message.getField(id))
                        .append("\" kind=\"").append(message.getField(kind))
                        .append("\" ok=\"").append(message.getField(ok))
                        .append("\"/>\n");
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return expected.toString();
    }
}
