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

import stroom.shapeshifter.engine.config.Declaration;
import stroom.shapeshifter.engine.graph.Names;
import stroom.shapeshifter.engine.graph.VarName;
import stroom.shapeshifter.engine.value.TypedValue;

import java.util.Arrays;

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
 * <p><b>A slot holds a value</b> (design 35 §8). A scalar's slot holds the value; a list's holds
 * a {@link TypedValue.List}; a map's a {@link TypedValue.Map}. What this replaced was a
 * {@code Store} per slot — a sparse array of per-match history behind every name, scalar or not —
 * which was an object and an indirection on every reference resolution, the hottest read in the
 * engine. The history a list needs is now a list, declared as one.
 *
 * <p><b>The slot array is fixed.</b> It is sized once from the names the configuration declares
 * and never grows: a key-value capture puts into a declared map rather than minting a slot per
 * key it reads out of the data, which was the one thing that used to grow it.
 *
 * <h2>Scopes are an undo log</h2>
 *
 * <p>There is one array for the run and a log of what to put back. Entering a declaring
 * execution records the log's height and, per declared name, saves the slot's current value and
 * sets it unset; leaving unwinds to the mark <b>in reverse</b>, which is what makes a name declared
 * twice on the way down come back to the binding outside both rather than to the one in between.
 * So a scope is an {@code int}, and nothing is allocated to open one: declaring writes null, and
 * a collection comes into being on its first mutation, in the slot its declaration owns.
 *
 * <p>Restore-on-exit is what gives clear-on-exit: an execution's declarations restore to what was
 * there before it, which was unset, so the next execution starts unset; and it gives recursion
 * its own variables for free, because an inner execution's declaration logs and restores just the
 * same (design 35 §4).
 *
 * <p>{@code owner} is what keeps the log bounded: it holds the depth that installed each slot's
 * current binding, so declaring a name this scope already holds is the no-op it always was
 * rather than another entry.
 *
 * <h2>The live-element counter</h2>
 *
 * <p>One run-wide count of the elements every collection in a slot holds (design 35 §11): every
 * append or put increments it, and a clear or a scope exit that discards a collection decrements
 * it by that collection's size, in O(1). It is a bound on memory, judged by the interpreter
 * against {@code max_sequence_entries}; it is exact while no collection is reachable from two
 * slots, which nothing here does.
 *
 * <p>What is in here is the <b>author's</b> names. The engine's own are {@link Frames}, which
 * design 30 phase 4 gave them; the one exception is {@code group()}, a sequence rather than a
 * scalar, which is a slot like any other.
 */
public final class VarRegistry {

    /** Not bound by any scope. */
    private static final int UNBOUND = -1;

    /** How many undo entries the log starts with; it doubles from there. */
    private static final int UNDO_INITIAL = 64;

    /** Every slot's current value, by {@link VarName#slot()}; null is unset. */
    private final TypedValue[] slots;

    /** The scope depth that installed each slot's current binding, or {@link #UNBOUND}. */
    private final int[] owner;

    /** What each name was declared to hold, by slot; {@code SCALAR} for a name declared in place. */
    private final Declaration.Type[] types;

    private int[] marks = new int[16];
    private int depth;

    // The undo log, as three parallel arrays: which slot an entry restores, what owned it, and
    // what it held. They are grown together in declare() and must stay the same length.

    /** The slot each entry restores. */
    private int[] undoSlot;

    /** The scope depth that owned the slot before the entry replaced it. */
    private int[] undoOwner;

    /** What each entry replaced. */
    private TypedValue[] undoSaved;

    /** How many entries are live, which is also the next free index in all three. */
    private int undoCount;

    /** How many elements the collections in every slot hold between them. */
    private long live;

    private final Frames frames = new Frames();

    public VarRegistry(final Names names) {
        this.owner = new int[names.size()];
        Arrays.fill(owner, UNBOUND);
        this.slots = new TypedValue[names.size()];
        this.types = new Declaration.Type[names.size()];
        for (final VarName name : names.all().values()) {
            types[name.slot()] = names.typeOf(name);
        }
        this.undoSlot = new int[UNDO_INITIAL];
        this.undoOwner = new int[UNDO_INITIAL];
        this.undoSaved = new TypedValue[UNDO_INITIAL];
    }

    /** The engine's own variables, which are frames rather than names. */
    public Frames frames() {
        return frames;
    }

    /** What a name was declared to hold. */
    public Declaration.Type typeOf(final VarName name) {
        return types[name.slot()];
    }

