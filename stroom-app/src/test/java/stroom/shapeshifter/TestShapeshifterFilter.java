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

package stroom.shapeshifter;

import stroom.docref.DocRef;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.PipelineTestUtil;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.factory.Pipeline;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.TextConverterDoc.TextConverterType;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.textconverter.TextConverterStore;
import stroom.shapeshifter.pipeline.ShapeshifterStore;
import stroom.task.api.SimpleTaskContext;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.util.io.FileUtil;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.ElementId;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 22 phase 1's exit, in a real pipeline: Stroom's DS3 parser feeds a Shapeshifter filter,
 * which transforms the records it is given and hands XML on.
 */
class TestShapeshifterFilter extends AbstractProcessIntegrationTest {

    private static final Path LEGACY = Paths.get(
            "..", "stroom-shapeshifter", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures", "legacy")
            .toAbsolutePath().normalize();

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

    private static final String PIPELINE = """
            {
              "elements" : { "add" : [
                { "id" : "dsParser", "type" : "DSParser" },
                { "id" : "shapeshifterFilter", "type" : "ShapeshifterFilter" },
                { "id" : "xmlWriter", "type" : "XMLWriter" },
                { "id" : "fileAppender", "type" : "FileAppender" } ] },
              "properties" : { "add" : [
                { "element" : "fileAppender", "name" : "outputPaths",
                  "value" : { "string" : "${stroom.temp}/TestShapeshifterFilter.xml" } } ] },
              "links" : { "add" : [
                { "from" : "dsParser", "to" : "shapeshifterFilter" },
                { "from" : "shapeshifterFilter", "to" : "xmlWriter" },
                { "from" : "xmlWriter", "to" : "fileAppender" } ] }
            }
            """;

    @Inject
    private Provider<PipelineFactory> pipelineFactoryProvider;
    @Inject
    private Provider<ErrorReceiverProxy> errorReceiverProvider;
    @Inject
    private TextConverterStore textConverterStore;
    @Inject
    private ShapeshifterStore shapeshifterStore;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private PipelineDataCache pipelineDataCache;
    @Inject
    private PipelineScopeRunnable pipelineScopeRunnable;

    @Test
    void ds3ThenShapeshifterThenXmlWriter() throws IOException {
        final DocRef textConverterRef = textConverterStore.createDocument("001 as DS3");
        final TextConverterDoc textConverter = textConverterStore.readDocument(textConverterRef).copy()
                .converterType(TextConverterType.DATA_SPLITTER)
                .data(Files.readString(LEGACY.resolve("001_csv_with_header.ds3.xml")))
                .build();
        textConverterStore.writeDocument(textConverter);

        final DocRef shapeshifterRef = shapeshifterStore.createDocument("users");
        shapeshifterStore.writeDocument(shapeshifterStore.readDocument(shapeshifterRef).copy().data(USERS).build());

        final DocRef pipelineRef = PipelineTestUtil.createTestPipeline(pipelineStore, PIPELINE);
        final PipelineDoc pipelineDoc = pipelineStore.readDocument(pipelineRef);
        final PipelineDataBuilder builder = new PipelineDataBuilder(pipelineDoc.getPipelineData());
        builder.addProperty(PipelineDataUtil.createProperty("dsParser", "textConverter", textConverterRef));
        builder.addProperty(PipelineDataUtil.createProperty("shapeshifterFilter", "shapeshifter", shapeshifterRef));
        pipelineStore.writeDocument(pipelineDoc.copy().pipelineData(builder.build()).build());

        final Path outputFile = getCurrentTestDir().resolve("TestShapeshifterFilter.xml");
        FileUtil.deleteFile(outputFile);
        FileUtil.deleteFile(getCurrentTestDir().resolve("TestShapeshifterFilter.xml.lock"));

        pipelineScopeRunnable.scopeRunnable(() -> {
            try (final InputStream input = Files.newInputStream(LEGACY.resolve("001_csv_with_header.in"))) {
                final LoggingErrorReceiver errors = new LoggingErrorReceiver();
                errorReceiverProvider.get().setErrorReceiver(errors);
                final PipelineData pipelineData = pipelineDataCache.get(pipelineStore.readDocument(pipelineRef));
                final Pipeline pipeline = pipelineFactoryProvider.get().create(pipelineData, new SimpleTaskContext());
                try {
                    pipeline.startProcessing();
                    pipeline.process(input, StandardCharsets.UTF_8.name());
                    pipeline.endProcessing();
                } catch (final RuntimeException e) {
                    throw new AssertionError("pipeline failed; receiver says: " + errors.getMessage() + " / "
                                             + errors.getIndicators(new ElementId("shapeshifterFilter")), e);
                }
                assertThat(errors.isAllOk()).as(errors.getMessage()).isTrue();
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });

        final String output = Files.readString(outputFile);
        assertThat(output).contains("<users").contains("<user>jim</user>").contains("<user>fred</user>");
        assertThat(output.split("<user>").length - 1).isEqualTo(6);
    }
}
