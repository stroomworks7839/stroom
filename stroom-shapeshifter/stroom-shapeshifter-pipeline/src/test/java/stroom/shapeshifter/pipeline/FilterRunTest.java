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

import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.writer.TextWriter;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 22 phase 1: the filter's mechanics, driven with Stroom's own DS3 as the upstream.
 */
class FilterRunTest {

    private static final Path LEGACY = Paths.get(
            "..", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures", "legacy");

    /**
     * Over the records image: each record's {@code who} becomes a {@code <user>}. The eater
     * between records must never be able to take {@code <record>} itself: under the streamed run a
     * record at a window's edge cannot match yet, and an eater that could take its open tag would
     * dissolve it (design 22 phase 1's finding).
     */
    private static final String USERS = """
            {"name": "users", "version": 5,
             "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "utf-8"},
             "templates": [
              {"id": "00000000-0000-0000-0000-000000000001", "name": "root", "match": "source",
               "body": [{"element": {"name": "users", "body": [
                 {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "records"}}]}}]},
              {"id": "00000000-0000-0000-0000-000000000002", "name": "record", "mode": "records",
               "match": {"regex": {"pattern": "\\\\s*<record>.*?</record>", "flags": {"dot_all": true}}},
               "body": [{"element": {"name": "user", "body": [
                 {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "fields"}}]}}]},
              {"id": "00000000-0000-0000-0000-000000000003", "name": "who", "mode": "fields",
               "match": {"regex": {"pattern": "\\\\s*<data name=\\"who\\" value=\\"([^\\"]*)\\"/>"}},
               "body": [{"value-of": {"parts": [{"capture": {"group": 1}}]}}]},
              {"id": "00000000-0000-0000-0000-000000000004", "name": "rest", "mode": "fields", "consume": true,
               "match": {"regex": {"pattern": "[^<]*<[^>]*>"}}, "body": []},
              {"id": "00000000-0000-0000-0000-000000000005", "name": "between", "mode": "records", "consume": true,
               "match": {"regex": {"pattern": "[^<]*<(?!record>)[^>]*>"}}, "body": []}
             ]}
            """;

    private static ShapeshifterReader reader(final String json) {
        return (ShapeshifterReader) new ShapeshifterParserFactory(ProjectReader.read(json)).getParser();
    }

    private static ErrorHandler errors() {
        return Ds3Oracle.errorHandler("ShapeshifterFilter", new LoggingErrorReceiver());
    }

    /** Stroom's DS3 over a legacy fixture, its events delivered to the handler given. */
    private static void ds3(final String stem, final ContentHandler handler) throws Exception {
        final String config = Files.readString(LEGACY.resolve(stem + ".ds3.xml"));
        final byte[] input = Files.readAllBytes(LEGACY.resolve(stem + ".in"));
        final XMLReader ds3 = Ds3Oracle.parser(config);
        ds3.setContentHandler(handler);
        ds3.setErrorHandler(Ds3Oracle.errorHandler("DS3Parser", new LoggingErrorReceiver()));
        ds3.parse(new InputSource(new InputStreamReader(new ByteArrayInputStream(input), StandardCharsets.UTF_8)));
    }

    @Test
    void theImageOfAnEventStreamIsTheFileTheUpstreamWouldHaveWritten() throws Exception {
        // I1's contract as a file: DS3's events through the image are Stroom's golden, byte for byte.
        final ByteArrayOutputStream image = new ByteArrayOutputStream();
        ds3("001_csv_with_header", new EventImage(image));
        assertThat(image.toString(StandardCharsets.UTF_8))
                .isEqualTo(Files.readString(LEGACY.resolve("001_csv_with_header.out.xml")));
    }

    @Test
    void configurationGluedAfterDs3ProducesWhatItProducesFromTheFile() throws Exception {
        // Through the filter: DS3 events -> pipe -> engine -> forwarded events.
        final EventRecorder viaFilter = new EventRecorder();
        final FilterRun run = new FilterRun(reader(USERS), ShapeshifterFilter.PIPE_CAPACITY, viaFilter, errors());
        ds3("001_csv_with_header", run.input());
        run.finish();

        // From the file the DS3 element would have written: the parser element's path.
        final EventRecorder viaFile = new EventRecorder();
        final ShapeshifterReader fromFile = reader(USERS);
        fromFile.setContentHandler(viaFile);
        fromFile.setErrorHandler(errors());
        fromFile.parse(new InputSource(Files.newInputStream(LEGACY.resolve("001_csv_with_header.out.xml"))));

        assertThat(viaFilter.events()).containsExactlyElementsOf(viaFile.events());
        assertThat(viaFilter.events()).contains("characters \"jim\"", "characters \"fred\"");
        assertThat(viaFilter.events().stream().filter(e -> e.startsWith("startElement {}user ")).count()).isEqualTo(6);
    }

    @Test
    void theLocatorPointsIntoTheImage() throws Exception {
        final ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        ds3("001_csv_with_header", new EventImage(imageBytes));
        final List<String> imageLines = imageBytes.toString(StandardCharsets.UTF_8).lines().toList();
        final List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < imageLines.size(); i++) {
            if (imageLines.get(i).strip().equals("<record>")) {
                expected.add(i + 1);
            }
        }

        final List<Integer> reported = new ArrayList<>();
        final FilterRun run = new FilterRun(reader(USERS), ShapeshifterFilter.PIPE_CAPACITY, new DefaultHandler() {
            private Locator locator;

            @Override
            public void setDocumentLocator(final Locator locator) {
                this.locator = locator;
            }

            @Override
            public void startElement(final String uri, final String local, final String qName, final Attributes atts) {
                if (local.equals("user")) {
                    reported.add(locator.getLineNumber());
                }
            }
        }, errors());
        ds3("001_csv_with_header", run.input());
        run.finish();

        // <user>'s start tag is deferred until its first child emits, so it belongs to the
        // who-field's span (design 21 phase 4's rule), and that match's leading \\s* begins on the
        // newline that ends the line before <data name="who"> — the record's line plus one.
        assertThat(expected).hasSize(6);
        assertThat(reported).containsExactlyElementsOf(expected.stream().map(line -> line + 1).toList());
    }

