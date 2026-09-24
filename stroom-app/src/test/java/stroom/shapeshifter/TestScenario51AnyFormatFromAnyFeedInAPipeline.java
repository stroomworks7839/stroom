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
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.xml.converter.ds3.DS3ParserFactory;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.api.ProcessorResult;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.ProcessorFilter;
import stroom.processor.shared.QueryData;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.extraction.DataSplitterCompiler;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.extraction.JsonStep;
import stroom.shapeshifter.ai.extraction.XmlFragmentStep;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.pack.DemoContentPack;
import stroom.shapeshifter.ai.pack.DemoDocument;
import stroom.shapeshifter.ai.scenario.AdvisorHolder;
import stroom.shapeshifter.ai.scenario.QuestionMatcher;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scenario.Script;
import stroom.shapeshifter.ai.scenario.Structure;
import stroom.shapeshifter.ai.stage.Bindings;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
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
 * Design 02 §5, scenario 51 at Tier 2: <strong>one document, one pipeline, any format, any feed</strong>,
 * processed as processor tasks against real feeds and real streams.
 * <p>
 * Tier 1 proves the stage reaches the right decisions. What only this tier can claim is that the whole
 * arrangement works: one pipeline, whose supervised element is attached to one document, meeting four
 * feeds of four unrelated formats in turn — delimited text with a header, JSON lines, syslog in two
 * forms, and fixed-width columns with nothing to split on. Each is learned, each writes a fragment that
 * is a real pipeline document, each produces an events stream equal to its format's golden, and each
 * output carries the bindings that say what produced it.
 * <p>
 * This is the demo, run. The document, the pipeline and the filter are {@link DemoContentPack}'s own, so
 * what is proved here is the thing the content pack ships rather than a rehearsal of it. Only the model
 * is different: the advisor is scripted, as every Tier 2 scenario's is, and what a model chooses from
 * the list this document allows is the live run's to answer.
 */
class TestScenario51AnyFormatFromAnyFeedInAPipeline extends AbstractProcessIntegrationTest {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String CSV_XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String CSV_EVENTS = Scenarios.resource("csv-logon.events.xml");

    private static final String JSON_LINES = Scenarios.resource("records.jsonl");
    private static final String JSON_XSLT = Scenarios.resource("records.xsl");
    private static final String JSON_EVENTS = Scenarios.resource("records.events.xml");

    private static final String SYSLOG = Scenarios.resource("syslog.log");
    private static final String SYSLOG_DS3 = Scenarios.resource("syslog.ds3.xml");
    private static final String SYSLOG_XSLT = Scenarios.resource("syslog.xsl");
    private static final String SYSLOG_EVENTS = Scenarios.resource("syslog.events.xml");

    private static final String FIXED = Scenarios.resource("fixed-width.log");
    private static final String FIXED_DS3 = Scenarios.resource("fixed-width.ds3.xml");
    private static final String FIXED_XSLT = Scenarios.resource("fixed-width.xsl");
    private static final String FIXED_EVENTS = Scenarios.resource("fixed-width.events.xml");

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
    private Rules rules;
    @Inject
    private MockMetaService metaService;
    @Inject
    private MockStore streamStore;
    @Inject
    private AdvisorHolder advisor;
    @Inject
    private Provider<DS3ParserFactory> parserFactories;

    private DocRef document;

