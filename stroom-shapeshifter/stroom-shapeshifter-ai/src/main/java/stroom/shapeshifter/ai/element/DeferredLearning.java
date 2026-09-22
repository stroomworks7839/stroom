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

import stroom.security.api.SecurityContext;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.DeferredWorker;
import stroom.shapeshifter.ai.stage.Documents;
import stroom.shapeshifter.ai.stage.Inputs;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.pipeline.scope.PipelineScopeRunnable;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/// Deferred mode's worker as a node runs it (A5, A28): the job behind *Shapeshifter AI Deferred
/// Learning*. A deferred document asks the model nothing in the processing task — the attempt is opened
/// and parked at its first question — and this is what carries those attempts on, at whatever pace the
/// model answers and without a task slot held while it does.
///
/// As the processing user, since it reads streams and documents on nobody's behalf, and writes
/// fragments and rules as an automatic promotion does.
///
/// A pass takes a bounded batch rather than everything waiting: a job that tried to empty the queue
/// would hold one thread for as long as the model took times the length of the queue, and the next pass
/// takes what is left. The claim of A45 is what keeps two nodes' passes off one attempt.
@Singleton
public class DeferredLearning {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(DeferredLearning.class);

    /// How many attempts one pass carries on. One attempt is one dialogue — minutes of model time at
    /// worst — so a pass is bounded and the schedule decides the rate.
    private static final int BATCH = 10;

    private final Provider<StageFactory> stageFactory;
    private final PipelineScopeRunnable pipelineScope;
    private final Attempts attempts;
    private final Documents documents;
    private final Inputs inputs;
    private final Advisors advisors;
    private final SecurityContext securityContext;

    @Inject
    public DeferredLearning(final Provider<StageFactory> stageFactory,
                            final PipelineScopeRunnable pipelineScope,
                            final Attempts attempts,
                            final Documents documents,
                            final Inputs inputs,
                            final Advisors advisors,
                            final SecurityContext securityContext) {
        this.stageFactory = stageFactory;
        this.pipelineScope = pipelineScope;
        this.attempts = attempts;
        this.documents = documents;
        this.inputs = inputs;
        this.advisors = advisors;
        this.securityContext = securityContext;
    }

    public void exec() {
        securityContext.asProcessingUser(() -> pipelineScope.scopeRunnable(() -> {
            // In a pipeline scope, though there is no pipeline: what a stage runs on — the Data Splitter
            // compiler, the schema scorer — reports through the scoped error receiver, and a candidate
            // configuration is compiled and run here exactly as it is in a task.
            final DeferredWorker worker = new DeferredWorker(stageFactory.get().create(), attempts, documents,
                    inputs, advisors);
            final int carried = worker.advance(BATCH);
            if (carried > 0) {
                LOGGER.info(() -> LogUtil.message("Carried on {} attempt(s) awaiting the model", carried));
            }
        }));
    }
}
