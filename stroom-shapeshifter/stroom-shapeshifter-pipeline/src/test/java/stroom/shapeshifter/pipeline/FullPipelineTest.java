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

import stroom.docref.DocRef;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.factory.ModulePipelines;
import stroom.pipeline.factory.Pipeline;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.util.io.FileUtil;
import stroom.util.shared.Severity;
import stroom.util.xml.SAXParserFactoryFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full pipelines, the way Stroom's own {@code TestFileAppender} runs them — the same input, the
 * same goldens, the same chain downstream: record counting, splitting, schema validation, the
 * record output filter, the writer, the appender — with a Shapeshifter configuration where the
 * DS3 configuration and the stylesheet were. Three shapes: text out through a {@code TextWriter};
 * XML out through an {@code XMLWriter}; and Shapeshifter as the filter after Stroom's own DS3,
 * where the stylesheet sat. In each the configuration emits {@code Eventy} for the 59 requests of
 * {@code /bad}, as the stylesheets do, and it is the schema filter that marks them and the record
 * output filter that drops them.
 */
class FullPipelineTest {

    private static final Path FIXTURES = Paths.get("src", "test", "resources", "TestFileAppender");
    private static final int RECORDS = 200;
    private static final int BAD = 59;

    @TempDir
    Path temp;

    @Test
    void textPipelineWritesStroomsTextGolden() throws Exception {
        final Path written = run("Text", "TestFileAppender_Text.shapeshifter.json",
                "TestFileAppender_Text_Pipeline.json", "shapeshifterParser", fixture("TestFileAppender.in"));
        // Stroom's text stylesheet ends each Event with a text node of one newline, which the
        // schema filter accepts and the TextWriter writes; the configuration writes the same
        // text node and the event sink delivers it (E38).
        assertThat(Files.readString(written)).isEqualTo(
                new String(fixture("TestFileAppender_Text.out"), StandardCharsets.UTF_8));
    }

    @Test
    void xmlPipelineWritesStroomsXmlGolden() throws Exception {
        final Path written = run("XML", "TestFileAppender_XML.shapeshifter.json",
                "TestFileAppender_XML_Pipeline.json", "shapeshifterParser", fixture("TestFileAppender.in"));
        // The XMLWriter's serialisation is not Saxon's, so the comparison is as documents: every
        // element, attribute and text in the golden is in the output, in order, and nothing else.
        assertThat(events(Files.readAllBytes(written))).containsExactlyElementsOf(
                events(fixture("TestFileAppender_XML.out")));
    }

    /**
     * Shapeshifter where the stylesheet was: after Stroom's own DS3 has parsed the log into its
     * records document, the filter reads that document's image and builds the events, and the
     * schema filter and record output filter downstream drop what the stylesheet made invalid.
     */
    @Test
    void filterWhereTheStylesheetWasWritesStroomsXmlGolden() throws Exception {
        final Path written = run("Filter", "TestFileAppender_Filter.shapeshifter.json",
                "TestFileAppender_Filter_Pipeline.json", "shapeshifterFilter", recordsDocument());
        assertThat(events(Files.readAllBytes(written))).containsExactlyElementsOf(
                events(fixture("TestFileAppender_XML.out")));
    }

    /**
     * Design 26 phase 2: a configuration calling Stroom's functions, through a real pipeline into
     * a {@code TextWriter} — the registry the pool compiles against is the module's, and the
     * element gives each document's run its services.
     */
    @Test
    void configurationCallingStroomsFunctionsRunsThroughAPipeline() throws Exception {
        final String configuration = """
                {"name": "calls", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                   "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "lines"}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "lines",
                   "match": {"regex": {"pattern": "([^ ]+) [^ ]+ ([^ ]+) \\\\[([^\\\\]]+)\\\\] [^\\\\n]*\\\\n"}},
                   "body": [
                     {"call": {"function": "format-date", "select": [
                        {"parts": [{"capture": {"group": 3}}]},
                        {"parts": [{"text": "dd/MMM/yyyy:HH:mm:ss Z"}]}]}},
                     {"text": " "},
                     {"call": {"function": "numeric-ip", "select": [{"parts": [{"capture": {"group": 1}}]}]}},
                     {"text": " "},
                     {"call": {"function": "hash", "select": [
                        {"parts": [{"capture": {"group": 2}}]}, {"parts": [{"text": "MD5"}]}]}},
                     {"text": "\\n"}]}
                 ]}
                """;
        final String pipelineJson = """
                {"elements": {"add": [
                   {"id": "shapeshifterParser", "type": "ShapeshifterParser"},
                   {"id": "textWriter", "type": "TextWriter"},
                   {"id": "fileAppender", "type": "FileAppender"}]},
                 "properties": {"add": [
                   {"element": "fileAppender", "name": "outputPaths",
                    "value": {"string": "${stroom.temp}/TestFileAppender_Calls.tmp"}}]},
                 "links": {"add": [
                   {"from": "shapeshifterParser", "to": "textWriter"},
                   {"from": "textWriter", "to": "fileAppender"}]}}
                """;
        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        final ModulePipelines pipelines = new ModulePipelines(new ErrorReceiverProxy(receiver), temp);
        final DocRef doc = pipelines.shapeshifterDoc("calls", configuration);
        final PipelineData data = new PipelineDataBuilder(ModulePipelines.pipelineData(pipelineJson))
                .addProperty(PipelineDataUtil.createProperty("shapeshifterParser", "shapeshifter", doc))
                .build();
        final Pipeline pipeline = pipelines.create(data);
        pipeline.startProcessing();
        pipeline.process(new ByteArrayInputStream(fixture("TestFileAppender.in")), StandardCharsets.UTF_8.name());
        pipeline.endProcessing();
        assertThat(receiver.isAllOk()).as(receiver.getMessage()).isTrue();

        final List<String> lines = Files.readAllLines(temp.resolve("TestFileAppender_Calls.tmp"));
        assertThat(lines).hasSize(200);
        // 123.123.123.123 - testuser [09/Apr/2013:01:00:50 +0100] ... : the date in Stroom's normal
        // form, the address as a number, the user's MD5.
        assertThat(lines.getFirst()).isEqualTo("2013-04-09T00:00:50.000Z 2071690107 5d9c68c6c50ed3d02a2fcf54f63993b6");
    }

