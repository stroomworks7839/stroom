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

package stroom.shapeshifter.pipeline;

import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.SaxEventSink;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.XmlByteSink;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.config.ProjectReader;

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 21 phase 2b's invariant: for a structured configuration, the events the native sink
 * emits are the events a parser reports for the byte sink's output. That is what lets S7's
 * parse-and-forward and the native path stand in for each other.
 */
class StructuredEventsTest {

    private static final Path FIXTURE = Paths.get(
            "..", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures", "projects",
            "event_logging_structured");

    @Test
    void theNativeEventSinkAndTheParsedByteSinkAgreeEventForEvent() throws Exception {
        final CompiledProject compiled = Shapeshifter.compile(
                ProjectReader.read(Files.readString(FIXTURE.resolve("project.json"))));
        final byte[] input = Files.readAllBytes(FIXTURE.resolve("input.txt"));

        final EventRecorder viaBytes = new EventRecorder();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final List<Message> byteMessages = Shapeshifter.runWhole(compiled, input, new XmlByteSink(bytes));
        final XMLReader parser = new ShapeshifterParserFactory(ProjectReader.read(
                Files.readString(FIXTURE.resolve("project.json")))).getParser();
        parser.setContentHandler(viaBytes);
        parser.setErrorHandler(Ds3Oracle.errorHandler("ShapeshifterParser", new LoggingErrorReceiver()));
        parser.parse(new InputSource(new ByteArrayInputStream(input)));

        final EventRecorder viaEvents = new EventRecorder();
        final List<Message> eventMessages = Shapeshifter.runWhole(compiled, input, new SaxEventSink(viaEvents));

        // The fixture's eater warns about its junk line on either target; the point is that the
        // targets say the same thing and forward the same events.
        assertThat(byteMessages).hasSize(1);
        assertThat(eventMessages).isEqualTo(byteMessages);
        assertThat(viaEvents.events()).isNotEmpty().containsExactlyElementsOf(viaBytes.events());

        // And the reader itself, which since design 22 phase 2 runs a structured configuration
        // straight into the event sink, agrees with a parse of the byte sink's output.
        final EventRecorder viaReader = new EventRecorder();
        assertThat(compiled.structured()).isTrue();
        final ByteArrayOutputStream parsed = new ByteArrayOutputStream();
        Shapeshifter.runWhole(compiled, input, new XmlByteSink(parsed));
        final EventRecorder viaParse = new EventRecorder();
        final XMLReader plain = new ShapeshifterParserFactory(ProjectReader.read(
                Files.readString(FIXTURE.resolve("project.json")))).getParser();
        plain.setContentHandler(viaReader);
        plain.setErrorHandler(Ds3Oracle.errorHandler("ShapeshifterParser", new LoggingErrorReceiver()));
        plain.parse(new InputSource(new ByteArrayInputStream(input)));
        assertThat(viaReader.events()).containsExactlyElementsOf(viaEvents.events());
        assertThat(bytes.toString()).startsWith("<?xml version=\"1.1\"");
    }
}
