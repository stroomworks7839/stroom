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

package stroom.shapeshifter.ai.element;

import stroom.node.api.NodeInfo;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.factory.PipelineStackLoader;
import stroom.pipeline.textconverter.TextConverterStore;
import stroom.pipeline.xslt.XsltStore;
import stroom.shapeshifter.ai.extraction.DataSplitterCompiler;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.JsonStep;
import stroom.shapeshifter.ai.fragment.FragmentRunner;
import stroom.shapeshifter.ai.fragment.FragmentWriter;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.scoring.BusinessRulesScorer;
import stroom.shapeshifter.ai.scoring.CompileScorer;
import stroom.shapeshifter.ai.scoring.ExtractionQualityScorer;
import stroom.shapeshifter.ai.scoring.InputCoverageScorer;
import stroom.shapeshifter.ai.scoring.SchemaConformanceScorer;
import stroom.shapeshifter.ai.scoring.Scorer;
import stroom.shapeshifter.ai.scoring.YieldScorer;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Ledger;
import stroom.shapeshifter.ai.stage.Outputs;
import stroom.shapeshifter.ai.stage.RegressionSet;
import stroom.shapeshifter.ai.stage.Reprocessing;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.stage.Shapes;
import stroom.shapeshifter.ai.stage.Spend;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.transformation.XsltStep;

import jakarta.inject.Inject;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/// A stage over this node's state, wherever one is wanted: in a pipeline task, where the supervisor
/// element runs it over the stream in front of it, and in deferred mode's worker (A5), which runs it
/// over an attempt that stopped. A stage holds nothing between calls, so one is as good as many; what it
/// is built from — the element runners, the scorers, the fragment writer and runner, and the runtime
/// state of A26 — is the same in either place, and a second copy of this list in the worker would be a
/// second place to forget a scorer.
///
/// Not a singleton, and not to be held: two of the things a stage runs on — the Data Splitter compiler
/// and the schema scorer — collect their diagnostics through the pipeline-scoped `ErrorReceiverProxy`,
/// so a stage belongs to one pipeline scope. The element is inside one already; the worker enters one
/// for each attempt it carries on ([DeferredLearning]).
public class StageFactory {

    private final Advisors advisors;
    private final DataSplitterCompiler dataSplitterCompiler;
    private final SchemaConformanceScorer schemaConformanceScorer;
    private final FragmentWriter fragmentWriter;
    private final PipelineStore pipelineStore;
    private final PipelineStackLoader pipelineStackLoader;
    private final TextConverterStore textConverterStore;
    private final XsltStore xsltStore;
    private final NodeInfo nodeInfo;
    private final Attempts attempts;
    private final Rules rules;
    private final Shapes shapes;
    private final Spend spend;
    private final Ledger ledger;
    private final Outputs outputs;
    private final Reprocessing reprocessing;
    private final RegressionSet regressionSet;

    @Inject
    public StageFactory(final Advisors advisors,
                        final DataSplitterCompiler dataSplitterCompiler,
                        final SchemaConformanceScorer schemaConformanceScorer,
                        final FragmentWriter fragmentWriter,
                        final PipelineStore pipelineStore,
                        final PipelineStackLoader pipelineStackLoader,
                        final TextConverterStore textConverterStore,
                        final XsltStore xsltStore,
                        final NodeInfo nodeInfo,
                        final Attempts attempts,
                        final Rules rules,
                        final Shapes shapes,
                        final Spend spend,
                        final Ledger ledger,
                        final Outputs outputs,
                        final Reprocessing reprocessing,
                        final RegressionSet regressionSet) {
        this.advisors = advisors;
        this.dataSplitterCompiler = dataSplitterCompiler;
        this.schemaConformanceScorer = schemaConformanceScorer;
        this.fragmentWriter = fragmentWriter;
        this.pipelineStore = pipelineStore;
        this.pipelineStackLoader = pipelineStackLoader;
        this.textConverterStore = textConverterStore;
        this.xsltStore = xsltStore;
        this.nodeInfo = nodeInfo;
        this.attempts = attempts;
        this.rules = rules;
        this.shapes = shapes;
        this.spend = spend;
        this.ledger = ledger;
        this.outputs = outputs;
        this.reprocessing = reprocessing;
        this.regressionSet = regressionSet;
    }

    /// A stage on this node, under the wall clock and a fresh seed for the held-out split (A14).
    public Stage create() {
        final List<StepRunner> runners = List.of(
                new DataSplitterStep(dataSplitterCompiler), new JsonStep(), new XsltStep());
        final List<Scorer> scorers = List.of(
                new CompileScorer(), new InputCoverageScorer(), new YieldScorer(), schemaConformanceScorer,
                new ExtractionQualityScorer(), new BusinessRulesScorer());
        return new Stage(
                advisors,
                runners,
                scorers,
                fragmentWriter,
                new FragmentRunner(pipelineStore, pipelineStackLoader, textConverterStore, xsltStore, runners),
                attempts,
                rules,
                shapes,
                spend,
                ledger,
                outputs,
                reprocessing,
                regressionSet,
                Clock.systemUTC(),
                ThreadLocalRandom.current().nextLong(),
                nodeInfo.getThisNodeName());
    }
}