    @Test
    void oneDocumentAndOnePipelineLearnEveryFormatTheyAreShown() {
        DemoContentPack.FEEDS.forEach(feed -> storeCreationTool.getOrCreateFeedDoc(feed));
        document = document();
        filter(pipeline(document));

        // Delimited text with a header row.
        learn(DemoContentPack.FEEDS.get(0), CSV.input(), CSV_EVENTS,
                script(CSV.configuration(), CSV_XSLT)
                        .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                        .expect(QuestionMatcher.configuration("DSParser"))
                        .reply(Scenarios.fenced(CSV.configuration()))
                        .expect(QuestionMatcher.configuration("XSLTFilter"))
                        .reply(Scenarios.fenced(CSV_XSLT)));

        // JSON, one object per line, and no configuration question for the parser.
        learn(DemoContentPack.FEEDS.get(1), JSON_LINES, JSON_EVENTS,
                jsonScript("root", JSON_XSLT)
                        .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                        .expect(QuestionMatcher.configuration("XSLTFilter"))
                        .reply(Scenarios.fenced(JSON_XSLT)));

        // Syslog, RFC 3164 and RFC 5424 in one stream.
        learn(DemoContentPack.FEEDS.get(2), SYSLOG, SYSLOG_EVENTS,
                script(SYSLOG_DS3, SYSLOG_XSLT)
                        .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                        .expect(QuestionMatcher.configuration("DSParser"))
                        .reply(Scenarios.fenced(SYSLOG_DS3))
                        .expect(QuestionMatcher.configuration("XSLTFilter"))
                        .reply(Scenarios.fenced(SYSLOG_XSLT)));

        // Fixed-width columns, with no delimiter to split on.
        learn(DemoContentPack.FEEDS.get(3), FIXED, FIXED_EVENTS,
                script(FIXED_DS3, FIXED_XSLT)
                        .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                        .expect(QuestionMatcher.configuration("DSParser"))
                        .reply(Scenarios.fenced(FIXED_DS3))
                        .expect(QuestionMatcher.configuration("XSLTFilter"))
                        .reply(Scenarios.fenced(FIXED_XSLT)));

        // One document, one table, a rule per feed — and every rule bound to a fragment that is a real
        // pipeline document in the store.
        final List<RoutingRule> table = rules.forDocument(document.getUuid());
        assertThat(table).hasSize(DemoContentPack.FEEDS.size());
        assertThat(table).extracting(RoutingRule::getShapeId)
                .containsExactlyInAnyOrderElementsOf(DemoContentPack.FEEDS.stream()
                        .map(feed -> "Feed=" + feed + "|Type=" + StreamTypeNames.RAW_EVENTS)
                        .toList());
        assertThat(table).allSatisfy(rule -> {
            assertThat(rule.isDraft()).isFalse();
            assertThat(rule.isProvisional())
                    .describedAs("each brought enough records to be judged on, so none is provisional")
                    .isFalse();
            assertThat(pipelineStore.readDocument(rule.getPipeline()))
                    .describedAs("what it learned is a pipeline anyone can open")
                    .isNotNull();
        });
        assertThat(shapeshifterAiStore.readDocument(document).getPlan())
                .describedAs("A41: learning writes rules, not documents — the demo document is untouched")
                .isEqualTo(DemoContentPack.document().getPlan());

        // And a second stream of a format already learned is served by its rule without the model being
        // asked. The one that matters here is JSON, since it is the only format whose parser the demo
        // configures nothing for.
        final Script silent = Script.of();
        advisor.set(silent);
        rawStream(DemoContentPack.FEEDS.get(1), JSON_LINES);
        final ProcessorResult served = processOne();

        assertThat(silent.asked()).describedAs("nothing was asked: the rule serves it").isEmpty();
        assertThat(served.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(canonical(data(outputs().get(outputs().size() - 1))))
                .isEqualTo(canonical(JSON_EVENTS));
        assertThat(rules.forDocument(document.getUuid()))
                .describedAs("and no second rule was written for a shape already bound")
                .hasSize(DemoContentPack.FEEDS.size());
    }

    /**
     * One feed's stream, learned and translated, checked against that format's golden events.
     */
    private void learn(final String feed, final String data, final String expected, final Script script) {
        advisor.set(script);
        rawStream(feed, data);
        final ProcessorResult result = processOne();
        script.verifyExhausted();

        assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR))
                .describedAs(feed + " was processed without error")
                .isZero();
        final Meta output = outputs().get(outputs().size() - 1);
        assertThat(canonical(data(output)))
                .describedAs(feed + " produced its format's golden events")
                .isEqualTo(canonical(expected));
        assertThat(streamStore.getAttributes(output.getId()))
                .describedAs("design 01 §7.3 rule 3: the output carries what produced it")
                .containsEntry(Bindings.DOC_ATTRIBUTE, document.getUuid())
                .containsEntry(Bindings.PROVISIONAL_ATTRIBUTE, "false");
    }

    /**
     * The pack's document, built from what the pack ships. The model is left off: this run's model is the
     * script.
     */
    private DocRef document() {
        final ShapeshifterAiDoc shipped = DemoContentPack.document();
        final DocRef docRef = shapeshifterAiStore.createDocument(shipped.getName());
        shapeshifterAiStore.writeDocument(DemoDocument
                .configure(shapeshifterAiStore.readDocument(docRef).copy())
                .description(shipped.getDescription())
                .executionMode(shipped.getExecutionMode())
                .build());
        return docRef;
    }

    private DocRef pipeline(final DocRef document) {
        final DocRef pipelineRef = pipelineStore.createDocument(DemoContentPack.PIPELINE_NAME);
        final PipelineDoc pipeline = pipelineStore.readDocument(pipelineRef);
        pipelineStore.writeDocument(pipeline.copy()
                .pipelineData(DemoContentPack.pipelineData(document))
                .build());
        return pipelineRef;
    }

    /**
     * The pack's own filter over all four feeds, with this installation's pipeline in place of the uuid
     * the pack names.
     */
    private void filter(final DocRef pipeline) {
        final ProcessorFilter shipped = DemoContentPack.filter();
        final QueryData queryData = shipped.getQueryData().copy()
                .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                .build();
        processorFilterService.create(CreateProcessFilterRequest.builder()
                .pipeline(pipeline)
                .queryData(queryData)
                .priority(shipped.getPriority())
                .build());
    }

    /**
     * The split and target questions are answered from the configurations the script will give, as the
     * module's scenarios do; each script states only what its format is about. The runners are built when
     * a question is asked, inside the processing pipeline's scope, so they reach the node's parser
     * factory the way the element does.
     */
    private List<StepRunner> runners() {
        final DataSplitterCompiler compiler = new DataSplitterCompiler(parserFactories, new ErrorReceiverProxy());
        return List.of(new DataSplitterStep(compiler), new JsonStep(), new XmlFragmentStep(), new XsltStep());
    }

    private Script script(final String splitter, final String stylesheet) {
        return Script.of().structure(new Structure(runners(), splitter, stylesheet));
    }

    private Script jsonScript(final String arrayKey, final String stylesheet) {
        return Script.of().structure(Structure.ofJson(runners(), arrayKey, stylesheet));
    }

    private void rawStream(final String feed, final String data) {
        try {
            final Path file = Files.createTempFile(getCurrentTestDir(), "demo", ".txt");
            Files.writeString(file, data, StandardCharsets.UTF_8);
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
        final Map<String, byte[]> files = streamStore.getFileData().get(meta.getId());
        return new String(files.get(meta.getTypeName()), StandardCharsets.UTF_8);
    }

    private static String canonical(final String xml) {
        return xml.replaceAll("<\\?xml[^>]*\\?>", "").replaceAll(">\\s+<", "><").strip();
    }
}