    /** Build the pipeline, point its Shapeshifter element at the configuration, run the input through it. */
    private Path run(final String variant, final String configuration, final String pipelineJson,
                     final String element, final byte[] input) throws IOException {
        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        final ModulePipelines pipelines = new ModulePipelines(new ErrorReceiverProxy(receiver), temp);
        pipelines.xmlSchema("event-logging v3.0.0", "event-logging:3", "file://event-logging-v3.0.0.xsd", "EVENTS",
                Files.readString(FIXTURES.resolve("event-logging-v3.0.0.xsd")));
        final DocRef doc = pipelines.shapeshifterDoc(variant, Files.readString(FIXTURES.resolve(configuration)));
        final PipelineData data = new PipelineDataBuilder(
                ModulePipelines.pipelineData(Files.readString(FIXTURES.resolve(pipelineJson))))
                .addProperty(PipelineDataUtil.createProperty(element, "shapeshifter", doc))
                .build();
        final Path output = temp.resolve("TestFileAppender_" + variant + ".tmp");
        FileUtil.deleteFile(output);

        final Pipeline pipeline = pipelines.create(data);
        pipeline.startProcessing();
        pipeline.process(new ByteArrayInputStream(input), StandardCharsets.UTF_8.name());
        pipeline.endProcessing();

        // Stroom's own validateProcess: every record read, the /bad ones marked by the schema
        // filter and dropped by the record output filter, nothing else said.
        assertThat(pipelines.recordCount().getRead()).isEqualTo(RECORDS);
        assertThat(pipelines.recordCount().getWritten()).isEqualTo(RECORDS - BAD);
        assertThat(receiver.getRecords(Severity.WARNING)).isZero();
        assertThat(receiver.getRecords(Severity.ERROR)).isEqualTo(BAD);
        assertThat(receiver.getRecords(Severity.FATAL_ERROR)).isZero();
        assertThat(output).exists();
        return output;
    }

    /** The log as Stroom's DS3 parses it with the fixture's own configuration — the document the stylesheet saw. */
    private static byte[] recordsDocument() throws Exception {
        final XMLReader ds3 = Ds3Oracle.parser(Files.readString(FIXTURES.resolve("TestFileAppender.ds3.xml")));
        final ByteArrayOutputStream image = new ByteArrayOutputStream();
        ds3.setContentHandler(new EventImage(image));
        ds3.setErrorHandler(Ds3Oracle.errorHandler("DS3Parser", new LoggingErrorReceiver()));
        ds3.parse(new InputSource(new InputStreamReader(
                new ByteArrayInputStream(fixture("TestFileAppender.in")), StandardCharsets.UTF_8)));
        return image.toByteArray();
    }

    private static byte[] fixture(final String name) throws IOException {
        return Files.readAllBytes(FIXTURES.resolve(name));
    }

    /** A document as events, whitespace between elements and namespace declarations aside. */
    private static List<String> events(final byte[] xml) throws Exception {
        final XMLReader reader = SAXParserFactoryFactory.newInstance().newSAXParser().getXMLReader();
        final EventRecorder recorder = new EventRecorder();
        reader.setContentHandler(recorder);
        reader.parse(new InputSource(new ByteArrayInputStream(xml)));
        return recorder.events().stream().filter(e -> !e.startsWith("startPrefixMapping")).toList();
    }
}
