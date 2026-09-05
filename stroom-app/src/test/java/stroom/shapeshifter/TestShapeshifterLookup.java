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

import stroom.data.shared.StreamTypeNames;
import stroom.data.store.mock.MockStore;
import stroom.docref.DocRef;
import stroom.meta.mock.MockMetaService;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.PipelineTestUtil;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.api.ProcessorResult;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.QueryData;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.shapeshifter.pipeline.ShapeshifterStore;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.test.CommonTranslationTestHelper;
import stroom.test.StoreCreationTool;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 26 phase 4, the test the module could not have: a Shapeshifter {@code lookup} against a
 * reference stream that Stroom itself loaded, through {@code ReferenceData}, the effective-stream
 * lookup, the Reference Loader pipeline and the off-heap store, under Stroom's processor. The
 * reference feed is the one Stroom's own translation tests use, and the event data is theirs too,
 * so the configuration asks the question their XSLT asks: where is {@code Device/Location} at the
 * event's time?
 */
class TestShapeshifterLookup extends AbstractProcessIntegrationTest {

    /** The content pack's Reference Loader pipeline, as {@code StoreCreationTool} knows it. */
    private static final String REFERENCE_LOADER_PIPELINE_UUID = "da1c7351-086f-493b-866a-b42dbe990700";
    /** One of the reference feeds {@code CommonTranslationTestHelper.createReferenceFeeds()} loads. */
    private static final String HOSTNAME_TO_LOCATION = "HOSTNAME_TO_LOCATION";
    private static final String FEED = "TEST_SHAPESHIFTER_LOOKUP";
    private static final int EVENTS = 26;

    /** Parser, then Stroom's TextWriter, then a stream appender: one line of text per event. */
    private static final String PIPELINE = """
            {
              "elements" : { "add" : [
                { "id" : "shapeshifterParser", "type" : "ShapeshifterParser" },
                { "id" : "textWriter", "type" : "TextWriter" },
                { "id" : "streamAppender", "type" : "StreamAppender" } ] },
              "properties" : { "add" : [
                { "element" : "streamAppender", "name" : "streamType", "value" : { "string" : "Events" } } ] },
              "links" : { "add" : [
                { "from" : "shapeshifterParser", "to" : "textWriter" },
                { "from" : "textWriter", "to" : "streamAppender" } ] }
            }
            """;

    /**
     * Over the network monitoring CSV: the device, then the location looked up at the event's
     * time. The time is the date and time columns formatted the way NetworkMonitoring.xsl formats
     * them, bound by name and passed to the lookup, so both calls run against Stroom's services.
     */
    private static final String DEVICE_LOCATIONS = """
            {"name": "device-locations", "version": 5,
             "source": {"buffer_size": 4096, "ignore_errors": true, "encoding": "utf-8"},
             "templates": [
              {"id": "00000000-0000-0000-0000-000000000001", "name": "root", "match": "source",
               "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "rows"}}]},
              {"id": "00000000-0000-0000-0000-000000000002", "name": "header", "mode": "rows", "consume": true,
               "match": {"regex": {"pattern": "Date,Time,[^\\n]*\\n"}}, "body": []},
              {"id": "00000000-0000-0000-0000-000000000003", "name": "row", "mode": "rows",
               "match": {"regex": {"pattern": "([^,\\n]*),([^,\\n]*),([^,\\n]*),([^,\\n]*),[^\\n]*\\n"}},
               "body": [
                 {"value-of": {"parts": [{"capture": {"group": 4}}]}}, {"text": " "},
                 {"call": {"function": "format-date", "name": "when", "select": [
                    {"parts": [{"capture": {"group": 1}}, {"capture": {"group": 2}}]},
                    {"parts": [{"text": "dd/MM/yyyyHH:mm:ss"}]}]}},
                 {"call": {"function": "lookup", "select": [
                    {"parts": [{"text": "HOSTNAME_TO_LOCATION_MAP"}]},
                    {"parts": [{"text": "Device/Location"}]},
                    {"parts": [{"capture": {"var_id": "when", "group": 0}}]}]}},
                 {"text": "\\n"}]}
             ]}
            """;

    @Inject
    private CommonTranslationTestHelper commonTranslationTestHelper;
    @Inject
    private StoreCreationTool storeCreationTool;
    @Inject
    private ProcessorFilterService processorFilterService;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private ShapeshifterStore shapeshifterStore;
    @Inject
    private MockMetaService metaService;
    @Inject
    private MockStore streamStore;

