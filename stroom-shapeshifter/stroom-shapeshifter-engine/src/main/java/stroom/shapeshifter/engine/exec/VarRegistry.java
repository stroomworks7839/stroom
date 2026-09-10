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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.compile.VarName;
import stroom.shapeshifter.engine.compile.VarNames;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The variables in scope, and their values.
 *
 * <p><b>A name is a slot, not a string</b> (design 30 phase 5). Every name a configuration uses
 * was interned when it compiled, so a read is an array access: no hash, and no walk outwards
 * through a stack of maps. The counting behind this found 252,553 hash lookups per operation on
 * {@code log_sessions} and 140,892 on {@code apache_httpd}, four fifths of them the walk's
 * per-level hash on a walk only 1.35 levels deep — many shallow lookups rather than a few deep
 * ones, which is exactly the case where removing the hash is the whole win.
 *
 * <h2>Scopes are an undo log</h2>
 *
 * <p>There is one array for the run and a log of what to put back. A push records the log's
 * height; a shadow saves the slot's current binding and installs a fresh one; a pop unwinds to
 * the mark <b>in reverse</b>, which is what makes a name shadowed twice in one scope come back to
 * the binding outside it rather than to the one in between. So a scope is an {@code int}, and
 * nothing is allocated to open one.
 *
 * <p>Scopes still exist for memory rather than for hygiene. A recursive apply over a large
 * document would otherwise accumulate every capture of every level; unwinding releases them,
 * which is what keeps a stream of unbounded length processable in bounded space.
 *
 * <p>{@code owner} is what keeps the log bounded: it holds the depth that installed each slot's
 * current binding, so shadowing a name this scope already holds is the no-op it always was
 * rather than another entry. Without it a {@code sequence} declared inside a loop would log once
 * per iteration.
 *
 * <p>Each name maps to a <i>list</i> of stores, indexed by capture group. Group 0 is where
 * ordinary captures land; the wider list is what lets a variable carry a whole match's groups.
 *
 * <p>What is in here is the <b>author's</b> names. The engine's own are {@link Frames}, which
 * design 30 phase 4 gave them; the one exception is {@code __group}, a sequence rather than a
 * scalar, which is a slot like any other.
 */
public final class VarRegistry {

    /** Not bound by any scope. */
    private static final int UNBOUND = -1;

    private final VarNames names;

    /** Every slot's current binding, by {@link VarName#slot()}. */
    private final List<List<Store>> slots = new ArrayList<>();

    /** The scope depth that installed each slot's current binding, or {@link #UNBOUND}. */
    private int[] owner;

    /**
     * The table, extended with names that arrived from the data. Null until one does, which for
     * a configuration without key-value captures is for ever.
     */
    private Map<String, VarName> extended;

    private int[] marks = new int[16];
    private int depth;

    private int[] undoSlot = new int[64];
    private int[] undoOwner = new int[64];
    private final List<List<Store>> undoSaved = new ArrayList<>();

    private final Frames frames = new Frames();

    public VarRegistry(final VarNames names) {
        this.names = names;
        this.owner = new int[names.size()];
        Arrays.fill(owner, UNBOUND);
        for (int i = 0; i < names.size(); i++) {
            slots.add(null);
        }
    }

    /** The engine's own variables, which are frames rather than names. */
    public Frames frames() {
        return frames;
    }

    /** Enter a new scope. */
    public void push() {
        if (depth == marks.length) {
            marks = Arrays.copyOf(marks, marks.length * 2);
        }
        marks[depth++] = undoSaved.size();
    }

    /**
     * Enter a new scope shadowing a set of names settled at compile time.
     *
     * <p>Every one of the interpreter's pushes knows what it shadows before the run starts — a
     * loop's binding, a call's arguments and parameters, a recursive applies captures — so the
     * two operations are one, over an array that on the measured workloads is almost always of
     * length one.
     */
    public void push(final VarName[] shadowed) {
        push();
        for (final VarName name : shadowed) {
            shadow(name);
        }
    }

    /** Leave the current scope, discarding everything written in it. */
    public void pop() {
        if (depth == 0) {
            throw new IllegalStateException("Cannot pop the global scope");
        }
        final int mark = marks[--depth];
        for (int i = undoSaved.size() - 1; i >= mark; i--) {
            final int slot = undoSlot[i];
            slots.set(slot, undoSaved.removeLast());
            owner[slot] = undoOwner[i];
        }
    }

