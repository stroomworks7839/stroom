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

package stroom.shapeshifter.pipeline;

import java.util.HashMap;
import java.util.Map;

/**
 * What {@code put} writes and {@code get} reads (design 26 phase 3): one map per element
 * instance, which is one per pipeline instance, as Stroom's pipeline-scoped {@code TaskScopeMap}
 * is — so a value put while one stream is processed is still there for the next.
 */
public final class PipelineState {

    private final Map<String, String> values = new HashMap<>();

    public Map<String, String> values() {
        return values;
    }
}
