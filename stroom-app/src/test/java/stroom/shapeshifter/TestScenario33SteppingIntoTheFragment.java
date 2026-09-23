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
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.SharedElementData;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.stepping.NestedElementData;
import stroom.pipeline.shared.stepping.PipelineStepRequest;
import stroom.pipeline.shared.stepping.StepType;
import stroom.pipeline.shared.stepping.SteppingResult;
import stroom.pipeline.stepping.SteppingService;
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
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiStepDetails;
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
import static org.assertj.core.api.Assertions.tuple;

/**
 * Design 02 §5, scenario 33 (Tier 2): <b>stepping into a supervised stage</b> (A30, design 01 §11.7,
 * §12 item 19).
 * <p>
 * A supervised stage looks, from outside, like one element that takes a stream and emits events.
 * Everything that decided the shape of them — the records the learned parser cut, what the learned
 * transform made of each — happens inside it and is invisible to the person debugging the pipeline it
 * sits in. This is what opens it: the fragment's own elements, beneath the stage, each with what it was
 * given and what it wrote for the record at the cursor, stepping like any other pair.
 * <p>
 * The chain shown is the fragment as it was learned. Two elements the runner adds to make a fragment
 * runnable — a parser in front of a chain that has none, and a filter at the tail to keep the events —
 * are its scaffolding and are no part of what was learned, so they are not shown, exactly as the walk
 * that scores the chain reads the fragment as the fragment has it.
 */
class TestScenario33SteppingIntoTheFragment extends AbstractProcessIntegrationTest {

    private static final String FEED = "DOOR-ACCESS";
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
    private Rules rules;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private MockMetaService metaService;
    @Inject
    private MockStore streamStore;
    @Inject
    private AdvisorHolder advisor;
    @Inject
    private Provider<DS3ParserFactory> parserFactories;
    @Inject
    private SteppingService steppingService;

    /**
     * Learn a feed, then step the pipeline that learned it: the supervisor's step details carry the
     * fragment's own chain, each element with the record's real input and output.
     */
    @Test
    void theFragmentsElementsStepBeneathTheStage() {
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef doc = document();
        final DocRef pipeline = pipeline(feed, doc);
        learn(pipeline, doc);

        final SteppingResult stepped = step(pipeline, StepType.FIRST);

        assertThat(stepped.isFoundRecord()).describedAs("the stream has records to step").isTrue();
        final SharedElementData stage = stepped.getStepData().getElementData("shapeshifterAi");
        assertThat(stage).describedAs("the supervised stage is in the step data").isNotNull();
        final ShapeshifterAiStepDetails details = (ShapeshifterAiStepDetails) stage.getDetails();
        assertThat(details).describedAs("with its stage pane (A30)").isNotNull();
        assertThat(details.isDryRun())
                .describedAs("stepping is a dry run: the stage routes and serves and learns nothing")
                .isTrue();

        final List<NestedElementData> nested = details.getNested();
        assertThat(nested)
                .extracting(NestedElementData::getName, NestedElementData::getType)
                .describedAs("the chain as it was learned, in the order it ran — and nothing the runner "
                             + "added to make it runnable")
                .containsExactly(tuple("dsParser", "DSParser"), tuple("xsltFilter", "XSLTFilter"));
        assertThat(nested)
                .extracting(NestedElementData::getId)
                .describedAs("named by the stage that ran them, because a fragment may hold an XSLTFilter "
                             + "and so may the pipeline it is running inside")
                .containsExactly("shapeshifterAi/dsParser", "shapeshifterAi/xsltFilter");

        final NestedElementData parser = nested.get(0);
        final NestedElementData transform = nested.get(1);
        assertThat(parser.getInput())
                .describedAs("the learned parser was given the stream's own text")
                .isNotBlank();
        assertThat(parser.getOutput())
                .describedAs("and cut it into records")
                .contains("<record>");
        assertThat(transform.getInput())
                .describedAs("what the parser wrote is what the transform was given: the chain, opened up")
                .isEqualTo(parser.getOutput());
        assertThat(transform.getOutput())
                .describedAs("and the transform wrote the event the stage emitted")
                .contains("<Event");
    }

    /**
     * A chain run once over the whole stream says so, on every record of it.
     * <p>
     * A stage standing where a parser stands is handed the stream, so its chain runs once: the learned
     * parser reads the whole of it and the learned transform is handed the whole of what that produced.
     * The records the stepper walks are cut from the far end <em>afterwards</em>, so stepping forward
     * moves the stage's record on and does not move these — there is nothing to move.
     * <p>
     * The row says which it is rather than leaving it to be noticed. Six records' worth of output under
     * a cursor sitting on the first reads as a fault otherwise, and the alternative — cutting the
     * element's output up for display — would show a person something the element never produced.
     */
    @Test
    void aChainRunOverTheWholeStreamSaysSo() {
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef doc = document();
        final DocRef pipeline = pipeline(feed, doc);
        learn(pipeline, doc);

        final SteppingResult first = step(pipeline, StepType.FIRST);
        final SteppingResult second = steppingService.step(request(pipeline)
                .copy()
                .stepType(StepType.FORWARD)
                .stepLocation(first.getFoundLocation())
                .sessionUuid(first.getSessionUuid())
                .build());

        assertThat(second.isFoundRecord()).describedAs("the stream has a second record").isTrue();
        assertThat(second.getFoundLocation().getRecordIndex())
                .describedAs("and the stage has stepped on to it")
                .isEqualTo(first.getFoundLocation().getRecordIndex() + 1);
        final List<NestedElementData> one = nested(first);
        final List<NestedElementData> two = nested(second);
        assertThat(one).allMatch(NestedElementData::isWholeStream)
                .describedAs("a chain handed the stream ran once over all of it, and each row says so");
        assertThat(two).extracting(NestedElementData::getOutput)
                .describedAs("so the second record shows the same one run as the first: what is there to "
                             + "show is what the elements did, which was done once")
                .isEqualTo(one.stream().map(NestedElementData::getOutput).toList());
        assertThat(one.get(0).getOutput())
                .describedAs("and what that one run produced is the whole stream's records, not one")
                .contains("2020-06-17T08:00:00.000Z")
                .contains("2020-06-17T08:14:00.000Z");
    }

