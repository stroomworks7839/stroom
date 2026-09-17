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

package stroom.shapeshifter.config;

import java.util.List;

/**
 * A variable, declared: its name, and what it holds (design 35 §4, §5).
 *
 * <p>Where a declaration is written is its scope. On the source template — outside every other
 * template — it lasts the run; on any other template it lives for that template's execution and is
 * visible to everything that executes within it. Assignment happens deeper, in the templates the
 * declaring one dispatches into, which is how a record template reads what its field templates
 * captured. The type is part of the declaration, not inferred from first use, so that a refusal
 * can point at one place and the run-time shape is settled before a slot is allocated.
 *
 * @param name the variable's name, declared once per configuration
 * @param type what it holds
 */
public record Declaration(String name, Type type, List<Entry> entries) {

    public Declaration(final String name, final Type type) {
        this(name, type, List.of());
    }

    public Declaration {
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (type != Type.MAP && !entries.isEmpty()) {
            throw new ConfigException("Declaration '" + name + "' has entries, which only a map"
                    + " declaration takes");
        }
        if (name == null || name.isEmpty()) {
            throw new ConfigException("A declaration needs a name");
        }
        if (type == null) {
            throw new ConfigException("Declaration '" + name + "' needs a type: scalar, list, map or set");
        }
    }

    /**
     * One initial entry of a map declared with a table — what {@code value-map} used to be
     * (design 35 §5): a map declared with initial entries, read with {@code get} and a default.
     */
    public record Entry(String from, String to) {

        public Entry {
            if (from == null || to == null) {
                throw new ConfigException("A map entry needs a from and a to");
            }
        }
    }

    /** What a declared variable holds. One element type, so a collection needs no parameter (design 35 §5). */
    public enum Type {
        SCALAR,
        LIST,
        MAP,
        SET
    }
}
