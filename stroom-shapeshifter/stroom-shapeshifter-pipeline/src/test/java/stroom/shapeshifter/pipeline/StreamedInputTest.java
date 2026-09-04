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
import stroom.shapeshifter.engine.SaxEventSink;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.ds3.Ds3Migration;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 23 phase 1: the parser element streams its input through the engine's window, DS3's
 * way, holding neither the input nor — for a structured configuration — the output; and a record
 * larger than the window is fatal.
 */
class StreamedInputTest {

    private static final Path LEGACY = Paths.get(
            "..", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures", "legacy");

    /** A window this small forces thousands of refills across record boundaries. */
    private static final int SMALL_WINDOW = 4096;

    private static String withWindow(final String projectJson, final int window) {
        return projectJson.replaceFirst("\"buffer_size\" : \\d+", "\"buffer_size\" : " + window);
    }

    private static String migrated001(final int window) throws Exception {
        final String json = ProjectReader.writePretty(
                Ds3Migration.importXml(Files.readString(LEGACY.resolve("001_csv_with_header.ds3.xml"))));
        return withWindow(json, window);
    }

    private static byte[] manyRecords(final int count) {
        final StringBuilder csv = new StringBuilder("dt,who,where,what\n");
        for (int i = 0; i < count; i++) {
            csv.append("2020-06-17T08:00:00.000Z,user").append(i).append(",office,logon\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The parser element's reader over an input source, its events recorded. */
    private static EventRecorder throughReader(final String projectJson, final InputSource source,
                                               final LoggingErrorReceiver receiver) throws Exception {
        final EventRecorder recorder = new EventRecorder();
        final XMLReader reader = new ShapeshifterParserFactory(ProjectReader.read(projectJson)).getParser();
        reader.setContentHandler(recorder);
        reader.setErrorHandler(Ds3Oracle.errorHandler("ShapeshifterParser", receiver));
        reader.parse(source);
        return recorder;
    }

    @Test
    void byteStreamIsWindowedNotHeldAndProducesTheWholeBufferEvents() throws Exception {
        final String json = migrated001(SMALL_WINDOW);
        final byte[] input = manyRecords(5000);
        assertThat(input.length).isGreaterThan(SMALL_WINDOW * 50);

        // The oracle: the same configuration over the whole input as one window.
        final CompiledProject compiled = Shapeshifter.compile(ProjectReader.read(json));
        final EventRecorder whole = new EventRecorder();
        Shapeshifter.runWhole(compiled, input, new SaxEventSink(whole));

        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        final EventRecorder streamed = throughReader(json, new InputSource(new ByteArrayInputStream(input)), receiver);

        assertThat(receiver.isAllOk()).as(receiver.getMessage()).isTrue();
        assertThat(streamed.events().stream().filter(e -> e.startsWith("startElement {records:2}record ")).count())
                .isEqualTo(5000);
        assertThat(streamed.events()).containsExactlyElementsOf(whole.events());
    }

    @Test
    void readerIsEncodedAsItIsReadAndProducesTheSameEvents() throws Exception {
        final String json = migrated001(SMALL_WINDOW);
        final byte[] input = manyRecords(1000);
        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        final EventRecorder viaBytes = throughReader(json, new InputSource(new ByteArrayInputStream(input)), receiver);
        final EventRecorder viaReader = throughReader(json,
                new InputSource(new StringReader(new String(input, StandardCharsets.UTF_8))), receiver);
        assertThat(receiver.isAllOk()).isTrue();
        assertThat(viaReader.events()).containsExactlyElementsOf(viaBytes.events());
    }

    @Test
    void textConfigurationStreamsItsInputToo() throws Exception {
        // The text path still holds its output for the parse (the output round's concern), but
        // the input goes through the window like everything else.
        final String text = """
                {"name": "text", "version": 5,
                 "source": {"buffer_size": 64, "ignore_errors": true, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "root", "match": "source",
                   "body": [{"text": "<r>"},
                            {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "l"}},
                            {"text": "</r>"}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "l",
                   "match": {"regex": {"pattern": "([a-z0-9]+)\\n"}},
                   "body": [{"text": "<l>"}, {"value-of": {"parts": [{"capture": {"group": 1}}]}}, {"text": "</l>"}]}
                 ]}
                """;
        final StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            lines.append("line").append(i).append("\n");
        }
        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        final EventRecorder events = throughReader(text,
                new InputSource(new ByteArrayInputStream(lines.toString().getBytes(StandardCharsets.UTF_8))), receiver);
        assertThat(receiver.isAllOk()).as(receiver.getMessage()).isTrue();
        assertThat(events.events().stream().filter(e -> e.equals("startElement {}l []")).count()).isEqualTo(2000);
        assertThat(events.events()).contains("characters \"line1999\"");
    }

    @Test
    void recordLargerThanTheWindowIsFatal() throws Exception {
        final String json = migrated001(64);
        final byte[] input = ("dt,who,where,what\n2020-06-17T08:00:00.000Z," + "x".repeat(200) + ",office,logon\n")
                .getBytes(StandardCharsets.UTF_8);
        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        throughReader(json, new InputSource(new ByteArrayInputStream(input)), receiver);
        assertThat(receiver.getTotal(Severity.FATAL_ERROR)).isEqualTo(1);
        final List<String> messages = receiver.getIndicators(new ElementId("ShapeshifterParser")).getErrorList()
                .stream().map(StoredError::toString).toList();
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("larger than source buffer_size"));
    }

    @Test
    void feedAndConfigurationEncodingsThatDisagreeAreReportedOnce() throws Exception {
        final String json = migrated001(SMALL_WINDOW)
                .replaceFirst("\"encoding\" : \"auto\"", "\"encoding\" : \"utf-8\"");
        assertThat(json).contains("\"encoding\" : \"utf-8\"");
        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        final InputSource declaredOtherwise = new InputSource(new ByteArrayInputStream(manyRecords(3)));
        declaredOtherwise.setEncoding("windows-1252");
        throughReader(json, declaredOtherwise, receiver);
        assertThat(receiver.getTotal(Severity.WARNING)).isEqualTo(1);
        assertThat(receiver.getIndicators(new ElementId("ShapeshifterParser")).getErrorList().getFirst().toString())
                .contains("windows-1252").contains("UTF-8").contains("source.encoding");

        final LoggingErrorReceiver agreeing = new LoggingErrorReceiver();
        final InputSource declaredTheSame = new InputSource(new ByteArrayInputStream(manyRecords(3)));
        declaredTheSame.setEncoding("UTF-8");
        throughReader(json, declaredTheSame, agreeing);
        assertThat(agreeing.getTotal(Severity.WARNING)).isZero();
    }

    @Test
    void theElementPassesAByteStreamThroughAndDecodesNothing() {
        final InputStream bytes = new ByteArrayInputStream(new byte[]{1, 2, 3});
        final InputSource raw = new InputSource(bytes);
        raw.setEncoding("windows-1252");
        final InputSource kept = ShapeshifterParser.rawBytes(raw);
        assertThat(kept.getByteStream()).isSameAs(bytes);
        assertThat(kept.getCharacterStream()).isNull();
        assertThat(kept.getEncoding()).isEqualTo("windows-1252");
        assertThat(ShapeshifterParser.rawBytes(new InputSource(new StringReader("chars")))).isNull();
    }
}
