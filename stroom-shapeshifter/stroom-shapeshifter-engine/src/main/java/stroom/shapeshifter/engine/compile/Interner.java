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

import stroom.shapeshifter.engine.config.Declaration;
import stroom.shapeshifter.engine.config.EngineVars;
import stroom.shapeshifter.engine.graph.KeyName;
import stroom.shapeshifter.engine.graph.Names;
import stroom.shapeshifter.engine.graph.VarName;

import java.util.HashMap;
import java.util.Map;

/**
 * Assigning a slot to every name a configuration uses (design 30 phases 5 and 7).
 *
 * <p>Names are interned as the graph is built rather than by a walk of their own: whatever
 * compiles a node that names something asks for the {@link VarName} or {@link KeyName}, and the
 * first ask assigns the slot. That is the same shape the match compiler interns patterns with,
 * and it means there is no second walk to keep in step with the first (E27).
 *
 * <p><b>Two namespaces, two slot spaces.</b> Variables and keys are separate here because they
 * are separate in the language — the compiler keeps its own declared set for keys, and a key and
 * a variable may share a name without meaning the same thing. They therefore index different
 * arrays at run time, and carry different types so that they cannot be indexed into each
 * other's.
 *
 * <p><b>It is a builder and nothing else.</b> What a run holds is {@link Names}, which this
 * hands over once and is then discarded — so a name cannot be interned after compilation because
 * there is nothing left to intern into. That used to be a {@code frozen} flag on the table
 * itself, refusing a late intern with an exception; not being able to is better than being told
 * not to, and it is what pulling the builder out of the value buys.
 */
final class Interner {

    private final Map<String, VarName> byName = new HashMap<>();

    /**
     * Keys, which are their own namespace: the compiler keeps a separate declared set for them,
     * and a key and a variable may share a name without meaning the same thing. Two tables, so
     * two slot spaces, so two arrays that cannot be indexed into each other.
     */
    private final Map<String, KeyName> keysByName = new HashMap<>();

    /** What each declared name holds, recorded before any body compiles (design 35 §5). */
    private final Map<String, Declaration.Type> types = new HashMap<>();

    Interner() {
        // group() is interned first and always, because the interpreter binds it whether or not
        // the configuration reads it — it is the one engine function that answers a sequence, so
        // design 30 phase 4 left it a store rather than a frame, and a store needs a slot. It is
        // interned under its spelling, which no declaration can take (design 35 §6).
        intern(EngineVars.GROUP.spelling());
    }

    /** Record a declaration: its name gets a slot, and its type travels with the table. */
    VarName declare(final Declaration declaration) {
        types.put(declaration.name(), declaration.type());
        return intern(declaration.name());
    }

    /** What a name was declared to hold, or a scalar for one declared in place. */
    Declaration.Type typeOf(final String name) {
        final Declaration.Type declared = types.get(name);
        return declared == null ? Declaration.Type.SCALAR : declared;
    }

    VarName intern(final String name) {
        if (name == null) {
            return null;
        }
        return byName.computeIfAbsent(name, key -> new VarName(key, byName.size()));
    }


    /**
     * The interned key, assigning a slot if this is the first sight of it.
     *
     * <p>A {@code key-get} may compile before the {@code key} that builds what it reads — they
     * can be in different templates — so the slot is assigned by whichever arrives first, exactly
     * as a variable's is.
     */
    KeyName internKey(final String name) {
        if (name == null) {
            return null;
        }
        return keysByName.computeIfAbsent(name, key -> new KeyName(key, keysByName.size()));
    }







    /** The table, finished. The interner is done with once this is taken. */
    Names names() {
        return new Names(byName, keysByName, types);
    }
}
