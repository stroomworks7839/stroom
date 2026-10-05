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

import stroom.processor.api.ProcessorTaskService;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Wake;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/// The wake in a node (design 04 §5.3): the deferred tasks of each filter, for the streams waiting, back
/// to `CREATED` through the processor task service. Rows with no task are streams nothing is processing
/// — a stream put back by hand through the stepper has none — and are left where they are.
@Singleton
public class TaskWake implements Wake {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TaskWake.class);

    private final ProcessorTaskService processorTaskService;

    @Inject
    public TaskWake(final ProcessorTaskService processorTaskService) {
        this.processorTaskService = processorTaskService;
    }

    @Override
    public int wake(final List<Deferral> waiting) {
        final Map<Integer, List<Long>> byFilter = waiting.stream()
                .filter(row -> row.filterId() != null && row.taskId() != null)
                .collect(Collectors.groupingBy(Deferral::filterId,
                        Collectors.mapping(Deferral::inputId, Collectors.toList())));
        int woken = 0;
        for (final Map.Entry<Integer, List<Long>> filter : byFilter.entrySet()) {
            woken += processorTaskService.wakeDeferredTasks(filter.getKey(), filter.getValue());
        }
        final int count = woken;
        LOGGER.debug(() -> LogUtil.message("Woke {} task(s) for {} waiting stream(s)", count, waiting.size()));
        return woken;
    }
}
