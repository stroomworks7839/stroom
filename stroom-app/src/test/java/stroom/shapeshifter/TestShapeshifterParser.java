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
import stroom.importexport.api.ImportExportDocument;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.PipelineTestUtil;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.factory.Pipeline;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.state.RecordCount;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.ds3.Ds3Migration;
import stroom.shapeshifter.pipeline.ShapeshifterSerialiser;
import stroom.shapeshifter.pipeline.ShapeshifterStore;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.task.api.SimpleTaskContext;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.util.io.FileUtil;
import stroom.util.pipeline.scope.PipelineScopeRunnable;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 21 phase 1b: a Shapeshifter document in a real pipeline, through the element, produces
 * what Stroom's own DS3 produced for the same feed — and the document survives export and import
 * byte for byte.
 */
class TestShapeshifterParser extends AbstractProcessIntegrationTest {

    private static final Path LEGACY = Paths.get(
            "..", "stroom-shapeshifter", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures", "legacy")
            .toAbsolutePath().normalize();
    private static final String FIXTURE = "001_csv_with_header";

    private static final String PIPELINE = """
            {
              "elements" : { "add" : [
                { "id" : "shapeshifterParser", "type" : "ShapeshifterParser" },
                { "id" : "readRecordCountFilter", "type" : "RecordCountFilter" },
                { "id" : "xmlWriter", "type" : "XMLWriter" },
                { "id" : "fileAppender", "type" : "FileAppender" } ] },
              "properties" : { "add" : [
                { "element" : "readRecordCountFilter", "name" : "countRead", "value" : { "boolean" : true } },
                { "element" : "fileAppender", "name" : "outputPaths",
                  "value" : { "string" : "${stroom.temp}/TestShapeshifterParser.xml" } } ] },
              "links" : { "add" : [
                { "from" : "shapeshifterParser", "to" : "readRecordCountFilter" },
                { "from" : "readRecordCountFilter", "to" : "xmlWriter" },
                { "from" : "xmlWriter", "to" : "fileAppender" } ] }
            }
            """;

    @Inject
    private Provider<PipelineFactory> pipelineFactoryProvider;
    @Inject
    private Provider<ErrorReceiverProxy> errorReceiverProvider;
    @Inject
    private Provider<RecordCount> recordCountProvider;
    @Inject
    private ShapeshifterStore shapeshifterStore;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private PipelineDataCache pipelineDataCache;
    @Inject
    private PipelineScopeRunnable pipelineScopeRunnable;
    @Inject
    private ShapeshifterSerialiser serialiser;

    @Test
    void migratedConfigurationInAPipelineProducesStroomsRecords() throws IOException {
        final DocRef docRef = createShapeshifterDoc();
        final DocRef pipelineRef = createPipeline(docRef);
        final Path outputFile = getCurrentTestDir().resolve("TestShapeshifterParser.xml");
        FileUtil.deleteFile(outputFile);
        FileUtil.deleteFile(getCurrentTestDir().resolve("TestShapeshifterParser.xml.lock"));

        pipelineScopeRunnable.scopeRunnable(() -> {
            try (final InputStream input = Files.newInputStream(LEGACY.resolve(FIXTURE + ".in"))) {
                final LoggingErrorReceiver errors = new LoggingErrorReceiver();
                errorReceiverProvider.get().setErrorReceiver(errors);

                final PipelineDoc pipelineDoc = pipelineStore.readDocument(pipelineRef);
                final PipelineData pipelineData = pipelineDataCache.get(pipelineDoc);
                final Pipeline pipeline = pipelineFactoryProvider.get().create(pipelineData, new SimpleTaskContext());

                pipeline.startProcessing();
                pipeline.process(input, StandardCharsets.UTF_8.name());
                pipeline.endProcessing();

                assertThat(errors.isAllOk()).as(errors.getMessage()).isTrue();
                assertThat(recordCountProvider.get().getRead()).isEqualTo(recordsIn(golden()));
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });

        // The XMLWriter's serialisation is not Saxon's — and since design 22 phase 2 a structured
        // configuration reaches it as events with no whitespace between them, so the output may be
        // one line — so the comparison is on content: every data element Stroom's DS3 wrote is in
        // the output, in order, and nothing else is.
        final String output = Files.readString(outputFile);
        assertThat(dataElements(output)).containsExactlyElementsOf(dataElements(golden()));
    }

    @Test
    void theDocumentRoundTripsThroughImportExportByteForByte() throws IOException {
        final DocRef docRef = createShapeshifterDoc();
        final ShapeshifterDoc doc = shapeshifterStore.readDocument(docRef);

        final ImportExportDocument exported = serialiser.write(doc);
        final ShapeshifterDoc imported = serialiser.read(exported);

        assertThat(new String(exported.getExtAssetData("json"), StandardCharsets.UTF_8)).isEqualTo(doc.getData());
        assertThat(imported).isEqualTo(doc);
    }

    private DocRef createShapeshifterDoc() throws IOException {
        final String ds3 = Files.readString(LEGACY.resolve(FIXTURE + ".ds3.xml"));
        final String json = ProjectReader.writePretty(Ds3Migration.importXml(ds3));
        final DocRef docRef = shapeshifterStore.createDocument(FIXTURE);
        shapeshifterStore.writeDocument(shapeshifterStore.readDocument(docRef)
                .copy()
                .description("Migrated from " + FIXTURE + ".ds3.xml")
                .data(json)
                .build());
        return docRef;
    }

    private DocRef createPipeline(final DocRef shapeshifterRef) {
        final DocRef pipelineRef = PipelineTestUtil.createTestPipeline(pipelineStore, PIPELINE);
        final PipelineDoc pipelineDoc = pipelineStore.readDocument(pipelineRef);
        final PipelineDataBuilder builder = new PipelineDataBuilder(pipelineDoc.getPipelineData());
        builder.addProperty(PipelineDataUtil.createProperty("shapeshifterParser", "shapeshifter", shapeshifterRef));
        pipelineStore.writeDocument(pipelineDoc.copy().pipelineData(builder.build()).build());
        return pipelineRef;
    }

    private static String golden() throws IOException {
        return Files.readString(LEGACY.resolve(FIXTURE + ".out.xml"));
    }

    private static List<String> dataElements(final String xml) {
        return java.util.regex.Pattern.compile("<data [^>]*/>").matcher(xml).results()
                .map(java.util.regex.MatchResult::group).toList();
    }

    private static long recordsIn(final String golden) {
        return golden.lines().filter(line -> line.strip().equals("<record>")).count();
    }
}
