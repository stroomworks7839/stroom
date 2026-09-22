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
import stroom.shapeshifter.ai.ShapeshifterAiConfig;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Outputs;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/// The prune job of design 01 §12 item 8: the record of attempts that finished longer ago than the
/// document's node is told to keep them, and their turns with them. One row per attempt and one per turn
/// is the fastest-growing thing this feature writes, and a transcript nobody will ever read is a cost
/// with no reader.
///
/// An attempt still learning, still waiting for the model, or waiting for a person to decide is never
/// pruned however old it is: age is not what says an attempt is over.
@Singleton
public class AttemptRetention {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(AttemptRetention.class);

    /// The most batches one run will take, so that a night's pruning cannot run into the day's work. A
    /// batch is a thousand attempts, so a run clears a million and the next run takes what is left.
    private static final int MAX_BATCHES = 1000;

    private final Attempts attempts;
    private final Outputs outputs;
    private final Provider<ShapeshifterAiConfig> configProvider;
    private final SecurityContext securityContext;

    @Inject
    public AttemptRetention(final Attempts attempts,
                            final Outputs outputs,
                            final Provider<ShapeshifterAiConfig> configProvider,
                            final SecurityContext securityContext) {
        this.attempts = attempts;
        this.outputs = outputs;
        this.configProvider = configProvider;
        this.securityContext = securityContext;
    }

    public void exec() {
        securityContext.asProcessingUser(() -> {
            final long keepFor = configProvider.get().getAttemptRetention().toMillis();
            if (keepFor <= 0) {
                LOGGER.debug("Attempts are kept for ever: nothing is pruned");
                return;
            }
            // Until there is nothing left to forget, not once: a batch is bounded so that no single
            // delete holds locks across the table, and one batch a night would never catch a cluster
            // that makes more attempts than that in a day.
            final long before = System.currentTimeMillis() - keepFor;
            final int attemptsPruned = untilDone(() -> attempts.prune(before));
            final int outputsPruned = untilDone(() -> outputs.prune(before));
            if (attemptsPruned > 0 || outputsPruned > 0) {
                LOGGER.info(() -> LogUtil.message(
                        "Pruned {} finished attempt(s) with their turns, and {} output record(s)",
                        attemptsPruned, outputsPruned));
            }
        });
    }

    /// One batch after another until a batch comes back short, or until enough batches have been taken
    /// that the rest can wait for the next run.
    private static int untilDone(final java.util.function.IntSupplier batch) {
        int total = 0;
        for (int taken = 0; taken < MAX_BATCHES; taken++) {
            final int pruned = batch.getAsInt();
            total += pruned;
            if (pruned == 0) {
                break;
            }
        }
        return total;
    }
}
