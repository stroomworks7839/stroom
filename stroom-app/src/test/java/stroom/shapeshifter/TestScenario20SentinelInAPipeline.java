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
import stroom.docref.DocRef;
import stroom.meta.api.MetaService;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.api.ProcessorResult;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.QueryData;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.element.ShapeshifterAiParser;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.impl.db.LedgerDao;
import stroom.shapeshifter.ai.impl.db.RulesDao;
import stroom.shapeshifter.ai.scenario.AdvisorHolder;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scenario.Script;
import stroom.shapeshifter.ai.stage.Ledger;
import stroom.shapeshifter.ai.stage.Ledger.Released;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.stage.Shape;
import stroom.shapeshifter.shared.LearningMode;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.test.CommonTranslationTestHelper;
import stroom.test.StoreCreationTool;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Design 02 §5, scenario 20, in tier 2 against a real database (design 03, phase C): a stream whose shape
/// has no binding produces no output, an error stream naming the shape and the reason, and a row in the
/// ledger naming the input — the row being a row now, in `shapeshifter_ledger` (A26), and not a map in one
/// node's heap. Nothing is held: what the ledger names is what promotion would replay.
class TestScenario20SentinelInAPipeline extends AbstractCoreIntegrationTest {

    private static final String FEED = "DOOR-ACCESS-UNBOUND";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Inject
    private StoreCreationTool storeCreationTool;
    @Inject
    private CommonTranslationTestHelper commonTranslationTestHelper;
    @Inject
    private ProcessorFilterService processorFilterService;
    @Inject
    private ShapeshifterAiStore shapeshifterAiStore;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private MetaService metaService;
    @Inject
    private AdvisorHolder advisor;
    @Inject
    private Rules rules;
    @Inject
    private Ledger ledger;

    @Test
    void scenario20TheSentinelIsAnErrorStreamAndTheLedgerIsARow() {
        // This harness is the node's own wiring, so the seams are the tables of A26 and not one node's heap.
        assertThat(ledger).isInstanceOf(LedgerDao.class);
        assertThat(rules).isInstanceOf(RulesDao.class);
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef doc = document();
        final DocRef pipeline = pipeline(feed, doc);
        processorFilterService.create(CreateProcessFilterRequest.builder()
                .pipeline(pipeline)
                .queryData(QueryData.builder()
                        .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                        .expression(ExpressionOperator.builder()
                                .addTextTerm(MetaFields.FEED, Condition.EQUALS, FEED)
                                .addTextTerm(MetaFields.TYPE, Condition.EQUALS, StreamTypeNames.RAW_EVENTS)
                                .build())
                        .build())
                .priority(1)
                .build());
        // Learning is off for this document, so nothing can bind and nothing is asked: the shape is
        // sentinelled, which is what this scenario is about.
        final Script silent = Script.of();
        advisor.set(silent);
        final Meta raw = rawStream();

        final ProcessorResult result = processOne();

        assertThat(silent.asked()).describedAs("the model was not asked").isEmpty();
        assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR))
                .describedAs("one error, naming the shape").isEqualTo(1);
        assertThat(result.getWritten()).isZero();
        assertThat(metaService.find(FindMetaCriteria.createWithType(StreamTypeNames.EVENTS)).getValues())
                .describedAs("no output stream: the stage bound nothing")
                .isEmpty();
        assertThat(rules.forDocument(doc.getUuid())).describedAs("and wrote no rule").isEmpty();

        // The ledger names the input, in a row: promotion would replay exactly this stream (A12).
        final String shape = Shape.of(List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE),
                Map.of(MetaFields.FIELD_FEED, FEED, MetaFields.FIELD_TYPE, StreamTypeNames.RAW_EVENTS)).id();
        assertThat(ledger.release(doc.getUuid(), shape))
                .describedAs("the ledger row is a row in the database, not a map in one node's heap")
                .extracting(Released::inputId, Released::pipeline)
                .describedAs("naming the stream and the pipeline that would replay it (A12)")
                .containsExactly(tuple(raw.getId(), pipeline.getUuid()));
    }

    private DocRef document() {
        final DocRef docRef = shapeshifterAiStore.createDocument("door-access-unbound");
        shapeshifterAiStore.writeDocument(shapeshifterAiStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.DISABLED)
                .build());
        return docRef;
    }

    private DocRef pipeline(final DocRef feed, final DocRef doc) {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("shapeshifterAi", ShapeshifterAiParser.TYPE))
                .addElement(new PipelineElement("recordOutputFilter", "RecordOutputFilter"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "shapeshifterAi")
                .addLink("shapeshifterAi", "recordOutputFilter")
                .addLink("recordOutputFilter", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("shapeshifterAi", "shapeshifterAi", doc))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "segmentOutput", true))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument(FEED + " supervised");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        return pipelineRef;
    }

    private Meta rawStream() {
        try {
            final Path file = Files.createTempFile(getCurrentTestDir(), "door-access", ".csv");
            Files.writeString(file, CSV.input(), StandardCharsets.UTF_8);
            storeCreationTool.loadEventData(FEED, file, null);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final List<Meta> raw = metaService.find(FindMetaCriteria.createWithType(StreamTypeNames.RAW_EVENTS))
                .getValues();
        assertThat(raw).hasSize(1);
        return raw.get(0);
    }

    private ProcessorResult processOne() {
        final List<ProcessorResult> results = commonTranslationTestHelper.processAll();
        assertThat(results).hasSize(1);
        return results.get(0);
    }
}