    @Test
    void pipeFarSmallerThanTheStreamCompletesUnderBackPressure() throws Exception {
        final StringBuilder csv = new StringBuilder("dt,who,where,what\n");
        for (int i = 0; i < 5000; i++) {
            csv.append("2020-06-17T08:00:00.000Z,user").append(i).append(",office,logon\n");
        }
        final String config = Files.readString(LEGACY.resolve("001_csv_with_header.ds3.xml"));
        final XMLReader ds3 = Ds3Oracle.parser(config);
        final EventRecorder downstream = new EventRecorder();
        final FilterRun run = new FilterRun(reader(USERS), 4096, downstream, errors());
        ds3.setContentHandler(run.input());
        ds3.setErrorHandler(Ds3Oracle.errorHandler("DS3Parser", new LoggingErrorReceiver()));
        ds3.parse(new InputSource(new InputStreamReader(
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)));
        // Both ends stream: a structured configuration's events were delivered while the input
        // was still arriving, not held until endDocument (phase 2).
        final int deliveredBeforeTheEnd = run.delivered();
        run.finish();

        assertThat(deliveredBeforeTheEnd).isGreaterThan(1000);
        assertThat(downstream.events().stream().filter(e -> e.startsWith("startElement {}user ")).count())
                .isEqualTo(5000);
        assertThat(downstream.events()).contains("characters \"user4999\"");
    }

    /**
     * Design 24 phase 2 audit: the filter's text path into a real {@code TextWriter}, the
     * consumer it is for — what reaches the destination is the text the configuration wrote,
     * and it is the text the event recorder saw, character for character.
     */
    @Test
    void textConfigurationThroughTheFilterReachesATextWriterAsWritten() throws Exception {
        final EventRecorder recorded = new EventRecorder();
        final FilterRun plain = new FilterRun(reader(USERS_AS_TEXT), 4096, recorded, errors());
        ds3("001_csv_with_header", plain.input());
        plain.finish();
        final String expected = recorded.events().stream()
                .filter(e -> e.startsWith("characters \""))
                .map(e -> e.substring("characters \"".length(), e.length() - 1))
                .collect(java.util.stream.Collectors.joining());

        final ByteArrayOutputStream destination = new ByteArrayOutputStream();
        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        final TextWriter writer = new TextWriter(new ErrorReceiverProxy(receiver));
        writer.setElementId(new ElementId("TextWriter"));
        writer.addTarget(new CapturingDestination(destination));
        final FilterRun run = new FilterRun(reader(USERS_AS_TEXT), 4096, writer,
                Ds3Oracle.errorHandler("ShapeshifterFilter", receiver));
        writer.startProcessing();
        try {
            ds3("001_csv_with_header", run.input());
            run.finish();
        } finally {
            writer.endProcessing();
        }

        assertThat(receiver.isAllOk()).isTrue();
        assertThat(destination.toString(StandardCharsets.UTF_8)).isEqualTo(expected)
                .startsWith("<users><user>").endsWith("</user></users>");
    }

