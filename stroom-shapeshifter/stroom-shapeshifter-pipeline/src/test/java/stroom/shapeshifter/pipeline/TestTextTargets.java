/*
 * Copyright 2026 Crown Copyright
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
import stroom.pipeline.errorhandler.ProcessException;
import stroom.pipeline.factory.ModulePipelines;
import stroom.pipeline.factory.Pipeline;
import stroom.pipeline.filter.NullXMLFilter;
import stroom.pipeline.filter.SAXEventRecorder;
import stroom.pipeline.filter.SAXRecordDetector;
import stroom.pipeline.filter.SplitFilter;
import stroom.pipeline.filter.XMLFilter;
import stroom.pipeline.filter.XMLFilterFork;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.writer.TextWriter;
import stroom.pipeline.writer.XMLWriter;
import stroom.shapeshifter.engine.ProjectReader;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Design 24, ruled 2026-11-24: a text configuration feeds writers and nothing else.
class TestTextTargets {

    private static final String SOURCE = """
            {"name": "c", "version": 5,
             "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "utf-8"},
             "templates": [
              {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
               "body": [%s]}]}
            """;
    private static final String TEXT = SOURCE.formatted("{\"text\": \"hello\"}");
    private static final String STRUCTURE = SOURCE.formatted(
            "{\"element\": {\"name\": \"a\", \"body\": [{\"text\": \"hello\"}]}}");
    private static final ElementId PARSER = new ElementId("parser");

    @TempDir
    Path temp;

    @Test
    void textIntoAWriterIsAllowed() {
        assertThatCode(() -> check(TEXT, writer("textWriter"))).doesNotThrowAnyException();
        assertThatCode(() -> check(TEXT, named(new XMLWriter(), "xmlWriter"))).doesNotThrowAnyException();
    }

    @Test
    void textIntoNothingIsAllowed() {
        assertThatCode(() -> check(TEXT, NullXMLFilter.INSTANCE)).doesNotThrowAnyException();
        assertThatCode(() -> check(TEXT, null)).doesNotThrowAnyException();
    }

    @Test
    void textIntoAnythingElseIsRefusedByName() {
        assertThatThrownBy(() -> check(TEXT, named(new SplitFilter(), "splitFilter")))
                .isInstanceOf(ProcessException.class)
                .hasMessageContaining("'parser' writes text")
                .hasMessageContaining("'splitFilter' is not a writer");
    }

    @Test
    void everyTargetIsJudged() {
        final XMLFilter first = named(new SplitFilter(), "first");
        final XMLFilter second = named(new SplitFilter(), "second");
        assertThatThrownBy(() -> check(TEXT,
                new XMLFilterFork(new XMLFilter[]{writer("textWriter"), first, second})))
                .hasMessageContaining("'first' and 'second' are not writers")
                .hasMessageNotContaining("'textWriter'");
        assertThatCode(() -> check(TEXT, new XMLFilterFork(new XMLFilter[]{writer("a"), writer("b")})))
                .doesNotThrowAnyException();
    }

    @Test
    void aWriterFurtherDownIsNotEnough() {
        final SplitFilter split = named(new SplitFilter(), "splitFilter");
        split.setTarget(writer("textWriter"));
        assertThatThrownBy(() -> check(TEXT, split)).hasMessageContaining("'splitFilter'");
    }

    @Test
    void nothingIntoAnythingIsAllowed() {
        // A new document, which writes nothing yet, and can be stepped and run wherever it is
        assertThatCode(() -> check(SOURCE.formatted(""), named(new SplitFilter(), "splitFilter")))
                .doesNotThrowAnyException();
    }

    @Test
    void structureIntoAnythingIsAllowed() {
        assertThatCode(() -> check(STRUCTURE, named(new SplitFilter(), "splitFilter"))).doesNotThrowAnyException();
    }

    @Test
    void whatSteppingPutsBetweenIsLookedThrough() {
        // As PipelineFactory has it after a parser: a split with no id, then a recorder
        assertThatCode(() -> check(TEXT, stepping(writer("textWriter")))).doesNotThrowAnyException();
        assertThatThrownBy(() -> check(TEXT, stepping(named(new SplitFilter(), "splitFilter"))))
                .hasMessageContaining("'splitFilter'");
        final SAXRecordDetector detector = new SAXRecordDetector();
        detector.setTarget(writer("textWriter"));
        assertThatCode(() -> check(TEXT, detector)).doesNotThrowAnyException();
    }

    @Test
    void aParserRefusesBeforeItReadsAnything() throws Exception {
        assertThat(runRefused("""
                {"id": "shapeshifterParser", "type": "ShapeshifterParser"},""", "shapeshifterParser",
                """
                {"from": "shapeshifterParser", "to": "splitFilter"},"""))
                .contains("'shapeshifterParser' writes text")
                .contains("'splitFilter' is not a writer");
    }

    @Test
    void aFilterRefusesBeforeItReadsAnything() throws Exception {
        assertThat(runRefused("""
                {"id": "xmlParser", "type": "XMLParser"},
                {"id": "shapeshifterFilter", "type": "ShapeshifterFilter"},""", "shapeshifterFilter",
                """
                {"from": "xmlParser", "to": "shapeshifterFilter"},
                {"from": "shapeshifterFilter", "to": "splitFilter"},"""))
                .contains("'shapeshifterFilter' writes text")
                .contains("'splitFilter' is not a writer");
    }

    // A pipeline with a split between the element and the writer: what it says, and that it
    // wrote nothing
    private String runRefused(final String elements, final String element, final String links) throws Exception {
        final String json = """
                {"elements": {"add": [%s
                   {"id": "splitFilter", "type": "SplitFilter"},
                   {"id": "textWriter", "type": "TextWriter"},
                   {"id": "fileAppender", "type": "FileAppender"}]},
                 "properties": {"add": [
                   {"element": "fileAppender", "name": "outputPaths",
                    "value": {"string": "${stroom.temp}/refused.tmp"}}]},
                 "links": {"add": [%s
                   {"from": "splitFilter", "to": "textWriter"},
                   {"from": "textWriter", "to": "fileAppender"}]}}
                """.formatted(elements, links);
        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        final ModulePipelines pipelines = new ModulePipelines(new ErrorReceiverProxy(receiver), temp);
        final DocRef doc = pipelines.shapeshifterDoc("text", TEXT);
        final PipelineData data = new PipelineDataBuilder(ModulePipelines.pipelineData(json))
                .addProperty(PipelineDataUtil.createProperty(element, "shapeshifter", doc))
                .build();
        final Pipeline pipeline = pipelines.create(data);
        try {
            pipeline.startProcessing();
            pipeline.process(new ByteArrayInputStream("<a/>".getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8.name());
            pipeline.endProcessing();
        } catch (final RuntimeException e) {
            // Refused as an exception, where the element throws what it has reported
        }
        assertThat(receiver.getRecords(Severity.FATAL_ERROR)).isPositive();
        assertThat(Files.exists(temp.resolve("refused.tmp"))
                   && Files.size(temp.resolve("refused.tmp")) > 0).isFalse();
        return receiver.getIndicators(new ElementId(element)).getErrorList().stream()
                .map(StoredError::toString)
                .collect(Collectors.joining("\n"));
    }

    private static void check(final String project, final XMLFilter target) {
        TextTargets.check(PARSER, new ShapeshifterParserFactory(ProjectReader.read(project)), target);
    }

    private static SplitFilter stepping(final XMLFilter next) {
        final SAXEventRecorder recorder = Mockito.mock(SAXEventRecorder.class);
        Mockito.when(recorder.getFilter()).thenReturn(next);
        Mockito.when(recorder.getElementId()).thenReturn(PARSER);
        final SplitFilter split = new SplitFilter();
        split.setTarget(recorder);
        return split;
    }

    private static TextWriter writer(final String id) {
        return named(new TextWriter(new ErrorReceiverProxy(new LoggingErrorReceiver())), id);
    }

    private static <T extends XMLFilter> T named(final T filter, final String id) {
        filter.setElementId(new ElementId(id));
        return filter;
    }
}