    @Test
    void lookupReadsAReferenceStreamStroomLoadedThroughItsOwnReferenceData() throws IOException {
        // The reference side is exactly Stroom's translation tests': raw reference streams, and
        // the reference pipelines that turn them into reference streams, processed first.
        final Set<DocRef> referenceFeeds = commonTranslationTestHelper.createReferenceFeeds();
        final DocRef hostNameToLocation = referenceFeeds.stream()
                .filter(feed -> HOSTNAME_TO_LOCATION.equals(feed.getName()))
                .findFirst()
                .orElseThrow();
        for (final ProcessorResult result : commonTranslationTestHelper.processAll()) {
            assertThat(result.getMarkerCount(Severity.SEVERITIES)).as(result.toString()).isZero();
            assertThat(result.getWritten()).as(result.toString()).isGreaterThan(0);
        }
        final DocRef referenceLoader = pipelineStore.list().stream()
                .filter(docRef -> REFERENCE_LOADER_PIPELINE_UUID.equals(docRef.getUuid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The content pack's Reference Loader pipeline is not installed"));

        // The event side: a Shapeshifter pipeline over the same CSV, the reference feed named on
        // the element as it would be on an XSLTFilter, run by Stroom's processor.
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef configRef = shapeshifterStore.createDocument("device-locations");
        shapeshifterStore.writeDocument(
                shapeshifterStore.readDocument(configRef).copy().data(DEVICE_LOCATIONS).build());
        final DocRef pipelineRef = PipelineTestUtil.createTestPipeline(pipelineStore, PIPELINE);
        final PipelineDoc pipelineDoc = pipelineStore.readDocument(pipelineRef);
        final PipelineDataBuilder builder = new PipelineDataBuilder(pipelineDoc.getPipelineData());
        builder.addProperty(PipelineDataUtil.createProperty("shapeshifterParser", "shapeshifter", configRef));
        builder.addPipelineReference(PipelineDataUtil.createReference("shapeshifterParser", "pipelineReference",
                referenceLoader, hostNameToLocation, StreamTypeNames.REFERENCE));
        pipelineStore.writeDocument(pipelineDoc.copy().pipelineData(builder.build()).build());
        processorFilterService.create(CreateProcessFilterRequest.builder()
                .pipeline(pipelineRef)
                .queryData(QueryData.builder()
                        .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                        .expression(ExpressionOperator.builder()
                                .addTextTerm(MetaFields.FEED, ExpressionTerm.Condition.EQUALS, feed.getName())
                                .addTextTerm(MetaFields.TYPE, ExpressionTerm.Condition.EQUALS,
                                        StreamTypeNames.RAW_EVENTS)
                                .build())
                        .build())
                .priority(1)
                .build());
        storeCreationTool.loadEventData(FEED, CommonTranslationTestHelper.VALID_RESOURCE_NAME, null);

        final List<ProcessorResult> results = commonTranslationTestHelper.processAll();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getMarkerCount(Severity.SEVERITIES)).as(results.get(0).toString()).isZero();

        // Every event found its location: the XML value Stroom's reference pipeline wrote, as
        // text, indented as that pipeline indented it, so one event is several lines.
        final List<String> events = List.of(eventsWritten().split("(?m)^(?=device)"));
        assertThat(events).hasSize(EVENTS);
        for (final String event : events) {
            assertThat(event).startsWith("device")
                    .contains("<evt:Location xmlns:evt=\"event-logging:3\">")
                    .contains("<evt:Country>Country 1</evt:Country>")
                    .contains("<evt:Site>Site 1</evt:Site>")
                    .contains("<evt:Building>Building 1</evt:Building>")
                    .endsWith("</evt:Location>\n")
                    .doesNotContain("<?xml");
        }
    }

    /** The one Events stream the Shapeshifter pipeline wrote for its feed. */
    private String eventsWritten() {
        final List<Meta> events = metaService.getMetaMap().values().stream()
                .filter(meta -> FEED.equals(meta.getFeedName()) && StreamTypeNames.EVENTS.equals(meta.getTypeName()))
                .toList();
        assertThat(events).hasSize(1);
        final Meta meta = events.get(0);
        final byte[] data = streamStore.getFileData().get(meta.getId()).get(meta.getTypeName());
        return new String(data, StandardCharsets.UTF_8);
    }
}