    /** The same transformation as text, delivered as characters as it is written. */
    private static final String USERS_AS_TEXT = USERS
            .replace("{\"element\": {\"name\": \"users\", \"body\": [", "{\"text\": \"<users>\"}, ")
            .replace("\"mode\": \"records\"}}]}}]},", "\"mode\": \"records\"}}, {\"text\": \"</users>\"}]},")
            .replace("{\"element\": {\"name\": \"user\", \"body\": [", "{\"text\": \"<user>\"}, ")
            .replace("\"mode\": \"fields\"}}]}}]},", "\"mode\": \"fields\"}}, {\"text\": \"</user>\"}]},");

    /**
     * Design 24 phase 2: the text variant streams too — characters delivered while the input is
     * still arriving — and carries the same values, in the same order, as the structured one.
     */
    @Test
    void textConfigurationStreamsCharactersAndCarriesTheSameValuesAsTheStructuredOne() throws Exception {
        assertThat(reader(USERS).compiled().structured()).isTrue();
        assertThat(reader(USERS_AS_TEXT).compiled().structured()).isFalse();

        final EventRecorder viaText = new EventRecorder();
        final FilterRun text = new FilterRun(reader(USERS_AS_TEXT), 4096, viaText, errors());
        ds3("001_csv_with_header", text.input());
        final int deliveredBeforeTheEnd = text.delivered();
        text.finish();

        final EventRecorder viaStructure = new EventRecorder();
        final FilterRun structured = new FilterRun(reader(USERS), 4096, viaStructure, errors());
        ds3("001_csv_with_header", structured.input());
        structured.finish();

        assertThat(deliveredBeforeTheEnd).isGreaterThan(0);
        assertThat(viaText.events()).noneMatch(e -> e.startsWith("startElement"));
        final String joined = viaText.events().stream()
                .filter(e -> e.startsWith("characters \""))
                .map(e -> e.substring("characters \"".length(), e.length() - 1))
                .collect(java.util.stream.Collectors.joining());
        final List<String> textValues = new ArrayList<>();
        final java.util.regex.Matcher users = java.util.regex.Pattern.compile("<user>([^<]*)</user>").matcher(joined);
        while (users.find()) {
            textValues.add(users.group(1));
        }
        final List<String> structuredValues = viaStructure.events().stream()
                .filter(e -> e.startsWith("characters \""))
                .map(e -> e.substring("characters \"".length(), e.length() - 1))
                .toList();
        assertThat(joined).startsWith("<users>").endsWith("</users>");
        assertThat(textValues).isNotEmpty().isEqualTo(structuredValues);
    }

    @Test
    void charactersSplitAcrossEventsKeepTheirWhitespaceInTheImage() throws Exception {
        final ByteArrayOutputStream image = new ByteArrayOutputStream();
        final EventImage handler = new EventImage(image);
        handler.startDocument();
        handler.startElement("", "d", "d", new org.xml.sax.helpers.AttributesImpl());
        handler.characters("a".toCharArray(), 0, 1);
        handler.characters(" ".toCharArray(), 0, 1);
        handler.characters("b".toCharArray(), 0, 1);
        handler.endElement("", "d", "d");
        assertThat(image.toString(StandardCharsets.UTF_8)).endsWith("<d>a b</d>\n");
    }

    @Test
    void anAbandonedDocumentReleasesItsWorker() throws Exception {
        final FilterRun run = new FilterRun(reader(USERS), 64, new EventRecorder(), errors());
        run.input().startDocument();
        run.input().startElement("", "records", "records", new org.xml.sax.helpers.AttributesImpl());
        // No endDocument ever comes: the worker is waiting on the pipe for more.
        assertThat(run.workerDone(50)).isFalse();
        run.abandon(new IllegalStateException("the stream ended before the document did"));
        assertThat(run.workerDone(5_000)).isTrue();
    }

    /** A downstream that refuses the first element, as a failing XSLT would. */
    private static final class Refusing extends DefaultHandler {

        @Override
        public void startElement(final String uri, final String local, final String qName, final Attributes atts)
                throws org.xml.sax.SAXException {
            throw new org.xml.sax.SAXException("downstream refused " + qName);
        }
    }

    @Test
    void downstreamThatThrowsMidStreamDoesNotLeaveTheWorkerWaitingOnTheQueue() throws Exception {
        final StringBuilder csv = new StringBuilder("dt,who,where,what\n");
        for (int i = 0; i < 3000; i++) {
            csv.append("2020-06-17T08:00:00.000Z,user").append(i).append(",office,logon\n");
        }
        final FilterRun run = new FilterRun(reader(USERS), 4096, new Refusing(), errors());
        final XMLReader ds3 = Ds3Oracle.parser(Files.readString(LEGACY.resolve("001_csv_with_header.ds3.xml")));
        ds3.setContentHandler(run.input());
        ds3.setErrorHandler(Ds3Oracle.errorHandler("DS3Parser", new LoggingErrorReceiver()));
        // The refusal reaches the pipeline's thread inside the image's write, as the element sees it.
        assertThatThrownBy(() -> ds3.parse(new InputSource(new InputStreamReader(
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))))
                .hasMessageContaining("downstream refused");
        run.abandon(new IllegalStateException("abandoned after the refusal"));
        assertThat(run.workerDone(5_000)).as("the worker must not wait on a queue nobody drains").isTrue();
    }