    private static List<NestedElementData> nested(final SteppingResult result) {
        final SharedElementData stage = result.getStepData().getElementData("shapeshifterAi");
        return ((ShapeshifterAiStepDetails) stage.getDetails()).getNested();
    }

    private SteppingResult step(final DocRef pipeline, final StepType stepType) {
        return steppingService.step(request(pipeline).copy().stepType(stepType).build());
    }

    private PipelineStepRequest request(final DocRef pipeline) {
        return PipelineStepRequest.builder()
                .pipelineDoc(pipelineStore.readDocument(pipeline))
                .criteria(new FindMetaCriteria(ExpressionOperator.builder()
                        .addTextTerm(MetaFields.FEED, Condition.EQUALS, FEED)
                        .addTextTerm(MetaFields.TYPE, Condition.EQUALS, StreamTypeNames.RAW_EVENTS)
                        .build()))
                .timeout(Long.MAX_VALUE)
                .build();
    }

    /**
     * One stream through the pipeline, which teaches it: after this a rule is bound and stepping has a
     * fragment to open.
     */
    private void learn(final DocRef pipeline, final DocRef doc) {
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
        final Script script = script()
                .expect(QuestionMatcher.chain()
                        .withKey("Feed", FEED)
                        .withKey("Type", StreamTypeNames.RAW_EVENTS))
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        advisor.set(script);
        rawStream();
        final ProcessorResult result = processOne();
        script.verifyExhausted();
        assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        final List<RoutingRule> table = rules.forDocument(doc.getUuid());
        assertThat(table).hasSize(1);
        final PipelineDoc fragment = pipelineStore.readDocument(table.get(0).getPipeline());
        assertThat(fragment.getPipelineData().getAddedElements())
                .extracting(PipelineElement::getType)
                .containsExactly("Source", "DSParser", "XSLTFilter");
        // The model must not be asked again: a step is a dry run, and anything it consulted would be a
        // model call spent by looking.
        advisor.set(Script.of());
    }

    /**
     * The split and target questions are answered from the configurations the script will give, as the
     * module's scenarios do; the script states only what the scenario is about. The structure's compiler
     * is built when a question is asked, inside the processing pipeline's scope, so it reaches the node's
     * parser factory the way the element does.
     */
    private Script script() {
        final DataSplitterCompiler compiler = new DataSplitterCompiler(parserFactories, new ErrorReceiverProxy());
        return Script.of().structure(new Structure(
                List.of(new DataSplitterStep(compiler), new XsltStep()), CSV.configuration(), XSLT));
    }

    private DocRef document() {
        final DocRef docRef = shapeshifterAiStore.createDocument("door-access");
        shapeshifterAiStore.writeDocument(shapeshifterAiStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.AUTOMATIC)
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

    /**
     * {@code Source → ShapeshifterAi → SchemaFilter → RecordOutputFilter → RecordCountFilter → XMLWriter →
     * StreamAppender}: the supervised stage where a parser and its translation would be, and the standard
     * tail of an event pipeline after it.
     */
    private DocRef pipeline(final DocRef feed, final DocRef doc) {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("shapeshifterAi", ShapeshifterAiParser.TYPE))
                .addElement(new PipelineElement("schemaFilter", "SchemaFilter"))
                .addElement(new PipelineElement("recordOutputFilter", "RecordOutputFilter"))
                .addElement(new PipelineElement("writeRecordCountFilter", "RecordCountFilter"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "shapeshifterAi")
                .addLink("shapeshifterAi", "schemaFilter")
                .addLink("schemaFilter", "recordOutputFilter")
                .addLink("recordOutputFilter", "writeRecordCountFilter")
                .addLink("writeRecordCountFilter", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("shapeshifterAi", "shapeshifterAi", doc))
                .addProperty(PipelineDataUtil.createProperty("schemaFilter", "schemaGroup", "EVENTS"))
                .addProperty(PipelineDataUtil.createProperty("writeRecordCountFilter", "countRead", false))
                .addProperty(PipelineDataUtil.createProperty("xmlWriter", "indentOutput", true))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "segmentOutput", true))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument("DOOR-ACCESS supervised");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        return pipelineRef;
    }

    private void rawStream() {
        try {
            final Path file = Files.createTempFile(getCurrentTestDir(), "door-access", ".csv");
            Files.writeString(file, CSV.input(), StandardCharsets.UTF_8);
            storeCreationTool.loadEventData(FEED, file, null);
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
