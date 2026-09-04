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

import stroom.pipeline.shared.data.PipelineReference;
import stroom.shapeshifter.engine.function.Services;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What an element makes reachable to a document's functions (design 26 §5): the holders and
 * services Stroom's own XSLT functions are injected with, looked up by type, gathered by
 * {@link ShapeshifterServices#forElement}. A service the element does not have — a provider
 * that is null, outside a pipeline — is simply absent, and a function that needs it says so.
 */
public final class ElementServices implements Services {

    private final Map<Class<?>, Object> byType = new HashMap<>();

    ElementServices() {
    }

    void put(final Class<?> type, final Object service) {
        if (service != null) {
            byType.put(type, service);
        }
    }

    @Override
    public Object lookup(final Class<?> type) {
        return byType.get(type);
    }

    /** The element's {@code pipelineReference} properties, for the lookups (design 26 phase 4). */
    public record PipelineReferences(List<PipelineReference> references) {

    }
}