    @Test
    void refusalDuringTheFinalDrainIsThrownFromFinishAndTheRunIsAbandoned() throws Exception {
        // endDocument is the one event the worker can only produce after the pipe is closed, so a
        // downstream that refuses it fails inside finish()'s own drain, deterministically.
        final FilterRun run = new FilterRun(reader(USERS), 4096, new DefaultHandler() {
            @Override
            public void endDocument() throws org.xml.sax.SAXException {
                throw new org.xml.sax.SAXException("downstream refused the end");
            }
        }, errors());
        ds3("001_csv_with_header", run.input());
        assertThatThrownBy(run::finish).hasMessageContaining("downstream refused the end");
        assertThat(run.workerDone(5_000)).as("finish must not leave a worker behind").isTrue();
    }

    @Test
    void workerThatDiesUnblocksTheWriterAndSurfacesAtTheJoin() throws Exception {
        final ShapeshifterReader failing = new ShapeshifterReader(reader(USERS_AS_TEXT).compiled()) {
            @Override
            List<stroom.shapeshifter.engine.Message> runInto(final InputLocations.LineIndex lines,
                                                             final InputLocations locations,
                                                             final ContentHandler handler) {
                throw new IllegalStateException("the engine fell over");
            }
        };
        final FilterRun run = new FilterRun(failing, 64, new EventRecorder(), errors());
        // More than the pipe holds: without the failure propagating, this write would block for ever.
        assertThatThrownBy(() -> ds3("001_csv_with_header", run.input()))
                .hasMessageContaining("the engine fell over");
        assertThatThrownBy(run::finish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the engine fell over");
    }

    /**
     * Design 23 phase 2: the truncation ruling through the pipe. A record larger than
     * {@code buffer_size} ends the engine's run with a FATAL — on the worker, with most of the
     * image still to be written by the pipeline's thread. That thread must not be left writing
     * into a pipe nobody reads: the rest of the document is discarded, endDocument is reached,
     * and the FATAL is what the pipeline hears.
     */
    @Test
    void recordLargerThanTheWindowThroughThePipeIsFatalAndDoesNotHangTheWriter() throws Exception {
        final StringBuilder csv = new StringBuilder("dt,who,where,what\n");
        csv.append("2020-06-17T08:00:00.000Z,").append("x".repeat(2000)).append(",office,logon\n");
        for (int i = 0; i < 2000; i++) {
            csv.append("2020-06-17T08:00:00.000Z,user").append(i).append(",office,logon\n");
        }
        final String config = Files.readString(LEGACY.resolve("001_csv_with_header.ds3.xml"));
        final XMLReader ds3 = Ds3Oracle.parser(config);
        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        final EventRecorder downstream = new EventRecorder();
        // A record pattern that takes the rest of the window when no </record> is in it: the
        // truncated view fills the window and the ruling applies. (A pattern that simply fails
        // on such a record ends the run the way DS3's does — nothing matched, nothing more said.)
        final String oversize = USERS
                .replace("\"buffer_size\": 20000", "\"buffer_size\": 1024")
                .replace("\\\\s*<record>.*?</record>", "\\\\s*<record>(?:.*?</record>|.*)");
        assertThat(oversize).contains("(?:.*?</record>|.*)");
        final FilterRun run = new FilterRun(reader(oversize), 4096, downstream,
                Ds3Oracle.errorHandler("ShapeshifterFilter", receiver));
        ds3.setContentHandler(run.input());
        ds3.setErrorHandler(Ds3Oracle.errorHandler("DS3Parser", new LoggingErrorReceiver()));
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            ds3.parse(new InputSource(new InputStreamReader(
                    new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8)));
            run.finish();
        });
        assertThat(receiver.getTotal(Severity.FATAL_ERROR)).isEqualTo(1);
        assertThat(receiver.getIndicators(new ElementId("ShapeshifterFilter")).getErrorList().stream()
                .map(StoredError::toString).toList())
                .anySatisfy(m -> assertThat(m).contains("larger than source buffer_size"));
        assertThat(run.workerDone(1000)).isTrue();
    }
}
