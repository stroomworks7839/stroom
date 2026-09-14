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
import stroom.shapeshifter.engine.config.EngineVars;
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

    private static final VarName X = new VarName("x", 1);
    private static final VarName L = new VarName("l", 2);
    private static final VarName M = new VarName("m", 3);

    /** A name table as the compiler builds one: {@code group()} is always slot 0. */
    private static Names names() {
        final VarName group = new VarName(EngineVars.GROUP.spelling(), 0);
        return new Names(
                Map.of(group.name(), group, X.name(), X, L.name(), L, M.name(), M),
                Map.of(),
                Map.of(L.name(), Declaration.Type.LIST, M.name(), Declaration.Type.MAP));
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
}
