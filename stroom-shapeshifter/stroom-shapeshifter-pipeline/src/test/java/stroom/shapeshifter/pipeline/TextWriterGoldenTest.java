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

import stroom.pipeline.destination.Destination;
import stroom.pipeline.destination.DestinationProvider;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.factory.AbstractElement;
import stroom.pipeline.writer.TextWriter;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.util.shared.ElementId;
import stroom.util.shared.Indicators;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 24 §2: a text configuration streams to a byte sink through Stroom's own
 * {@link TextWriter}, and what reaches the sink is the engine's bytes exactly — the same golden
 * the engine's byte sink is held to. The {@code _exact} fixtures are the ones that reproduce
 * Stroom's DS3 output byte for byte from text emitters (design 21 phase 3); the others pin what
 * the text family writes as written.
 */
class TextWriterGoldenTest {

    private static final Path PROJECTS = Paths.get(
            "..", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures", "projects");

    @ParameterizedTest
    @ValueSource(strings = {
            "text_007_regex_dotall_exact", "text_021_trimmed_values_exact", "text_022_empty_input_exact",
            "text_003_multiline_regex", "text_007_regex_dotall", "text_009_multiline_regex_2",
            "text_019_single_line_split"})
    void theBytesReachingTheDestinationAreTheGolden(final String fixture) throws Exception {
        final Path dir = PROJECTS.resolve(fixture);
        final byte[] golden = Files.readAllBytes(dir.resolve("example_output.xml"));
        final byte[] input = Files.readAllBytes(dir.resolve("input.txt"));
        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();

        final ByteArrayOutputStream destination = new ByteArrayOutputStream();
        final TextWriter writer = new TextWriter(new ErrorReceiverProxy(receiver));
        writer.setElementId(new ElementId("TextWriter"));
        writer.addTarget(new CapturingDestination(destination));

        final XMLReader reader = new ShapeshifterParserFactory(
                ProjectReader.read(Files.readString(dir.resolve("project.json")))).getParser();
        reader.setContentHandler(writer);
        reader.setErrorHandler(Ds3Oracle.errorHandler("ShapeshifterParser", receiver));
        writer.startProcessing();
        try {
            reader.parse(new InputSource(new ByteArrayInputStream(input)));
        } finally {
            writer.endProcessing();
        }

        // The engine's messages about the input are the fixture's own — 003's unmatched
        // separator is an error DS3 reports too — and the reader reports exactly those, with
        // their severity; nothing comes from the writer.
        final List<String> expected = Shapeshifter.run(
                        Shapeshifter.compile(ProjectReader.read(Files.readString(dir.resolve("project.json")))),
                        new ByteArrayInputStream(input), OutputSink.of(new ByteArrayOutputStream()))
                .stream().map(Message::toString).toList();
        final List<String> reported = logged(receiver, "ShapeshifterParser");
        assertThat(reported).as(fixture).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            final String text = expected.get(i).substring(expected.get(i).indexOf('\t') + 1);
            assertThat(reported.get(i)).as(fixture).contains(text);
        }
        assertThat(receiver.getTotal(Severity.FATAL_ERROR)).as(fixture).isZero();
        assertThat(logged(receiver, "TextWriter")).as(fixture).isEmpty();
        assertThat(destination.toByteArray()).isEqualTo(golden);
    }

    private static List<String> logged(final LoggingErrorReceiver receiver, final String elementId) {
        final Indicators indicators = receiver.getIndicators(new ElementId(elementId));
        return indicators == null
                ? List.of()
                : indicators.getErrorList().stream().map(StoredError::toString).toList();
    }

    /** A destination that is one byte array — what a {@code FileAppender} is to a file. */
    private static final class CapturingDestination extends AbstractElement
            implements DestinationProvider, Destination {

        private final OutputStream bytes;

        private CapturingDestination(final OutputStream bytes) {
            this.bytes = bytes;
            setElementId(new ElementId("Destination"));
        }

        @Override
        public Destination borrowDestination() {
            return this;
        }

        @Override
        public void returnDestination(final Destination destination) {
        }

        @Override
        public OutputStream getOutputStream() {
            return bytes;
        }

        @Override
        public OutputStream getOutputStream(final byte[] header, final byte[] footer) {
            return bytes;
        }

        @Override
        public java.util.List<stroom.pipeline.factory.Processor> createProcessors() {
            return java.util.List.of();
        }
    }
}
