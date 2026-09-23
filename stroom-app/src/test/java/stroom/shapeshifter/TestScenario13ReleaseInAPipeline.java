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

package stroom.shapeshifter;

import stroom.data.shared.StreamTypeNames;
import stroom.docref.DocRef;
import stroom.meta.api.MetaService;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.xml.converter.ds3.DS3ParserFactory;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.api.ProcessorResult;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.QueryData;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.element.ShapeshifterAiParser;
import stroom.shapeshifter.ai.extraction.DataSplitterCompiler;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.scenario.AdvisorHolder;
import stroom.shapeshifter.ai.scenario.QuestionMatcher;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scenario.Script;
import stroom.shapeshifter.ai.scenario.Structure;
import stroom.shapeshifter.ai.stage.Ledger;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.stage.Shape;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.test.CommonTranslationTestHelper;
import stroom.test.StoreCreationTool;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 13, in tier 2 against a real database: **promotion releases the ledger, and
/// what is released is actually processed**.
///
/// Tier 1 records the reprocess request; this is the other half, owed out of phase C, where the request
/// is a reprocess filter over real streams and task creation turns it back into the inputs that waited.
/// It is what "nothing is held" (design 01 §5.2) means in practice: a stream that arrives before its
/// shape is known is not parked anywhere, it is processed to an error stream and remembered, and the
/// promotion that settles the shape puts it back through the pipeline as-current (A12).
class TestScenario13ReleaseInAPipeline extends AbstractCoreIntegrationTest {

    private static final String FEED = "DOOR-ACCESS-RELEASED";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");

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
    @Inject
    private Provider<DS3ParserFactory> parserFactories;

    @Test
    void scenario13ThePromotionReleasesTheStreamThatWaitedAndItIsProcessed() {
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

        // Learning is off, so the first stream cannot bind and is not held: an error stream naming the
        // shape, and a ledger row naming the input and the pipeline that would replay it.
        advisor.set(Script.of());
        final Meta early = rawStream();
        final ProcessorResult sentinelled = processOne();
        assertThat(sentinelled.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isEqualTo(1);
        assertThat(sentinelled.getWritten()).isZero();
        assertThat(events()).describedAs("nothing was produced from it").isEmpty();
        // That it is remembered is scenario 20's to assert, and asserting it here would spend it: the
        // ledger's only reader is its release, and a release is what this scenario is waiting for.

        // Learning on. The next stream of the same shape learns and is promoted, and the promotion
        // releases the shape's ledger — which is a reprocess filter over what the pipeline made of the
        // stream that waited.
        allowLearning(doc);
        final Script script = script();
        advisor.set(script);
        final Meta learned = rawStream();
        final ProcessorResult promoted = processOne();
        script.verifyExhausted();

        assertThat(promoted.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(promoted.getWritten()).isEqualTo(6);
        assertThat(rules.forDocument(doc.getUuid())).hasSize(1);
        assertThat(ledger.release(doc.getUuid(), shape()))
                .describedAs("the ledger is empty: what it held has been asked for")
                .isEmpty();

        // And the ask is real. The filter the release created yields a task for the stream that waited,
        // which now runs through the rule that settled its shape — bound, not learned again.
        final Script silent = Script.of();
        advisor.set(silent);
        final List<ProcessorResult> released = commonTranslationTestHelper.processAll();
        assertThat(released).describedAs("one task, for the stream that waited").hasSize(1);
        assertThat(released.get(0).getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(released.get(0).getWritten()).isEqualTo(6);

        assertThat(events())
                .describedAs("both streams have output now: the one that taught the shape and the one "
                             + "that arrived before it was known")
                .extracting(Meta::getParentMetaId)
                .containsExactlyInAnyOrder(learned.getId(), early.getId());
        assertThat(silent.asked())
                .describedAs("released as-current (A12): the rule that settled the shape served it, and "
                             + "the model was not asked again")
                .isEmpty();
        assertThat(rules.forDocument(doc.getUuid())).hasSize(1);
    }

    private String shape() {
        return Shape.of(List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE),
                Map.of(MetaFields.FIELD_FEED, FEED, MetaFields.FIELD_TYPE, StreamTypeNames.RAW_EVENTS)).id();
    }

    /**
     * The split and target questions are answered from the configurations the script will give, as the
     * module's scenarios do; the script states only what the scenario is about.
     */
    private Script script() {
        final DataSplitterCompiler compiler = new DataSplitterCompiler(parserFactories, new ErrorReceiverProxy());
        return Script.of()
                .structure(new Structure(
                        List.of(new DataSplitterStep(compiler), new XsltStep()), CSV.configuration(), XSLT))
                .expect(QuestionMatcher.chain()
                        .withKey("Feed", FEED)
                        .withKey("Type", StreamTypeNames.RAW_EVENTS))
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    /// The document as the operator first has it: nothing may be learned, so nothing can bind.
    private DocRef document() {
        final DocRef docRef = shapeshifterAiStore.createDocument("door-access-released");
        shapeshifterAiStore.writeDocument(shapeshifterAiStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.DISABLED)
                .executionMode(ExecutionMode.INLINE)
                .plan(PlanExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(5)
                .promotionFloor(0.85)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build());
        return docRef;
    }

    private void allowLearning(final DocRef docRef) {
        shapeshifterAiStore.writeDocument(shapeshifterAiStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.AUTOMATIC)
                .build());
    }

    private DocRef pipeline(final DocRef feed, final DocRef doc) {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("shapeshifterAi", ShapeshifterAiParser.TYPE))
                .addElement(new PipelineElement("recordOutputFilter", "RecordOutputFilter"))
                .addElement(new PipelineElement("writeRecordCountFilter", "RecordCountFilter"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "shapeshifterAi")
                .addLink("shapeshifterAi", "recordOutputFilter")
                .addLink("recordOutputFilter", "writeRecordCountFilter")
                .addLink("writeRecordCountFilter", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("shapeshifterAi", "shapeshifterAi", doc))
                .addProperty(PipelineDataUtil.createProperty("writeRecordCountFilter", "countRead", false))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "segmentOutput", true))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument(FEED + " supervised");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        return pipelineRef;
    }

    private List<Meta> events() {
        return metaService.find(FindMetaCriteria.createWithType(StreamTypeNames.EVENTS)).getValues();
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
        return raw.stream().max((a, b) -> Long.compare(a.getId(), b.getId())).orElseThrow();
    }

    private ProcessorResult processOne() {
        final List<ProcessorResult> results = commonTranslationTestHelper.processAll();
        assertThat(results).hasSize(1);
        return results.get(0);
    }
}
