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
import stroom.data.store.mock.MockStore;
import stroom.docref.DocRef;
import stroom.meta.mock.MockMetaService;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.TextConverterDoc.TextConverterType;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.textconverter.TextConverterStore;
import stroom.pipeline.xml.converter.ds3.DS3ParserFactory;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.api.ProcessorResult;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.QueryData;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.element.ShapeshifterAiFilter;
import stroom.shapeshifter.ai.element.ShapeshifterAiParser;
import stroom.shapeshifter.ai.extraction.DataSplitterCompiler;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.scenario.AdvisorHolder;
import stroom.shapeshifter.ai.scenario.QuestionMatcher;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scenario.Script;
import stroom.shapeshifter.ai.scenario.Structure;
import stroom.shapeshifter.ai.stage.Bindings;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.test.AbstractProcessIntegrationTest;
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

/**
 * Design 02 §5, scenario 50 (Tier 2): a supervised stage at the <i>transformation</i> position — fed the
 * records a parser above it made, learning the stylesheet that turns them into events. The `S1 → S2` pair
 * of design 01 §3 with a hand-written parser standing in for S1.
 * <p>
 * It is the position §12 item 18's audit found undrawable: a supervisor that declares the parser role
 * cannot be placed under a parser, so a stage whose replay unit is the record had nowhere to stand. What
 * this proves is that {@link ShapeshifterAiFilter} can stand there and do the same work — the events it
 * is given become the text the stage sees, the chain it learns has no parser in it (A1), and what the
 * learned stylesheet makes of each record reaches the filter's own targets.
 */
class TestScenario50SupervisedTransformation extends AbstractProcessIntegrationTest {

    private static final String FEED = "DOOR-ACCESS-RECORDS";
    private static final String PAIR_FEED = "DOOR-ACCESS-PAIR";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String EXPECTED_EVENTS = Scenarios.resource("csv-logon.events.xml");

    /// What one record is in what the parser above writes: the Data Splitter's own record element.
    private static final String RECORD_ELEMENT = "record";

    /// The same stylesheet with something to say about every record, which is how what a *served*
    /// fragment says is told apart from what a candidate says (A20). A fragment runs under an error
    /// receiver of its own so that the complaints of candidates nobody keeps go to the model rather
    /// than to the operator; the run that is kept has to reach the pipeline's error stream, and a
    /// warning is the cheapest thing that proves it without failing the gate.
    private static final String XSLT_THAT_WARNS = XSLT.replace(
            "<xsl:template match=\"record\">",
            "<xsl:template match=\"record\">\n    <xsl:message><warn>a word about this record"
            + "</warn></xsl:message>");

    @Inject
    private StoreCreationTool storeCreationTool;
    @Inject
    private CommonTranslationTestHelper commonTranslationTestHelper;
    @Inject
    private ProcessorFilterService processorFilterService;
    @Inject
    private ShapeshifterAiStore shapeshifterAiStore;
    @Inject
    private Rules rules;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private TextConverterStore textConverterStore;
    @Inject
    private MockMetaService metaService;
    @Inject
    private MockStore streamStore;
    @Inject
    private AdvisorHolder advisor;
    @Inject
    private Provider<DS3ParserFactory> parserFactories;

