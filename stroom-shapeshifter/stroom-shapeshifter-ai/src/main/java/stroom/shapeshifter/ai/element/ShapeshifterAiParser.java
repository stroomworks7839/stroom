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

package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
import stroom.meta.api.AttributeMap;
import stroom.meta.shared.Meta;
import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.ProcessException;
import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.factory.Pipeline;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.factory.PipelineProperty;
import stroom.pipeline.factory.PipelinePropertyDocRef;
import stroom.pipeline.factory.PipelineStackLoader;
import stroom.pipeline.parser.AbstractParser;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;
import stroom.pipeline.shared.data.PipelineLink;
import stroom.pipeline.state.FeedHolder;
import stroom.pipeline.state.MetaData;
import stroom.pipeline.state.MetaDataHolder;
import stroom.pipeline.state.MetaHolder;
import stroom.pipeline.textconverter.TextConverterStore;
import stroom.pipeline.xslt.XsltStore;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.extraction.DataSplitterCompiler;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.JsonStep;
import stroom.shapeshifter.ai.fragment.FragmentRunner;
import stroom.shapeshifter.ai.fragment.FragmentWriter;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.scoring.BusinessRulesScorer;
import stroom.shapeshifter.ai.scoring.CompileScorer;
import stroom.shapeshifter.ai.scoring.ExtractionQualityScorer;
import stroom.shapeshifter.ai.scoring.InputCoverageScorer;
import stroom.shapeshifter.ai.scoring.SchemaConformanceScorer;
import stroom.shapeshifter.ai.scoring.YieldScorer;
import stroom.shapeshifter.ai.stage.Bindings;
import stroom.shapeshifter.ai.stage.Decision;
import stroom.shapeshifter.ai.stage.Decision.Drafted;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Retracted;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Ledger;
import stroom.shapeshifter.ai.stage.Outputs;
import stroom.shapeshifter.ai.stage.RegressionSet;
import stroom.shapeshifter.ai.stage.Reprocessing;
import stroom.shapeshifter.ai.stage.Shapes;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.svg.shared.SvgImage;
import stroom.task.api.TaskContextFactory;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The supervisor element (design 01 §3, §12 item 4): one supervised stage in a pipeline, parameterised by
 * its Shapeshifter AI document. Sits where a parser sits — fed the stream, emitting events — and does
 * what the {@link Stage} decides: routes the stream to a bound fragment, learns one when nothing is
 * bound and the document allows, or refuses it with an error naming the shape and the reason (A4).
 * <p>
 * A bound fragment runs as a nested pipeline (A20): the fragment's merged {@code PipelineData} with a
 * {@link FragmentOutputFilter} at its tail, built by the same {@link PipelineFactory} in the same
 * pipeline scope, so its errors reach this pipeline's error stream and its events reach this element's
 * targets. The bindings that produced the output (§7.3 rule 3) go into the output stream's attributes
 * through {@link MetaData}. For now the fragment runs twice on a stream: once through the step runners
 * for the score the stage decides on, once as a pipeline for the output; a fragment runner over the
 * nested pipeline with per-element capture (§12 item 2) is what removes the first run.
 * <p>
 * The stage's runtime state — shapes, ledger, outputs, requests, regression set — is whatever the node
 * binds for the seams of A26; until the module of §12 item 8 exists that is in-memory and node-local.
 */
@ConfigurableElement(
        type = ShapeshifterAiParser.TYPE,
        category = Category.PARSER,
        description = """
                A supervised stage: routes each stream to the fragment its Shapeshifter AI document \
                binds for the stream's shape, learns a fragment for a shape nothing binds, and \
                refuses a shape it has given up on.
                """,
        roles = {
                PipelineElementType.ROLE_PARSER,
                PipelineElementType.ROLE_HAS_TARGETS,
                PipelineElementType.VISABILITY_SIMPLE,
                PipelineElementType.VISABILITY_STEPPING,
                PipelineElementType.ROLE_MUTATOR},
        icon = SvgImage.AI)
public class ShapeshifterAiParser extends AbstractParser {

    public static final String TYPE = "ShapeshifterAi";
    private static final String OUTPUT_ELEMENT_ID = "shapeshifterAiOutput";

