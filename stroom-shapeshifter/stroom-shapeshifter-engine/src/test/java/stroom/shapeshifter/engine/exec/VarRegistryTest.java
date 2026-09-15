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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry after design 35 phase 3: a slot holds a value, a declaration is an entry in the
 * undo log, and one run-wide counter says how many elements the collections in the slots hold.
 */
class VarRegistryTest {

    private static final VarName X = new VarName("x", 0);
    private static final VarName L = new VarName("l", 1);
    private static final VarName M = new VarName("m", 2);

    /** A name table as the compiler builds one: the author's names, dense from zero. */
    private static Names names() {
        return new Names(Map.of(X.name(), X, L.name(), L, M.name(), M), Map.of(L.name(),
                Declaration.Type.LIST, M.name(), Declaration.Type.MAP));
    }

    @Test
    void declarationRestoresOnExitToWhatWasThereBefore() {
        final VarRegistry vars = new VarRegistry(names());
        vars.set(X, TypedValue.of("outer"));
        vars.push(new VarName[]{X});
        assertThat(vars.get(X)).as("declared unset").isNull();
        vars.set(X, TypedValue.of("inner"));
        vars.pop();
        assertThat(vars.get(X)).isEqualTo(TypedValue.of("outer"));
    }

    /** Recursion: the same name declared twice on the way down unwinds to the binding outside both. */
    @Test
    void nameDeclaredTwiceOnTheWayDownUnwindsInReverse() {
        final VarRegistry vars = new VarRegistry(names());
        vars.set(X, TypedValue.of("a"));
        vars.push(new VarName[]{X});
        vars.set(X, TypedValue.of("b"));
        vars.push(new VarName[]{X});
        vars.set(X, TypedValue.of("c"));
        vars.pop();
        assertThat(vars.get(X)).isEqualTo(TypedValue.of("b"));
        vars.pop();
        assertThat(vars.get(X)).isEqualTo(TypedValue.of("a"));
    }

    /** A collection comes into being on first use, in the slot its declaration owns, and goes with it. */
    @Test
    void listMadeInsideADeclaringScopeGoesWithIt() {
        final VarRegistry vars = new VarRegistry(names());
        vars.push(new VarName[]{L});
        vars.setAt(L, 1, TypedValue.of("one"));
        vars.setAt(L, 3, TypedValue.of("three"));
        assertThat(vars.list(L).size()).as("position 3 grew the list, absence between").isEqualTo(4);
        assertThat(vars.live()).as("absence counts: it is an element, position 0 included").isEqualTo(4);
        vars.pop();
        assertThat(vars.get(L)).isNull();
        assertThat(vars.live()).as("exit releases the list's elements").isZero();
    }

    @Test
    void mapCountsItsKeysOnceAndAClearReleasesThem() {
        final VarRegistry vars = new VarRegistry(names());
        vars.put(M, TypedValue.of("k"), TypedValue.of("v1"));
        vars.put(M, TypedValue.of("k"), TypedValue.of("v2"));
        vars.put(M, TypedValue.of("j"), null);
        assertThat(vars.live()).as("two keys, one of them absent-valued").isEqualTo(2);
        assertThat(vars.map(M).get(TypedValue.of("k"))).isEqualTo(TypedValue.of("v2"));
        vars.clear(M);
        assertThat(vars.live()).isZero();
        assertThat(vars.map(M).size()).isZero();
    }

    /** Replacing a collection by a bind leaves the count as a scope exit would. */
    @Test
    void replacedCollectionLeavesTheCount() {
        final VarRegistry vars = new VarRegistry(names());
        vars.append(L, TypedValue.of("a"));
        vars.append(L, TypedValue.of("b"));
        assertThat(vars.live()).isEqualTo(2);
        final TypedValue.List fresh = new TypedValue.List();
        fresh.append(TypedValue.of("z"));
        vars.set(L, fresh);
        assertThat(vars.live()).isEqualTo(1);
        vars.set(L, null);
        assertThat(vars.live()).isZero();
    }

    /** Declaring a name the current scope already holds is a no-op, not another log entry. */
    @Test
    void redeclaringInTheSameScopeKeepsTheValue() {
        final VarRegistry vars = new VarRegistry(names());
        vars.push(new VarName[]{X});
        vars.set(X, TypedValue.of("kept"));
        vars.declare(X);
        assertThat(vars.get(X)).isEqualTo(TypedValue.of("kept"));
        vars.pop();
    }

    @Test
    void typesComeFromTheDeclarations() {
        final VarRegistry vars = new VarRegistry(names());
        assertThat(vars.typeOf(L)).isEqualTo(Declaration.Type.LIST);
        assertThat(vars.typeOf(M)).isEqualTo(Declaration.Type.MAP);
        assertThat(vars.typeOf(X)).as("a name declared in place holds a scalar").isEqualTo(Declaration.Type.SCALAR);
    }

