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
 */

package stroom.search.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stroom.dashboard.expression.v1.FieldIndexMap;
import stroom.pipeline.server.errorhandler.ErrorReceiver;
import stroom.query.api.v2.DocRef;
import stroom.query.common.v2.Coprocessor;
import stroom.search.extraction.ExtractionTask.ResultReceiver;
import stroom.security.SecurityContext;
import stroom.security.SecurityHelper;
import stroom.task.server.ExecutorProvider;
import stroom.task.server.ThreadPoolImpl;
import stroom.util.shared.HasTerminate;
import stroom.util.shared.Severity;
import stroom.util.shared.ThreadPool;
import stroom.util.task.taskqueue.TaskExecutor;
import stroom.util.task.taskqueue.TaskProducer;

import javax.inject.Provider;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ExtractionTaskProducer extends TaskProducer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractionTaskProducer.class);
    private static final ThreadPool THREAD_POOL = new ThreadPoolImpl(
            "Extraction",
            5,
            0,
            Integer.MAX_VALUE);

    private final HasTerminate clusterSearchTask;
    private final FieldIndexMap extractionFieldIndexMap;
    private final Map<DocRef, Set<Coprocessor>> extractionCoprocessorsMap;
    private final ErrorReceiver errorReceiver;
    private final Provider<ExtractionTaskHandler> handlerProvider;
    private final Queue<ExtractionRunnable> taskQueue = new ConcurrentLinkedQueue<>();

    private final CompletionStatus streamMapCreatorCompletionStatus = new CompletionStatus("Stream Map Creator");
    private final AtomicLong streamMapCountStatistic = streamMapCreatorCompletionStatus.getStatistic("count");
    private final CompletionStatus completionStatus = new CompletionStatus("Extraction Tasks");
    private final AtomicLong tasksCreatedStatistic = completionStatus.getStatistic("tasksCreated");
    private final AtomicLong tasksCompletedStatistic = completionStatus.getStatistic("tasksCompleted");
    private final AtomicLong successfulExtractions = completionStatus.getStatistic("successfulExtractions");
    private final AtomicLong failedExtractions = completionStatus.getStatistic("failedExtractions");
    private final Map<Long, List<Event>> streamEventMap = new ConcurrentHashMap<>();

    public ExtractionTaskProducer(final TaskExecutor taskExecutor,
                                  final HasTerminate clusterSearchTask,
                                  final StreamMapCreator streamMapCreator,
                                  final LinkedBlockingQueue<Values> storedData,
                                  final FieldIndexMap extractionFieldIndexMap,
                                  final Map<DocRef, Set<Coprocessor>> extractionCoprocessorsMap,
                                  final ErrorReceiver errorReceiver,
                                  final int maxThreadsPerTask,
                                  final ExecutorProvider executorProvider,
                                  final Provider<ExtractionTaskHandler> handlerProvider,
                                  final CompletionStatus parentCompletionStatus,
                                  final SecurityContext securityContext) {
        super(taskExecutor, maxThreadsPerTask, executorProvider.getExecutor(THREAD_POOL));
        this.clusterSearchTask = clusterSearchTask;
        this.extractionFieldIndexMap = extractionFieldIndexMap;
        this.extractionCoprocessorsMap = extractionCoprocessorsMap;
        this.errorReceiver = errorReceiver;
        this.handlerProvider = handlerProvider;

        parentCompletionStatus.addChild(streamMapCreatorCompletionStatus);
        parentCompletionStatus.addChild(completionStatus);

        // Start mapping streams.
        final Executor executor = executorProvider.getExecutor(THREAD_POOL);
        CompletableFuture.runAsync(() -> {
            LOGGER.debug("Starting extraction task producer");

            // Elevate permissions so users with only `Use` feed permission can `Read` streams.
            try (final SecurityHelper securityHelper = SecurityHelper.elevate(securityContext)) {
                while (!streamMapCreatorCompletionStatus.isComplete() && !clusterSearchTask.isTerminated()) {
                    try {
                        // Poll for the next set of values.
                        final Values values = storedData.poll(1, TimeUnit.SECONDS);
                        if (values != null) {
                            if (values.complete()) {
                                // If we did not get any values then there are no more to get if the search task producer is complete.
                                streamMapCreatorCompletionStatus.complete();
                            } else {
                                // If we have some values then map them.
                                streamMapCreator.addEvent(streamEventMap, values.getValues());
                                streamMapCountStatistic.incrementAndGet();

                                // Tell the supplied executor that we are ready to deliver tasks.
                                signalAvailable();
                            }
                        }
                    } catch (final InterruptedException e) {
                        LOGGER.debug(e.getMessage(), e);
                        streamMapCreatorCompletionStatus.complete();
                    } catch (final Exception e) {
                        LOGGER.error(e.getMessage(), e);
                        streamMapCreatorCompletionStatus.complete();
                    }
                }

                // Clear the event map if we have terminated so that other processing does not occur.
                if (clusterSearchTask.isTerminated()) {
                    streamEventMap.clear();
                }

            } catch (final Throwable t) {
                error(t.getMessage(), t);
            } finally {
                streamMapCreatorCompletionStatus.complete();

                // Tell the supplied executor that we are ready to deliver final tasks.
                signalAvailable();
            }
        }, executor);

        // Attach to the supplied executor.
        attach();

        // Tell the supplied executor that we are ready to deliver tasks.
        signalAvailable();
    }

    protected boolean isComplete() {
        return clusterSearchTask.isTerminated() || super.isComplete();
    }

    @Override
    protected Runnable getNext() {
        ExtractionRunnable task = null;

        if (!clusterSearchTask.isTerminated()) {
            task = taskQueue.poll();
            if (task == null) {
                if (addTasks()) {
                    finishedAddingTasks();
                }
                task = taskQueue.poll();
            }
        }

        checkCompletion();

        return task;
    }

    @Override
    protected void incrementTasksCompleted() {
        super.incrementTasksCompleted();
        tasksCompletedStatistic.incrementAndGet();
        checkCompletion();
    }

    private void checkCompletion() {
        if (isComplete()) {
            // Set complete if not already done.
            if (completionStatus.complete()) {
                // Send terminal values.
                // TODO : @66 Change coprocessors to accept terminal values.
//            putValues(new Values(null));
            }
        }
    }

    private boolean addTasks() {
        final boolean completedEventMapping = this.streamMapCreatorCompletionStatus.isComplete();
        for (final Entry<Long, List<Event>> entry : streamEventMap.entrySet()) {
            if (streamEventMap.remove(entry.getKey(), entry.getValue())) {
                final int tasksCreated = createTasks(entry.getKey(), entry.getValue());
                if (tasksCreated > 0) {
                    return false;
                }
            }
        }
        return completedEventMapping;
    }

    private int createTasks(final long streamId, final List<Event> events) {
        int tasksCreated = 0;

        long[] eventIds = null;

        for (final Entry<DocRef, Set<Coprocessor>> entry : extractionCoprocessorsMap.entrySet()) {
            final DocRef pipelineRef = entry.getKey();
            final Set<Coprocessor> coprocessors = entry.getValue();

            if (pipelineRef != null) {
                // This set of coprocessors require result extraction so invoke the extraction service.
                final ResultReceiver resultReceiver = values -> {
                    if (!values.complete()) {
                        for (final Coprocessor coprocessor : coprocessors) {
                            try {
                                coprocessor.receive(values.getValues());
                            } catch (final Exception e) {
                                error(e.getMessage(), e);
                            }
                        }
                    }
                    ;
                };

                if (eventIds == null) {
                    // Get a list of the event ids we are extracting for this stream and sort them.
                    eventIds = new long[events.size()];
                    for (int i = 0; i < eventIds.length; i++) {
                        eventIds[i] = events.get(i).getId();
                    }
                    // Sort the ids as the extraction expects them in order.
                    Arrays.sort(eventIds);
                }

                incrementTasksTotal();
                final ExtractionTask task = new ExtractionTask(streamId, eventIds, pipelineRef, extractionFieldIndexMap, resultReceiver, errorReceiver, successfulExtractions, failedExtractions);
                taskQueue.offer(new ExtractionRunnable(task, handlerProvider));
                tasksCreated++;
                tasksCreatedStatistic.incrementAndGet();

            } else {
                // Pass raw values to coprocessors that are not requesting values to be extracted.
                for (final Coprocessor coprocessor : coprocessors) {
                    for (final Event event : events) {
                        coprocessor.receive(event.getValues());
                    }
                }
            }
        }

        return tasksCreated;
    }

    private void error(final String message, final Throwable t) {
        errorReceiver.log(Severity.ERROR, null, null, message, t);
    }

    private static class ExtractionRunnable implements Runnable {
        private final ExtractionTask task;
        private final Provider<ExtractionTaskHandler> handlerProvider;

        ExtractionRunnable(final ExtractionTask task, final Provider<ExtractionTaskHandler> handlerProvider) {
            this.task = task;
            this.handlerProvider = handlerProvider;
        }

        @Override
        public void run() {
            final ExtractionTaskHandler handler = handlerProvider.get();
            handler.exec(task);
        }

        public ExtractionTask getTask() {
            return task;
        }
    }
}