    private final ShapeshifterAiStore store;
    private final PipelineStore pipelineStore;
    private final PipelineDataCache pipelineDataCache;
    private final PipelineFactory pipelineFactory;
    private final TaskContextFactory taskContextFactory;
    private final FeedHolder feedHolder;
    private final MetaHolder metaHolder;
    private final MetaDataHolder metaDataHolder;
    private final MetaData metaData;
    private final FragmentOutput fragmentOutput;
    private final ErrorReceiverProxy errorReceiverProxy;
    private final Stage stage;

    private DocRef docRef;

    @Inject
    public ShapeshifterAiParser(final ErrorReceiverProxy errorReceiverProxy,
                                final LocationFactoryProxy locationFactory,
                                final ShapeshifterAiStore store,
                                final PipelineStore pipelineStore,
                                final PipelineStackLoader pipelineStackLoader,
                                final TextConverterStore textConverterStore,
                                final XsltStore xsltStore,
                                final PipelineDataCache pipelineDataCache,
                                final PipelineFactory pipelineFactory,
                                final TaskContextFactory taskContextFactory,
                                final FeedHolder feedHolder,
                                final MetaHolder metaHolder,
                                final MetaDataHolder metaDataHolder,
                                final MetaData metaData,
                                final FragmentOutput fragmentOutput,
                                final DataSplitterCompiler dataSplitterCompiler,
                                final SchemaConformanceScorer schemaConformanceScorer,
                                final FragmentWriter fragmentWriter,
                                final Advisors advisors,
                                final Shapes shapes,
                                final Ledger ledger,
                                final Outputs outputs,
                                final Reprocessing reprocessing,
                                final RegressionSet regressionSet) {
        super(errorReceiverProxy, locationFactory);
        this.errorReceiverProxy = errorReceiverProxy;
        this.store = store;
        this.pipelineStore = pipelineStore;
        this.pipelineDataCache = pipelineDataCache;
        this.pipelineFactory = pipelineFactory;
        this.taskContextFactory = taskContextFactory;
        this.feedHolder = feedHolder;
        this.metaHolder = metaHolder;
        this.metaDataHolder = metaDataHolder;
        this.metaData = metaData;
        this.fragmentOutput = fragmentOutput;
        final List<stroom.shapeshifter.ai.learning.StepRunner> runners = List.of(
                new DataSplitterStep(dataSplitterCompiler), new JsonStep(), new XsltStep());
        this.stage = new Stage(
                advisors,
                runners,
                List.of(new CompileScorer(), new InputCoverageScorer(), new YieldScorer(), schemaConformanceScorer,
                        new ExtractionQualityScorer(), new BusinessRulesScorer()),
                fragmentWriter,
                new FragmentRunner(pipelineStore, pipelineStackLoader, textConverterStore, xsltStore, runners),
                shapes,
                ledger,
                outputs,
                reprocessing,
                regressionSet,
                Clock.systemUTC(),
                ThreadLocalRandom.current().nextLong());
    }

    @PipelineProperty(description = "The Shapeshifter AI document that governs this stage.", displayPriority = 1)
    @PipelinePropertyDocRef(types = ShapeshifterAiDoc.TYPE)
    public void setShapeshifterAi(final DocRef docRef) {
        this.docRef = docRef;
    }

    @Override
    protected XMLReader createReader() {
        if (docRef == null) {
            throw ProcessException.create("No Shapeshifter AI document is set on element " + getElementId());
        }
        return new SupervisorReader();
    }

    /**
     * The stream as the {@link Stage} sees it: its meta id, feed, type, attributes and content. The whole
     * stream is read: the extraction stage's replay unit is the stream (A1), and the stage learns on a
     * prefix and judges on the whole.
     */
    private Input input(final InputSource inputSource) {
        final Meta meta = metaHolder.getMeta();
        final AttributeMap attributeMap = metaDataHolder.getMetaData();
        final Map<String, String> attributes = new HashMap<>();
        if (attributeMap != null) {
            attributeMap.forEach(attributes::put);
        }
        return new Input(
                meta == null
                        ? -1L
                        : meta.getId(),
                feedHolder.getFeedName(),
                meta == null
                        ? null
                        : meta.getTypeName(),
                attributes,
                read(inputSource));
    }

