/*
 * Copyright 2017 Crown Copyright
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
 *
 */

package stroom.search.impl;

import stroom.annotation.api.AnnotationFields;
import stroom.query.api.v2.ExpressionOperator;
import stroom.query.api.v2.Query;
import stroom.query.common.v2.Coprocessors;
import stroom.search.extraction.ExpressionFilter;
import stroom.search.extraction.ExtractionDecoratorFactory;
import stroom.search.extraction.StoredDataQueue;
import stroom.search.impl.shard.IndexShardSearchFactory;
import stroom.security.api.SecurityContext;
import stroom.task.api.TaskContext;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.SearchProgressLog;
import stroom.util.logging.SearchProgressLog.SearchPhase;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;

class ClusterSearchTaskHandler {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ClusterSearchTaskHandler.class);

    private final IndexShardSearchFactory indexShardSearchFactory;
    private final ExtractionDecoratorFactory extractionDecoratorFactory;
    private final SecurityContext securityContext;

    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong extractionCount = new AtomicLong();

    private TaskContext taskContext;

    @Inject
    ClusterSearchTaskHandler(final IndexShardSearchFactory indexShardSearchFactory,
                             final ExtractionDecoratorFactory extractionDecoratorFactory,
                             final SecurityContext securityContext) {
        this.indexShardSearchFactory = indexShardSearchFactory;
        this.extractionDecoratorFactory = extractionDecoratorFactory;
        this.securityContext = securityContext;
    }

    public void search(final TaskContext taskContext,
                       final ClusterSearchTask task,
                       final Coprocessors coprocessors) {
        SearchProgressLog.increment(SearchPhase.CLUSTER_SEARCH_TASK_HANDLER_EXEC);
        this.taskContext = taskContext;
        securityContext.useAsRead(() -> {
            if (!Thread.currentThread().isInterrupted()) {
                taskContext.info(() -> "Initialising...");
                // Start searching.
                SearchProgressLog.increment(SearchPhase.CLUSTER_SEARCH_TASK_HANDLER_SEARCH);
                taskContext.info(() -> "Searching...");
                final Query query = task.getQuery();
                LOGGER.debug(() -> "Incoming search request:\n" + query.getExpression().toString());

                try {
                    if (task.getShards().size() > 0) {

                        final StoredDataQueue storedDataQueue = extractionDecoratorFactory.create(
                                taskContext,
                                coprocessors,
                                extractionCount,
                                query);

                        // Search all index shards.
                        final ExpressionFilter expressionFilter = ExpressionFilter.builder()
                                .addPrefixExcludeFilter(AnnotationFields.ANNOTATION_FIELD_PREFIX)
                                .build();
                        final ExpressionOperator expression = expressionFilter.copy(query.getExpression());
                        indexShardSearchFactory.search(
                                task,
                                expression,
                                coprocessors.getFieldIndex(),
                                taskContext,
                                hitCount,
                                storedDataQueue,
                                coprocessors.getErrorConsumer());

                        // Wait for index search to complete.
                        LOGGER.debug("Wait for index searches");
                        while (!indexShardSearchFactory.awaitCompletion(1, TimeUnit.SECONDS)) {
                            updateInfo();
                        }

                        LOGGER.debug("Complete stored data queue");
                        storedDataQueue.onComplete();

                        // Wait for extraction to complete.
                        LOGGER.debug("Wait for extraction to complete");
                        while (!extractionDecoratorFactory.awaitCompletion(1, TimeUnit.SECONDS)) {
                            updateInfo();
                        }

                        LOGGER.debug("Finished extraction");
                    }

                    LOGGER.debug(() -> "Complete");
                } catch (final InterruptedException e) {
                    LOGGER.trace(e::getMessage, e);
                    // Keep interrupting this thread.
                    Thread.currentThread().interrupt();
                } catch (final RuntimeException e) {
                    throw SearchException.wrap(e);
                }
            }
        });
    }

    public void updateInfo() {
        taskContext.info(() -> "" +
                "Searching... " +
                "found "
                + hitCount.get() +
                " documents" +
                " performed " +
                extractionCount.get() +
                " extractions");
    }
}
