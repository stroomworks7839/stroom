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
import stroom.util.xml.SAXParserFactoryFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full pipelines, the way Stroom's own {@code TestFileAppender} runs them — the same input, the
 * same goldens, the same writers and appender — with a Shapeshifter configuration in place of the
 * DS3 configuration and the stylesheet together. Three shapes: text out through a
 * {@code TextWriter}; XML out through an {@code XMLWriter}; and Shapeshifter in the middle, after
 * an {@code XMLParser}, turning the events back into the text.
 */
class FullPipelineTest {

    private static final Path FIXTURES = Paths.get("src", "test", "resources", "TestFileAppender");

    @TempDir
    Path temp;

    @Test
    void textPipelineWritesStroomsTextGolden() throws Exception {
        final Path written = run("Text", "TestFileAppender_Text.shapeshifter.json",
                "TestFileAppender_Text_Pipeline.json", "shapeshifterParser", fixture("TestFileAppender.in"));
        assertThat(Files.readAllBytes(written)).isEqualTo(fixture("TestFileAppender_Text.out"));
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

    @Test
    void filterAfterAnXmlParserTurnsTheEventsBackIntoTheTextGolden() throws Exception {
        final Path written = run("Filter", "TestFileAppender_Filter.shapeshifter.json",
                "TestFileAppender_Filter_Pipeline.json", "shapeshifterFilter", fixture("TestFileAppender_XML.out"));
        assertThat(Files.readAllBytes(written)).isEqualTo(fixture("TestFileAppender_Text.out"));
    }

    /** Build the pipeline, point its Shapeshifter element at the configuration, run the input through it. */
    private Path run(final String variant, final String configuration, final String pipelineJson,
                     final String element, final byte[] input) throws IOException {
        final LoggingErrorReceiver receiver = new LoggingErrorReceiver();
        final ModulePipelines pipelines = new ModulePipelines(new ErrorReceiverProxy(receiver), temp);
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

        assertThat(receiver.isAllOk()).as(receiver.getMessage()).isTrue();
        assertThat(output).exists();
        return output;
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
