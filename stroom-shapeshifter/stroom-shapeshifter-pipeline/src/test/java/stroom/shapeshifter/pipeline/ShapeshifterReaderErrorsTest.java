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
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's messages reach the pipeline's error receiver with their severity; and, since
 * design 24, a text configuration's output is characters rather than a document to be parsed,
 * so there is no second kind of error for the parser to report about its own output.
 */
class ShapeshifterReaderErrorsTest {

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

    /**
     * Design 24 (D42): a text configuration's output is characters, not a document to be parsed.
     * What looked like ill-formed XML under design 21 phase 1 is now text, delivered as it is,
     * with no elements and nothing to be fatal about — a {@code TextWriter} downstream writes it,
     * and anything that needs XML refuses it in its own words.
     */
    @Test
    void textConfigurationIsCharactersOnlyAndNothingIsParsed() throws Exception {
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

        assertThat(receiver.isAllOk()).as(receiver.getMessage()).isTrue();
        assertThat(recorder.events()).containsExactly(
                "startDocument", "characters \"<a>\n</b>\n</a>\n\"", "endDocument");
    }

    @Test
    void outputThatIsNotXmlIsCharactersAndNoElements() throws Exception {
        final Path fixture = FIXTURES.resolve("projects").resolve("xml_to_json");
        final Project project = ProjectReader.read(Files.readString(fixture.resolve("project.json")));
        final byte[] input = Files.readAllBytes(fixture.resolve("input.txt"));
        final EventRecorder recorder = new EventRecorder();

        final LoggingErrorReceiver receiver = run(project, input, recorder);

        assertThat(receiver.getTotal(Severity.FATAL_ERROR)).isZero();
        assertThat(recorder.events()).noneMatch(event -> event.startsWith("startElement"));
        assertThat(recorder.events()).anyMatch(event -> event.startsWith("characters \"{"));
    }

    /**
     * Design 24 phase 1 audit: a downstream that refuses the end of a text document must not
     * carry the engine's messages away with its exception. The refusal is the run's last
     * message, in the engine's own words for a refusal mid-run, after everything it said.
     */
    @Test
    void refusalOfTheDocumentsEndKeepsTheEnginesMessages() throws Exception {
        final Project project = ProjectReader.read("""
                {"name": "text", "version": 5,
                 "source": {"buffer_size": 64, "ignore_errors": false, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "root", "match": "source",
                   "body": [{"text": "<r>"},
                            {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "l"}},
                            {"text": "</r>"}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "l",
                   "match": {"regex": {"pattern": "([a-z0-9]+)\\n"}},
                   "body": [{"text": "<l>"}, {"value-of": {"parts": [{"capture": {"group": 1}}]}}, {"text": "</l>"}]}
                 ]}
                """);
        final EventRecorder recorder = new EventRecorder();
        final XMLFilterImpl refusing = new XMLFilterImpl() {
            @Override
            public void endDocument() throws SAXException {
                throw new SAXException("no more documents today");
            }
        };
        refusing.setContentHandler(recorder);

        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        final XMLReader parser = new ShapeshifterParserFactory(project).getParser();
        parser.setContentHandler(refusing);
        parser.setErrorHandler(Ds3Oracle.errorHandler("ShapeshifterParser", receiver));
        // The last line has no newline, so the line template cannot take it: an engine error.
        parser.parse(new InputSource(new ByteArrayInputStream("one\ntwo\nthree".getBytes(StandardCharsets.UTF_8))));

        assertThat(String.join("", recorder.events())).contains("<l>one</l><l>two</l>").doesNotContain("endDocument");
        assertThat(messages(receiver)).hasSize(2);
        assertThat(messages(receiver).get(0)).contains("Expressions failed to match all of the content");
        assertThat(messages(receiver).get(1)).contains("FATAL").contains("Output structure")
                .contains("no more documents today");
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
