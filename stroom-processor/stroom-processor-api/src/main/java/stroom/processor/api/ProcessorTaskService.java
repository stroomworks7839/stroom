/*
 * Copyright 2016 Crown Copyright
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

package stroom.processor.api;

import stroom.entity.shared.ExpressionCriteria;
import stroom.processor.shared.ProcessorTask;
import stroom.processor.shared.ProcessorTaskSummary;
import stroom.util.shared.ResultPage;

import java.util.Collection;

public interface ProcessorTaskService {

    ResultPage<ProcessorTask> find(final ExpressionCriteria criteria);

    ResultPage<ProcessorTaskSummary> findSummary(final ExpressionCriteria criteria);

    /// Wake the deferred tasks of a filter for the given streams: back to `CREATED` ahead of the retry
    /// delay, because what they were waiting for has happened (design 04 §5.3).
    ///
    /// @param filterId The filter the tasks belong to.
    /// @param metaIds  The streams whose tasks are to be woken.
    /// @return How many were woken.
    int wakeDeferredTasks(int filterId, Collection<Long> metaIds);
}