    private static String read(final InputSource inputSource) {
        try {
            final Reader reader = inputSource.getCharacterStream() != null
                    ? inputSource.getCharacterStream()
                    : new InputStreamReader(inputSource.getByteStream(), inputSource.getEncoding() == null
                            ? StandardCharsets.UTF_8
                            : Charset.forName(inputSource.getEncoding()));
            final StringBuilder text = new StringBuilder();
            final char[] buffer = new char[8192];
            for (int n = reader.read(buffer); n >= 0; n = reader.read(buffer)) {
                text.append(buffer, 0, n);
            }
            return text.toString();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The fragment as a nested pipeline: its merged data with an output filter linked from its tail, the
     * tail being the one element nothing links onward from.
     */
    private Pipeline nested(final DocRef fragment) {
        final PipelineDoc fragmentDoc = pipelineStore.readDocument(fragment);
        final PipelineData merged = pipelineDataCache.get(fragmentDoc);
        final Set<String> linkedFrom = new HashSet<>();
        for (final PipelineLink link : merged.getAddedLinks()) {
            linkedFrom.add(link.getFrom());
        }
        final List<String> tails = merged.getAddedElements().stream()
                .map(PipelineElement::getId)
                .filter(id -> !linkedFrom.contains(id))
                .toList();
        if (tails.size() != 1) {
            throw ProcessException.create("Fragment " + fragment.getName() + " is not a chain: its tail is "
                                          + tails);
        }
        final PipelineData withOutput = new PipelineDataBuilder(merged)
                .addElement(new PipelineElement(OUTPUT_ELEMENT_ID, FragmentOutputFilter.TYPE))
                .addLink(tails.get(0), OUTPUT_ELEMENT_ID)
                .build();
        return pipelineFactory.create(withOutput, taskContextFactory.current());
    }

    private static String reason(final Decision decision) {
        return switch (decision) {
            case Sentinel sentinel -> sentinel.reason();
            case GivenUp givenUp -> "Shape given up: " + givenUp.reason();
            case Retracted retracted -> retracted.reason();
            case Drafted drafted -> "Awaiting review: draft rule " + drafted.rule().getUuid() + " binds "
                                    + drafted.rule().getPipeline().getName();
            default -> throw new IllegalStateException("Decision " + decision + " bound nothing and refused nothing");
        };
    }

    /**
     * Reads the stream, lets the stage decide, and either runs the bound fragment into this element's
     * targets or logs the refusal. The document is written back when the stage changed its routing
     * table; two tasks learning the same shape at once will race on that write until the learning lease
     * of A26 exists, and the loser fails on its stream rather than overwriting.
     */
    private final class SupervisorReader extends stroom.pipeline.xml.converter.AbstractParser {

        @Override
        public void parse(final InputSource inputSource) {
            final ShapeshifterAiDoc doc = store.readDocument(docRef);
            if (doc == null) {
                throw ProcessException.create("Shapeshifter AI document " + docRef + " was not found");
            }
            final Input input = input(inputSource);
            final StageRun run = stage.run(doc, input);
            if (!run.doc().equals(doc)) {
                store.writeDocument(run.doc());
            }
            final Bindings bindings = run.bindings();
            if (bindings == null) {
                errorReceiverProxy.log(Severity.ERROR, null, getElementId(),
                        "Shape " + run.shape().id() + ": " + reason(run.decision()), null);
                return;
            }
            record(bindings);
            fragmentOutput.setHandler(getContentHandler());
            final Pipeline pipeline = nested(bindings.fragment());
            try {
                // Inside the try, as the task executor has it: a nested element that fails to start has
                // still borrowed what endProcessing gives back.
                pipeline.startProcessing();
                pipeline.process(new ByteArrayInputStream(input.data().getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8.name());
            } finally {
                pipeline.endProcessing();
            }
        }

        /**
         * The output stream's attributes are one set for the whole stream, and a stream of several parts
         * is served part by part. The first part's bindings stand for the stream; a later part that bound
         * differently is reported, since the attributes cannot say so and an as-processed reprocess of it
         * would be misled (design 01 §7.3).
         */
        private void record(final Bindings bindings) {
            final String recorded = metaData.getAttributes().get(Bindings.RULE_ATTRIBUTE);
            if (recorded == null) {
                bindings.asAttributes().forEach(metaData::put);
            } else if (!recorded.equals(bindings.ruleUuid())) {
                errorReceiverProxy.log(Severity.WARNING, null, getElementId(),
                        "Part " + metaHolder.getPartIndex() + " was bound by rule " + bindings.ruleUuid()
                        + " but the stream's bindings name rule " + recorded + " from an earlier part", null);
            }
        }
    }
}
