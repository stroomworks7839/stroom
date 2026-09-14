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

import stroom.shapeshifter.engine.config.Declaration;

import java.util.Map;

/**
 * Every name a configuration uses, each with its slot (design 30 phases 5 and 7).
 *
 * <p>One namespace (design 35 §4): a key is a declared map, and a sequence a declared list,
 * so what used to be three slot spaces is one table with a type per name.
 *
 * <p><b>This is a table, not a table-builder.</b> Assigning slots is compilation, and it lives in
 * {@code compile.Interner}; what a run holds is the finished thing. That separation is why there
 * is no {@code freeze}: this used to be the interner as well, guarded by a flag that refused a
 * late intern, and a flag guarding a mutation is worth less than not having the mutation. A
 * compiled project outlives the runs that use it (D35) and each sizes its slot arrays from these
 * counts, so a name added afterwards would index past the end of every one of them — which is now
 * unrepresentable rather than refused.
 *
 * <p><b>Nothing consults the map at run time</b> (design 35 §8). The one site that used to — a
 * key-value capture reading a variable's name out of the data — puts into a declared map
 * instead, so every name a run touches was resolved to a slot when the configuration compiled,
 * and the slot array is sized once from here and never grows.
 *
 * @param all   every variable name, by the name an author writes
 * @param types what each declared name holds (design 35 §5), by name; a name declared in
 *              place — a parameter, a loop's {@code as} — is absent here and holds a scalar
 */
public record Names(Map<String, VarName> all, Map<String, Declaration.Type> types) {

    public Names {
        types = Map.copyOf(types);
        all = Map.copyOf(all);
    }


    /** What a name was declared to hold: its declaration's type, or a scalar for a name declared in place. */
    public Declaration.Type typeOf(final VarName name) {
        final Declaration.Type declared = types.get(name.name());
        return declared == null ? Declaration.Type.SCALAR : declared;
    }

    /** How many slots a run needs, which is fixed: nothing arrives from the data (design 35 §8). */
    public int size() {
        return all.size();
    }

}
