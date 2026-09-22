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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.extraction.JsonStep;
import stroom.shapeshifter.ai.extraction.NodeFixture;
import stroom.shapeshifter.ai.fragment.ContentStores;
import stroom.shapeshifter.ai.fragment.FragmentRunner;
import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.scoring.BusinessRulesScorer;
import stroom.shapeshifter.ai.scoring.CompileScorer;
import stroom.shapeshifter.ai.scoring.ExtractionQualityScorer;
import stroom.shapeshifter.ai.scoring.InputCoverageScorer;
import stroom.shapeshifter.ai.scoring.Scorer;
import stroom.shapeshifter.ai.scoring.YieldScorer;
import stroom.shapeshifter.ai.stage.DeferredWorker;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.state.InMemoryAttempts;
import stroom.shapeshifter.ai.state.InMemoryDocuments;
import stroom.shapeshifter.ai.state.InMemoryInputs;
import stroom.shapeshifter.ai.state.InMemoryLedger;
import stroom.shapeshifter.ai.state.InMemoryOutputs;
import stroom.shapeshifter.ai.state.InMemoryRegressionSet;
import stroom.shapeshifter.ai.state.InMemoryReprocessing;
import stroom.shapeshifter.ai.state.InMemoryRules;
import stroom.shapeshifter.ai.state.InMemoryShapes;
import stroom.shapeshifter.ai.state.InMemorySpend;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What every scenario shares: the corpus, the reply resources, the step runners and scorers, the
 * in-memory stores and state, and a stage built over them under a fixed clock and seed (design 02 §7).
 * One instance per scenario; the stores and state are fresh each time.
 */
public final class Scenarios {

    /**
     * Every promotion in a scenario happens at this instant, so promotion times are assertable.
     */
    public static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");
    public static final long SEED = 42L;

    private static final NodeFixture NODE = new NodeFixture();

    public final ContentStores stores = new ContentStores();
    public final InMemoryAttempts attempts = new InMemoryAttempts();
    public final InMemoryRules rules = new InMemoryRules();
    public final InMemorySpend spend = new InMemorySpend();
    public final InMemoryShapes shapes = new InMemoryShapes();
    public final InMemoryLedger ledger = new InMemoryLedger();
    public final InMemoryOutputs outputs = new InMemoryOutputs();
    public final InMemoryReprocessing reprocessing = new InMemoryReprocessing();
    public final InMemoryRegressionSet regressionSet = new InMemoryRegressionSet();
    public final InMemoryDocuments documents = new InMemoryDocuments();
    public final InMemoryInputs inputs = new InMemoryInputs();

    /// A document as a scenario means it: **inline**, so that the stage learns in front of the test. The
    /// document's own default is deferred (A5), which is right for a node — an LLM call in a processing
    /// task holds a task slot — and wrong for a scenario about what learning does rather than about when
    /// it happens. Scenario 30, which *is* about when, says deferred for itself.
    public static ShapeshifterAiDoc.Builder document() {
        return ShapeshifterAiDoc.builder().executionMode(ExecutionMode.INLINE);
    }

    public List<StepRunner> runners() {
        return List.of(new DataSplitterStep(NODE.compiler()), new JsonStep(), new XsltStep());
    }

    public List<Scorer> scorers() {
        return List.of(new CompileScorer(), new InputCoverageScorer(), new YieldScorer(),
                NODE.schemaConformanceScorer(), new ExtractionQualityScorer(), new BusinessRulesScorer());
    }


    /**
     * A script that answers the split and target questions from the configurations it will give, so the
     * scenario states only what it is about.
     */
    public Script script(final String splitter, final String stylesheet) {
        return Script.of().structure(new Structure(runners(), splitter, stylesheet));
    }

    /// A script for input that is already XML: the split question is answered with the element that is one
    /// record, the targets from the stylesheet (A35).
    public Script xmlScript(final String recordElement, final String stylesheet) {
        return Script.of().structure(Structure.ofXml(runners(), recordElement, stylesheet));
    }

    /// A script for JSON: the split question is answered with the array's key, or root, the targets from the
    /// stylesheet over the parser's XML.
    public Script jsonScript(final String arrayKey, final String stylesheet) {
        return Script.of().structure(Structure.ofJson(runners(), arrayKey, stylesheet));
    }

    /// The node a stage says it is, for the learning lease of A42: one scenario is one node unless it says
    /// otherwise, and a scenario about two nodes meeting a shape gives each its own name.
    public String node = "node-1";

    public Stage stage(final Advisor advisor) {
        return stage(advisor, rules);
    }

    /// A stage over a given rule store: for a scenario that watches when a rule is written — the moment a
    /// second node must still be excluded (A42).
    public Stage stage(final Advisor advisor, final Rules rules) {
        return stage(document -> advisor, rules);
    }

    /// A stage over given advisors: for a scenario about a node that makes a new advisor per call, each
    /// counting its own tokens, as the node's own does (A28).
    public Stage stage(final Advisors advisors, final Rules rules) {
        final List<StepRunner> runners = runners();
        return new Stage(
                advisors,
                runners,
                scorers(),
                stores.writer(),
                new FragmentRunner(stores.pipelines, stores.stackLoader, stores.textConverters, stores.xslts,
                        runners),
                attempts,
                rules,
                shapes,
                spend,
                ledger,
                outputs,
                reprocessing,
                regressionSet,
                Clock.fixed(NOW, ZoneOffset.UTC),
                SEED,
                node);
    }

    /// Deferred mode's worker over this scenario's state (A5, A28), answering with a given script: the
    /// job that carries on the attempts a deferred document parks. The stage is one of this scenario's,
    /// since a stage holds nothing of a stream between calls.
    public DeferredWorker worker(final Advisor answerer) {
        return new DeferredWorker(stage(answerer), attempts, documents, inputs, document -> answerer);
    }

    public static Golden corpus(final String stem) {
        return ExtractionCorpus.goldens().stream()
                .filter(golden -> golden.stem().equals(stem))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No golden corpus case " + stem));
    }

    public static ExtractionCorpus.Failing failingCorpus(final String stem) {
        return ExtractionCorpus.failing().stream()
                .filter(failing -> failing.stem().equals(stem))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No failing corpus case " + stem));
    }

    /**
     * A reply resource from this package: the stylesheets and expected outputs scenarios are built on.
     */
    public static String resource(final String name) {
        try (InputStream in = Scenarios.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing scenario resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The same events however they are spaced: a transform run one record at a time writes the same
    /// events as one run over the whole document (§12 item 25), and what lies between them is the
    /// serialiser's business and not the scenario's.
    public static String canonical(final String xml) {
        if (xml == null) {
            return null;
        }
        // The declaration goes, the space between elements goes, and the space inside a start tag — which
        // is where a stylesheet's own line breaks put its attributes — becomes one space.
        final String tagsNormalised = Pattern.compile("<[^>]*>")
                .matcher(xml)
                .replaceAll(match -> Matcher.quoteReplacement(match.group().replaceAll("\\s+", " ")));
        return tagsNormalised.replaceAll("<\\?xml[^>]*\\?>", "").replaceAll(">\\s+<", "><").strip();
    }

    /**
     * A model reply as the grammar of {@code ConfigurationReply} wants it: one fenced block.
     */
    public static String fenced(final String document) {
        return "Here is the configuration:\n```xml\n" + document + "\n```\n";
    }
}
