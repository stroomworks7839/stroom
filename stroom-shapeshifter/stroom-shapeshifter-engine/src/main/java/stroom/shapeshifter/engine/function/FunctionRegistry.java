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

package stroom.shapeshifter.engine.function;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The functions a configuration may call, by name: a compile-time input (design 26 §3).
 * Immutable; a duplicate name is refused when it is built, since two definitions of one name
 * would make a configuration mean different things in different processes.
 */
public final class FunctionRegistry {

    public static final FunctionRegistry EMPTY = new FunctionRegistry(List.of());

    private final Map<String, FunctionDefinition> byName;

    private FunctionRegistry(final Collection<FunctionDefinition> definitions) {
        final Map<String, FunctionDefinition> map = new LinkedHashMap<>();
        for (final FunctionDefinition definition : definitions) {
            final FunctionDefinition previous = map.putIfAbsent(definition.name(), definition);
            if (previous != null) {
                throw new IllegalArgumentException("Function '" + definition.name() + "' is registered twice");
            }
        }
        this.byName = Map.copyOf(map);
    }

    public static FunctionRegistry of(final FunctionDefinition... definitions) {
        return new FunctionRegistry(List.of(definitions));
    }

    public static FunctionRegistry of(final Collection<FunctionDefinition> definitions) {
        return new FunctionRegistry(definitions);
    }

    /** The definition of a name, or null. */
    public FunctionDefinition lookup(final String name) {
        return byName.get(name);
    }

    public Collection<FunctionDefinition> definitions() {
        return byName.values();
    }

    public int size() {
        return byName.size();
    }
}
