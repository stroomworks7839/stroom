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
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.ds3.Ds3Migration;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 21 phase 1: the two kinds of error reach the pipeline's error receiver, and are told
 * apart — the engine's messages about the input, the parser's about the output.
 */
class ShapeshifterParserErrorsTest {

    private static final Path FIXTURES = Paths.get(
            "..", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures");

    @Test
    void theEnginesMessagesReachTheReceiverWithTheirSeverity() throws Exception {
        final Path legacy = FIXTURES.resolve("legacy");
        final Project project = Ds3Migration.importXml(
                Files.readString(legacy.resolve("005_unmatched_content_FAIL.ds3.xml")));
        final byte[] input = Files.readAllBytes(legacy.resolve("005_unmatched_content_FAIL.in"));

        final LoggingErrorReceiver receiver = run(project, input, new EventRecorder());

        assertThat(receiver.getTotal(Severity.ERROR)).isEqualTo(1);
        assertThat(receiver.getTotal(Severity.FATAL_ERROR)).isZero();
        assertThat(messages(receiver)).singleElement().asString()
                .contains("Expressions failed to match all of the content");
    }

    @Test
    void illFormedOutputIsReportedAgainstTheOutputLineNotTheInput() throws Exception {
        final Project project = ProjectReader.read("""
                {"name": "unbalanced", "version": 3,
                 "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "auto"},
                 "templates": [{
                   "id": "1d1c0c0e-0000-4000-8000-000000000001", "name": "all",
                   "match": {"source": {}},
                   "body": [{"text": "<a>\\n</b>\\n</a>\\n"}]
                 }]}
                """);
        final EventRecorder recorder = new EventRecorder();

        final LoggingErrorReceiver receiver = run(project, "anything".getBytes(StandardCharsets.UTF_8), recorder);

        assertThat(receiver.getTotal(Severity.FATAL_ERROR)).isEqualTo(1);
        assertThat(messages(receiver)).singleElement().asString()
                .contains("not well-formed XML")
                .contains("line 2, column")
                .contains("of the output, not the input")
                .contains("Output line 2: </b>");
        assertThat(recorder.events()).contains("startElement {}a []");
    }

    @Test
    void outputThatIsNotXmlIsOneFatalAndNoElements() throws Exception {
        final Path fixture = FIXTURES.resolve("projects").resolve("xml_to_json");
        final Project project = ProjectReader.read(Files.readString(fixture.resolve("project.json")));
        final byte[] input = Files.readAllBytes(fixture.resolve("input.txt"));
        final EventRecorder recorder = new EventRecorder();

        final LoggingErrorReceiver receiver = run(project, input, recorder);

        assertThat(receiver.getTotal(Severity.FATAL_ERROR)).isEqualTo(1);
        assertThat(messages(receiver)).singleElement().asString()
                .contains("not well-formed XML").contains("Output line 1: {");
        assertThat(recorder.events()).noneMatch(event -> event.startsWith("startElement"));
    }

    /** Everything logged against the parser, as text — the receiver's own summary is per record and clears. */
    private static List<String> messages(final LoggingErrorReceiver receiver) {
        return receiver.getIndicators(new ElementId("ShapeshifterParser")).getErrorList().stream()
                .map(StoredError::toString)
                .toList();
    }

    private static LoggingErrorReceiver run(final Project project,
                                            final byte[] input,
                                            final EventRecorder recorder) throws Exception {
        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        final XMLReader parser = new ShapeshifterParserFactory(project).getParser();
        parser.setContentHandler(recorder);
        parser.setErrorHandler(Ds3Oracle.errorHandler("ShapeshifterParser", receiver));
        parser.parse(new InputSource(new ByteArrayInputStream(input)));
        return receiver;
    }
}