    /** Enter a new scope that declares nothing yet. */
    public void push() {
        if (depth == marks.length) {
            marks = Arrays.copyOf(marks, marks.length * 2);
        }
        marks[depth++] = undoCount;
    }

    /**
     * Enter a new scope declaring a set of names settled at compile time — a template's
     * declarations, a call's parameters, a loop's {@code as}.
     */
    public void push(final VarName[] declared) {
        push();
        for (final VarName name : declared) {
            declare(name);
        }
    }

    /** Leave the current scope, restoring every name it declared to what it held outside. */
    public void pop() {
        if (depth == 0) {
            throw new IllegalStateException("Cannot pop the global scope");
        }
        final int mark = marks[--depth];
        for (int i = undoCount - 1; i >= mark; i--) {
            final int slot = undoSlot[i];
            release(slots[slot]);
            slots[slot] = undoSaved[i];
            undoSaved[i] = null;
            owner[slot] = undoOwner[i];
        }
        undoCount = mark;
    }

    /** A name's value, or null when it is unset. */
    public TypedValue get(final VarName name) {
        return slots[name.slot()];
    }

    /**
     * Set a name's value in the slot its declaration owns. A collection replaced here leaves the
     * count, as one discarded on exit does.
     */
    public void set(final VarName name, final TypedValue value) {
        final int slot = name.slot();
        release(slots[slot]);
        slots[slot] = value;
        if (value instanceof final TypedValue.Collection collection) {
            live += collection.size();
        }
    }

    /**
     * Declare a name in the <i>current</i> scope: its value outside is logged and restored on
     * exit, and inside it starts unset.
     */
    public void declare(final VarName name) {
        final int slot = name.slot();
        if (owner[slot] == depth) {
            // Already this scope's, which is what the per-scope map said by not replacing an
            // entry it already held.
            return;
        }
        if (depth > 0) {
            final int at = undoCount;
            if (at == undoSlot.length) {
                undoSlot = Arrays.copyOf(undoSlot, at * 2);
                undoOwner = Arrays.copyOf(undoOwner, at * 2);
                undoSaved = Arrays.copyOf(undoSaved, at * 2);
            }
            undoSlot[at] = slot;
            undoOwner[at] = owner[slot];
            undoSaved[at] = slots[slot];
            undoCount = at + 1;
        } else {
            // The global scope logs nothing to restore, so what it displaces is simply gone.
            release(slots[slot]);
        }
        slots[slot] = null;
        owner[slot] = depth;
    }

    /** A name's value from the current scope only, ignoring anything outside it. */
    public TypedValue fromCurrentScope(final VarName name) {
        final int slot = name.slot();
        return owner[slot] == depth ? slots[slot] : null;
    }

    /**
     * The list a name holds, made on first use in the slot its declaration owns. A slot holding
     * something else — a scalar bound where a list was declared — is replaced, as a bind replaces.
     */
    public TypedValue.List list(final VarName name) {
        final int slot = name.slot();
        if (slots[slot] instanceof final TypedValue.List list) {
            return list;
        }
        final TypedValue.List made = new TypedValue.List();
        set(name, made);
        return made;
    }

    /** The map a name holds, made on first use — see {@link #list}. */
    public TypedValue.Map map(final VarName name) {
        final int slot = name.slot();
        if (slots[slot] instanceof final TypedValue.Map map) {
            return map;
        }
        final TypedValue.Map made = new TypedValue.Map();
        set(name, made);
        return made;
    }

    /** Put a value at a position of a name's list — absence included — counting what that grows. */
    public void setAt(final VarName name, final int position, final TypedValue value) {
        live += list(name).set(position, value);
    }

    /** Append to a name's list, counting the element. */
    public void append(final VarName name, final TypedValue value) {
        list(name).append(value);
        live++;
    }

    /** Put an entry in a name's map, counting a new key. */
    public void put(final VarName name, final TypedValue key, final TypedValue value) {
        final TypedValue.Map map = map(name);
        if (!map.contains(key)) {
            live++;
        }
        map.put(key, value);
    }

    /** Empty a name's collection, if it holds one, releasing its elements from the count. */
    public void clear(final VarName name) {
        if (slots[name.slot()] instanceof final TypedValue.Collection collection) {
            live -= collection.size();
            collection.clear();
        }
    }

    /** How many elements every collection in a slot holds between them. */
    public long live() {
        return live;
    }

    private void release(final TypedValue value) {
        if (value instanceof final TypedValue.Collection collection) {
            live -= collection.size();
        }
    }
}