    @Test
    void learnsAStylesheetForTheRecordsAParserAboveItMade() {
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef doc = document();
        final DocRef pipeline = pipeline(feed, doc, splitter());
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

        // One question of substance: the stylesheet. A stage fed by a parser may only learn chains that
        // do not parse (A1), and this document allows one element, so there is no chain to choose and
        // none is asked for. The split and the targets are answered from the configuration the script
        // will give.
        final Script script = Script.of()
                .structure(Structure.ofXml(List.of(new XsltStep()), RECORD_ELEMENT, XSLT_THAT_WARNS))
                .expect(QuestionMatcher.configuration("XSLTFilter"))
                .reply(Scenarios.fenced(XSLT_THAT_WARNS));
        advisor.set(script);
        rawStream();
        final ProcessorResult first = processOne();
        script.verifyExhausted();

        assertThat(first.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(first.getWritten()).isEqualTo(6);
        assertThat(first.getMarkerCount(Severity.WARNING))
                .describedAs("A20: what the fragment that was served said is on this pipeline's error "
                             + "stream, once for the stream rather than once per record")
                .isEqualTo(1);

        assertThat(rules.forDocument(doc.getUuid())).hasSize(1);
        final RoutingRule rule = rules.forDocument(doc.getUuid()).get(0);
        assertThat(rule.isDraft()).isFalse();
        assertThat(rule.isProvisional()).isFalse();
        assertThat(pipelineStore.readDocument(rule.getPipeline()).getPipelineData().getAddedElements())
                .extracting(PipelineElement::getType)
                .describedAs("A1: a stage fed by a parser learns a chain with no parser in it")
                .doesNotContain("DSParser")
                .contains("XSLTFilter");

        final Meta output = outputs().get(0);
        assertThat(canonical(data(output))).isEqualTo(canonical(EXPECTED_EVENTS));
        final Map<String, String> attributes = streamStore.getAttributes(output.getId());
        assertThat(attributes)
                .describedAs("design 01 §7.3 rule 3: the output carries what produced it")
                .containsEntry(Bindings.DOC_ATTRIBUTE, doc.getUuid())
                .containsEntry(Bindings.RULE_ATTRIBUTE, rule.getUuid());

        // The second stream of the shape: bound by the rule, the model not consulted, and served by the
        // one run the stage judged it on.
        final Script silent = Script.of();
        advisor.set(silent);
        rawStream();
        final ProcessorResult second = processOne();

        assertThat(silent.asked()).isEmpty();
        assertThat(second.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(second.getWritten()).isEqualTo(6);
        assertThat(second.getMarkerCount(Severity.WARNING))
                .describedAs("and on a stream a rule already bound, where the run that says it is the "
                             + "run the promotion gate was judged on")
                .isEqualTo(1);
        assertThat(canonical(data(outputs().get(1)))).isEqualTo(canonical(EXPECTED_EVENTS));
        assertThat(rules.forDocument(doc.getUuid())).hasSize(1);
    }

    /// The pair itself (design 01 §3): a supervised stage that learns the parser, and another below it
    /// that learns the transform. Neither could be drawn below the other before item 4's second element
    /// existed, so nothing had ever run both.
    ///
    /// What it asks of the two, beyond running: they have one set of stream attributes between them, and
    /// each must be on it. The stage nearest the source takes the plain names, the one behind it the
    /// same names under its element, so that what the transformation stage bound is not lost to whoever
    /// wrote first (design 01 §7.3 rule 3).
    @Test
    void theExtractThenTransformPairBothLearnAndBothAreOnTheStream() {
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(PAIR_FEED);
        final DocRef extraction = stage("pair-extraction", List.of("DSParser"));
        final DocRef transformation = stage("pair-transformation", List.of("XSLTFilter"));
        final DocRef pipeline = pair(feed, extraction, transformation);
        processorFilterService.create(CreateProcessFilterRequest.builder()
                .pipeline(pipeline)
                .queryData(QueryData.builder()
                        .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                        .expression(ExpressionOperator.builder()
                                .addTextTerm(MetaFields.FEED, Condition.EQUALS, PAIR_FEED)
                                .addTextTerm(MetaFields.TYPE, Condition.EQUALS, StreamTypeNames.RAW_EVENTS)
                                .build())
                        .build())
                .priority(1)
                .build());

        // One configuration question per stage, each allowing one element and so asking for no chain.
        final Script script = Script.of()
                .structure(new Structure(List.of(new DataSplitterStep(
                        new DataSplitterCompiler(parserFactories, new ErrorReceiverProxy())), new XsltStep()),
                        CSV.configuration(), XSLT))
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .structure(Structure.ofXml(List.of(new XsltStep()), RECORD_ELEMENT, XSLT))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        advisor.set(script);
        rawStream(PAIR_FEED);
        final ProcessorResult result = processOne();
        script.verifyExhausted();

        assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(result.getWritten()).isEqualTo(6);
        final RoutingRule parses = rules.forDocument(extraction.getUuid()).get(0);
        final RoutingRule transforms = rules.forDocument(transformation.getUuid()).get(0);
        assertThat(pipelineStore.readDocument(parses.getPipeline()).getPipelineData().getAddedElements())
                .extracting(PipelineElement::getType).contains("DSParser");
        assertThat(pipelineStore.readDocument(transforms.getPipeline()).getPipelineData().getAddedElements())
                .extracting(PipelineElement::getType).contains("XSLTFilter").doesNotContain("DSParser");

        final Meta output = outputs().get(0);
        assertThat(canonical(data(output))).isEqualTo(canonical(EXPECTED_EVENTS));
        assertThat(streamStore.getAttributes(output.getId()))
                .describedAs("both stages are on the stream: the one nearest the source under the plain "
                             + "names, the one behind it under its element")
                .containsEntry(Bindings.RULE_ATTRIBUTE, parses.getUuid())
                .containsEntry(Bindings.RULE_ATTRIBUTE + ".shapeshifterAiFilter", transforms.getUuid())
                .containsEntry(Bindings.DOC_ATTRIBUTE, extraction.getUuid())
                .containsEntry(Bindings.DOC_ATTRIBUTE + ".shapeshifterAiFilter", transformation.getUuid());
    }

    /// A supervised stage's document, allowing what the stage at that position may learn (A1).
    private DocRef stage(final String name, final List<String> allowed) {
        final DocRef docRef = shapeshifterAiStore.createDocument(name);
        shapeshifterAiStore.writeDocument(shapeshifterAiStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.AUTOMATIC)
                .executionMode(ExecutionMode.INLINE)
                .plan(PlanExample.TARGET_FIRST)
                .allowedElements(allowed)
                .minRecordsPerShape(5)
                .promotionFloor(0.85)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build());
        return docRef;
    }

    /// {@code Source -> ShapeshifterAi -> ShapeshifterAiFilter -> ...}: the pair of design 01 §3, with
    /// no writer between them — the second stage is given the first's events.
    private DocRef pair(final DocRef feed, final DocRef extraction, final DocRef transformation) {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("shapeshifterAi", ShapeshifterAiParser.TYPE))
                .addElement(new PipelineElement("shapeshifterAiFilter", ShapeshifterAiFilter.TYPE))
                .addElement(new PipelineElement("recordOutputFilter", "RecordOutputFilter"))
                .addElement(new PipelineElement("writeRecordCountFilter", "RecordCountFilter"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "shapeshifterAi")
                .addLink("shapeshifterAi", "shapeshifterAiFilter")
                .addLink("shapeshifterAiFilter", "recordOutputFilter")
                .addLink("recordOutputFilter", "writeRecordCountFilter")
                .addLink("writeRecordCountFilter", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("shapeshifterAi", "shapeshifterAi", extraction))
                .addProperty(PipelineDataUtil.createProperty("shapeshifterAiFilter", "shapeshifterAi",
                        transformation))
                .addProperty(PipelineDataUtil.createProperty("writeRecordCountFilter", "countRead", false))
                .addProperty(PipelineDataUtil.createProperty("xmlWriter", "indentOutput", true))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "segmentOutput", true))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument("DOOR-ACCESS supervised pair");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        return pipelineRef;
    }

    private DocRef document() {
        final DocRef docRef = shapeshifterAiStore.createDocument("door-access-records");
        shapeshifterAiStore.writeDocument(shapeshifterAiStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.AUTOMATIC)
                .executionMode(ExecutionMode.INLINE)
                .plan(PlanExample.TARGET_FIRST)
                // The transformation stage's allowed elements: nothing that parses (A1).
                .allowedElements(List.of("XSLTFilter"))
                .minRecordsPerShape(5)
                .promotionFloor(0.85)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build());
        return docRef;
    }

    /// The parser above the stage: the same Data Splitter scenario 18's supervisor learns, written by
    /// hand here because this scenario is about what stands below it.
    private DocRef splitter() {
        final DocRef ref = textConverterStore.createDocument("door-access-csv");
        textConverterStore.writeDocument(textConverterStore.readDocument(ref)
                .copy()
                .converterType(TextConverterType.DATA_SPLITTER)
                .data(CSV.configuration())
                .build());
        return ref;
    }

    /**
     * {@code Source → DSParser → ShapeshifterAiFilter → SchemaFilter → RecordOutputFilter →
     * RecordCountFilter → XMLWriter → StreamAppender}: a supervised stage where a translation would be.
     */
    private DocRef pipeline(final DocRef feed, final DocRef doc, final DocRef textConverter) {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("dsParser", "DSParser"))
                .addElement(new PipelineElement("shapeshifterAiFilter", ShapeshifterAiFilter.TYPE))
                .addElement(new PipelineElement("schemaFilter", "SchemaFilter"))
                .addElement(new PipelineElement("recordOutputFilter", "RecordOutputFilter"))
                .addElement(new PipelineElement("writeRecordCountFilter", "RecordCountFilter"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "dsParser")
                .addLink("dsParser", "shapeshifterAiFilter")
                .addLink("shapeshifterAiFilter", "schemaFilter")
                .addLink("schemaFilter", "recordOutputFilter")
                .addLink("recordOutputFilter", "writeRecordCountFilter")
                .addLink("writeRecordCountFilter", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("dsParser", "textConverter", textConverter))
                .addProperty(PipelineDataUtil.createProperty("shapeshifterAiFilter", "shapeshifterAi", doc))
                .addProperty(PipelineDataUtil.createProperty("schemaFilter", "schemaGroup", "EVENTS"))
                .addProperty(PipelineDataUtil.createProperty("writeRecordCountFilter", "countRead", false))
                .addProperty(PipelineDataUtil.createProperty("xmlWriter", "indentOutput", true))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "segmentOutput", true))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument("DOOR-ACCESS supervised transformation");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        return pipelineRef;
    }

    private void rawStream() {
        rawStream(FEED);
    }

    private void rawStream(final String feed) {
        try {
            final Path file = Files.createTempFile(getCurrentTestDir(), "door-access", ".csv");
            Files.writeString(file, CSV.input(), StandardCharsets.UTF_8);
            storeCreationTool.loadEventData(feed, file, null);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ProcessorResult processOne() {
        final List<ProcessorResult> results = commonTranslationTestHelper.processAll();
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    private List<Meta> outputs() {
        return metaService.getMetaMap().values().stream()
                .filter(meta -> StreamTypeNames.EVENTS.equals(meta.getTypeName()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    private String data(final Meta meta) {
        return new String(streamStore.getFileData().get(meta.getId()).get(meta.getTypeName()), StandardCharsets.UTF_8);
    }

    /**
     * The two serialisers indent differently and one writes a declaration; the events are the same.
     */
    private static String canonical(final String xml) {
        return xml.replaceAll("<\\?xml[^>]*\\?>", "").replaceAll(">\\s+<", "><").strip();
    }
}