    /** The stores for a name, or null. */
    public List<Store> get(final VarName name) {
        return slots.get(name.slot());
    }

    /** The stores for a name the run read out of the data, or null. */
    public List<Store> get(final String name) {
        return get(name(name));
    }

    /** The stores for a name, creating them in the innermost scope if nothing holds it yet. */
    public List<Store> entry(final VarName name) {
        final int slot = name.slot();
        final List<Store> found = slots.get(slot);
        if (found != null) {
            return found;
        }
        return bind(slot);
    }

    /** The group-0 store for a name, creating it if needed. */
    public Store store(final VarName name) {
        final List<Store> stores = entry(name);
        if (stores.isEmpty()) {
            stores.add(new Store());
        }
        return stores.getFirst();
    }

    /** The group-0 store for a name the run read out of the data. */
    public Store store(final String name) {
        return store(name(name));
    }

    /**
     * Note that a name exists, in the global scope, so that a later write from inside a nested
     * scope finds it there rather than creating a local one.
     */
    public void register(final VarName name) {
        final int slot = name.slot();
        if (slots.get(slot) == null) {
            slots.set(slot, new ArrayList<>(1));
            owner[slot] = 0;
        }
    }

    /** Note a name in the <i>current</i> scope only, so an inner write cannot escape it. */
    public void shadow(final VarName name) {
        final int slot = name.slot();
        if (owner[slot] == depth) {
            // Already this scope's, which is what the per-scope map said by not replacing an
            // entry it already held.
            return;
        }
        bind(slot);
    }

    /** A name's stores from the current scope only, ignoring anything outside it. */
    public List<Store> fromCurrentScope(final VarName name) {
        final int slot = name.slot();
        return owner[slot] == depth ? slots.get(slot) : null;
    }

    /**
     * The slot a name from the data belongs in.
     *
     * <p>The run's one string lookup, and the rule of design 30 §1 holding rather than failing: a
     * key-value capture's name <em>is</em> data, and a map is what a data key is for. A name the
     * configuration never mentions gets a slot of its own rather than being dropped — nothing can
     * read it, but a capture has to keep operating for something outside the run to present it
     * (§8 ruling 8).
     *
     * <p>A condition's operands still come through here too, because a condition resolves the
     * authored expression rather than a compiled reference. That is E39's open seam and not this
     * method's purpose; §5.5 has the count.
     */
    private VarName name(final String name) {
        if (extended != null) {
            // A plain get first: the names a key-value capture binds repeat every record, so
            // the hit is the case, and computeIfAbsent is the slower way to take it.
            final VarName seen = extended.get(name);
            return seen != null ? seen : extended.computeIfAbsent(name, this::grow);
        }
        final VarName known = names.lookup(name);
        if (known != null) {
            return known;
        }
        // The first name out of the data. Copying the table once, here, is what keeps every
        // later lookup a single hit: consulting the compiled table and then a separate map of
        // data-derived names costs a miss and a hit for exactly the names a key-value
        // configuration resolves most — 14,140 per operation on `ausearch`, which measured as a
        // regression until this became one lookup.
        extended = new HashMap<>(names.all());
        return extended.computeIfAbsent(name, this::grow);
    }

    private VarName grow(final String name) {
        final VarName made = new VarName(name, slots.size());
        slots.add(null);
        owner = Arrays.copyOf(owner, slots.size());
        owner[made.slot()] = UNBOUND;
        return made;
    }

    /** Install a fresh binding for a slot in the current scope, logging what it replaced. */
    private List<Store> bind(final int slot) {
        if (depth > 0) {
            final int at = undoSaved.size();
            if (at == undoSlot.length) {
                undoSlot = Arrays.copyOf(undoSlot, at * 2);
                undoOwner = Arrays.copyOf(undoOwner, at * 2);
            }
            undoSlot[at] = slot;
            undoOwner[at] = owner[slot];
            undoSaved.add(slots.get(slot));
        }
        final List<Store> stores = new ArrayList<>(1);
        slots.set(slot, stores);
        owner[slot] = depth;
        return stores;
    }
}
