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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.function.FunctionRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a compile knows about functions: the registry it resolves names in, and the definitions
 * the configuration turned out to use — what the run binds (design 26 §3). Resolving a name
 * records the use, so this is the one holder of that state.
 */
final class Functions {

    private final FunctionRegistry registry;
    private final Map<String, FunctionDefinition> used = new LinkedHashMap<>();

    Functions(final FunctionRegistry registry) {
        this.registry = registry;
    }

    /** The definition a name resolves to, remembered as used; an unknown name is refused by name. */
    FunctionDefinition resolve(final String name) {
        final FunctionDefinition definition = registry.lookup(name);
        if (definition == null) {
            throw new ConfigException("Unknown function: '" + name + "'"
                                      + (registry.size() == 0 ? " (no functions are registered)" : ""));
        }
        used.putIfAbsent(definition.name(), definition);
        return definition;
    }

    /** The definitions the configuration uses, in first-use order. */
    List<FunctionDefinition> used() {
        return List.copyOf(used.values());
    }
}
