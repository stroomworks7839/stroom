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

import stroom.shapeshifter.engine.config.EngineVars;
import stroom.shapeshifter.engine.config.RefExpression;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Every variable name a configuration uses, each given a slot (design 30 phase 5).
 *
 * <p>Names are interned as the graph is built rather than by a walk of their own: whatever
 * compiles a node that names a variable asks for the {@link VarName}, and the first ask assigns
 * the slot. That is the same shape the match compiler interns patterns with, and it means there
 * is no second walk to keep in step with the first (E27).
 *
 * <p><b>The map is consulted at run time, and that is the rule holding rather than failing.</b>
 * A key-value capture reads its own name out of the data — DS3's shape where a field's name and
 * its value both come from the input — so that one site has a string and needs a slot. Design 30
 * §1 exempts a key whose value is data, which this is. Every other caller resolved its name when
 * the configuration compiled.
 *
 * <p>A name the table does not hold can still arrive from the data, and the run gives it a slot
 * of its own rather than dropping it: nothing can read it, but a capture has to keep operating
 * for something outside the run to present it (§8 ruling 8).
 */
public final class VarNames {

    private final Map<String, VarName> byName = new HashMap<>();

    /**
     * {@code __group} is interned first and always, because the interpreter binds it whether
     * the configuration reads it — it is the one engine variable that is a sequence, so
     * phase 4 left it a store rather than a frame, and a store needs a slot.
     */
    private final VarName group = intern(EngineVars.GROUP.varName());

    /** Set when the configuration has compiled; after that the slots are a run's array bounds. */
    private boolean frozen;

    /** The slot a grouping binds its members to. */
    public VarName group() {
        return group;
    }

    /**
     * The interned name, assigning a slot if this is the first sight of it.
     *
     * <p>Called while compiling, never while running.
     */
    public VarName intern(final String name) {
        if (name == null) {
            return null;
        }
        if (frozen) {
            // A compiled project outlives the runs that use it (D35), and each of those sized
            // its slot array from this table. A name interned now would have a slot past the end
            // of every one of them, so this is loud rather than silent.
            throw new IllegalStateException(
                    "Names are interned while a configuration compiles, not while it runs: " + name);
        }
        return byName.computeIfAbsent(name, key -> new VarName(key, byName.size()));
    }

    /** No more names: the configuration has compiled, and the count is now a run's array size. */
    void freeze() {
        frozen = true;
    }

    /** Intern several, in order. */
    public VarName[] intern(final String[] names) {
        final VarName[] out = new VarName[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = intern(names[i]);
        }
        return out;
    }

    /**
     * Intern every name an authored expression reads, without compiling it.
     *
     * <p>For the one resolver that still walks the authored form: a condition's operands. It is
     * completeness rather than correctness — {@code CompiledCondition.intern} says why — and it
     * is what lets {@link #all()} mean "every name this configuration mentions".
     */
    public void intern(final RefExpression expression) {
        if (expression == null) {
            return;
        }
        for (final RefExpression.RefPart part : expression.parts()) {
            if (part instanceof final RefExpression.RefPart.Capture capture) {
                intern(capture.varId());
                if (capture.matchIndex() != null) {
                    intern(capture.matchIndex().varRef());
                }
            }
        }
    }

    /** The name if the configuration mentions it, or null — the run's one string lookup. */
    public VarName lookup(final String name) {
        return byName.get(name);
    }

    /**
     * The whole table, for a run that has to extend it.
     *
     * <p>Only a configuration that binds a name from the data needs this, and it takes a copy —
     * see {@code VarRegistry.name}, which is where the reason lives.
     */
    public Map<String, VarName> all() {
        return Collections.unmodifiableMap(byName);
    }

    /** How many slots a run needs before anything arrives from the data. */
    public int size() {
        return byName.size();
    }
}
