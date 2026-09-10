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
 * A key's name, and the slot the run keeps its index in (design 30 phase 7).
 *
 * <p>Keys are their own namespace — the compiler keeps a separate declared set for them, and a
 * key and a variable may share a name without meaning the same thing — so this is a separate type
 * from {@link VarName} rather than the same one used twice. Two slot numbers that index different
 * arrays should not be the same type; getting them the wrong way round would read a real index
 * and answer confidently.
 *
 * @param name the name as authored
 * @param slot its index in the run's array of key indexes, dense from zero
 */
public record KeyName(String name, int slot) {

    @Override
    public String toString() {
        return name;
    }
}