    // -----------------------------------------------------------------------------------
    // Design 37 phase 2: a collection a scope discards is parked, cleared, and refilled
    // -----------------------------------------------------------------------------------

    /** The list the first execution made is the list the second execution gets, empty. */
    @Test
    void discardedListIsParkedAndTheNextScopeReusesIt() {
        final VarRegistry vars = new VarRegistry(names());
        vars.push(new VarName[]{L});
        vars.append(L, TypedValue.of("a"));
        final TypedValue.List first = vars.list(L);
        vars.pop();
        assertThat(vars.get(L)).as("unset outside, as before").isNull();
        assertThat(first.size()).as("cleared on exit").isZero();

        vars.push(new VarName[]{L});
        assertThat(vars.list(L)).as("the same object, not a new one").isSameAs(first);
        assertThat(vars.list(L).size()).isZero();
        vars.append(L, TypedValue.of("b"));
        assertThat(vars.live()).as("the count starts again from the refill").isEqualTo(1);
        vars.pop();
        assertThat(vars.live()).isZero();
    }

    /** A map declared with entries refills the parked map from its table rather than copying into a new one. */
    @Test
    void initialTableRefillsTheParkedMap() {
        final VarRegistry vars = new VarRegistry(names());
        final TypedValue.Map table = new TypedValue.Map();
        table.put(TypedValue.of("k"), TypedValue.of("v"));
        final VarName[] declared = {M};
        final TypedValue[] initial = {table};

        vars.push(declared, initial);
        final TypedValue.Map first = vars.map(M);
        assertThat(first).as("the table is copied, never installed").isNotSameAs(table);
        vars.put(M, TypedValue.of("extra"), TypedValue.of("x"));
        assertThat(vars.live()).isEqualTo(2);
        vars.pop();

        vars.push(declared, initial);
        assertThat(vars.map(M)).isSameAs(first);
        assertThat(vars.map(M).size()).as("the table's entry and nothing from last time").isEqualTo(1);
        assertThat(vars.map(M).get(TypedValue.of("k"))).isEqualTo(TypedValue.of("v"));
        assertThat(vars.live()).isEqualTo(1);
        vars.pop();
        assertThat(table.size()).as("the table itself is untouched").isEqualTo(1);
    }

    /** Storing a value that is reachable through the collection it replaces copies first and discards after. */
    @Test
    void storingANestedCollectionOverItsOwnerCopiesBeforeDiscarding() {
        final VarRegistry vars = new VarRegistry(names());
        final TypedValue.List inner = new TypedValue.List();
        inner.append(TypedValue.of("deep"));
        vars.put(M, TypedValue.of("k"), inner);
        final TypedValue nested = vars.map(M).get(TypedValue.of("k"));
        assertThat(nested).as("stored as a copy").isNotSameAs(inner);

        vars.set(L, nested);
        assertThat(vars.list(L).get(0)).isEqualTo(TypedValue.of("deep"));
        assertThat(vars.live()).as("the map's key and its list's element, and the copy's element").isEqualTo(3);

        // And over the map's own slot: the map is discarded only once its content is copied out.
        vars.set(M, nested);
        assertThat(vars.get(M)).isInstanceOf(TypedValue.List.class);
        assertThat(((TypedValue.List) vars.get(M)).get(0)).isEqualTo(TypedValue.of("deep"));
        assertThat(vars.live()).isEqualTo(2);
    }

    /** A name set to what it already holds is a no-op: no copy, no clear, no change to the count. */
    @Test
    void settingANameToItsOwnCollectionChangesNothing() {
        final VarRegistry vars = new VarRegistry(names());
        vars.append(L, TypedValue.of("a"));
        final TypedValue.List held = vars.list(L);
        vars.set(L, held);
        assertThat(vars.list(L)).isSameAs(held);
        assertThat(held.size()).isEqualTo(1);
        assertThat(vars.live()).isEqualTo(1);
    }

    /** The one move out of a scope: detached before the exit, the collection survives it whole and counted. */
    @Test
    void detachedCollectionCrossesTheExitAndIsAdoptedOutside() {
        final VarRegistry vars = new VarRegistry(names());
        vars.push(new VarName[]{L});
        vars.append(L, TypedValue.of("kept"));
        final TypedValue.Collection carried = vars.detach(L);
        assertThat(vars.get(L)).isNull();
        assertThat(vars.live()).as("uncounted while carried").isZero();
        vars.pop();
        assertThat(carried.size()).as("not parked, not cleared").isEqualTo(1);
        vars.adopt(L, carried);
        assertThat(vars.list(L)).isSameAs(carried);
        assertThat(vars.live()).isEqualTo(1);
    }
}
