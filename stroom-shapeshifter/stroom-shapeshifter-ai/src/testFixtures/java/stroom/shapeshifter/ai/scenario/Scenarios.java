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
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.scoring.BusinessRulesScorer;
import stroom.shapeshifter.ai.scoring.CompileScorer;
import stroom.shapeshifter.ai.scoring.ExtractionQualityScorer;
import stroom.shapeshifter.ai.scoring.InputCoverageScorer;
import stroom.shapeshifter.ai.scoring.Scorer;
import stroom.shapeshifter.ai.scoring.YieldScorer;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.state.InMemoryLedger;
import stroom.shapeshifter.ai.state.InMemoryOutputs;
import stroom.shapeshifter.ai.state.InMemoryRegressionSet;
import stroom.shapeshifter.ai.state.InMemoryReprocessing;
import stroom.shapeshifter.ai.state.InMemoryShapes;
import stroom.shapeshifter.ai.transformation.XsltStep;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

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
    public final InMemoryShapes shapes = new InMemoryShapes();
    public final InMemoryLedger ledger = new InMemoryLedger();
    public final InMemoryOutputs outputs = new InMemoryOutputs();
    public final InMemoryReprocessing reprocessing = new InMemoryReprocessing();
    public final InMemoryRegressionSet regressionSet = new InMemoryRegressionSet();

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

    public Stage stage(final Advisor advisor) {
        final List<StepRunner> runners = runners();
        return new Stage(
                doc -> advisor,
                runners,
                scorers(),
                stores.writer(),
                new FragmentRunner(stores.pipelines, stores.stackLoader, stores.textConverters, stores.xslts,
                        runners),
                shapes,
                ledger,
                outputs,
                reprocessing,
                regressionSet,
                Clock.fixed(NOW, ZoneOffset.UTC),
                SEED);
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

    /**
     * A model reply as the grammar of {@code ConfigurationReply} wants it: one fenced block.
     */
    public static String fenced(final String document) {
        return "Here is the configuration:\n```xml\n" + document + "\n```\n";
    }
}
