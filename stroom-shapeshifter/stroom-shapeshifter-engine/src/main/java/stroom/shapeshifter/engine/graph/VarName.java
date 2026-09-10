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

package stroom.shapeshifter.engine.graph;

/**
 * A variable's name, and the slot the run keeps it in (design 30 phase 5).
 *
 * <p>Every name a configuration uses is known when it compiles, so a compiled node can hold
 * <em>which</em> variable it means rather than a string to hash. The registry becomes an array
 * indexed by {@link #slot()}, and a read is an array access.
 *
 * <p>The name travels with the slot because everything that reports — messages, instrumentation,
 * a diagnostic dump — wants it, and because a slot on its own is unreadable in a stack trace.
 * Nothing at run time compares it.
 *
 * @param name the name as authored, without the sigil
 * @param slot its index in the run's variable array, dense from zero
 */
public record VarName(String name, int slot) {

    @Override
    public String toString() {
        return name;
    }
}
