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

import stroom.shapeshifter.engine.graph.Names;
import stroom.shapeshifter.engine.graph.VarName;
import stroom.shapeshifter.engine.value.TypedValue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captured values and where they are kept, ported from the Rust crate's suite.
 *
 * <p>Two behaviours here are load-bearing further up and easy to lose. A store is <b>sparse</b>,
 * so a capture that did not match leaves a hole rather than shifting everything after it — index
 * three still means the third match. And a value is <b>typed</b>, so a number read from binary
 * data stays a number until something writes it, rather than being rendered and parsed again.
 */
class StoreTest {

    /**
     * A name table as the compiler would have built one: slot 0 is always {@code __group}, which
     * the interpreter binds whether or not a configuration reads it.
     */
    private static Names table(final VarName... names) {
        final java.util.Map<String, VarName> byName = new java.util.HashMap<>();
        byName.put(stroom.shapeshifter.engine.config.EngineVars.GROUP.varName(),
                new VarName(stroom.shapeshifter.engine.config.EngineVars.GROUP.varName(), 0));
        for (final VarName name : names) {
            byName.put(name.name(), name);
        }
        return new Names(byName, java.util.Map.of());
    }

    private static TypedValue text(final String value) {
        return TypedValue.of(value);
    }

    // -----------------------------------------------------------------------------------
    // Store
    // -----------------------------------------------------------------------------------

    /**
     * The store grows by doubling until an index fits (design 33 §11 E), so a single distant
     * index has to carry the earlier ones with it rather than starting again.
     */
    @Test
    void growingToADistantIndexKeepsWhatCameBefore() {
        final Store store = new Store();
        store.set(0, text("first"));
        store.set(500, text("far"));

        assertThat(store.get(0)).isEqualTo(text("first"));
        assertThat(store.get(499)).isNull();
        assertThat(store.get(500)).isEqualTo(text("far"));
        assertThat(store.lastIndex()).isEqualTo(500);
        assertThat(store.size()).isEqualTo(501);
    }

    /** Two hundred matches in a row, which doubles the store several times over. */
    @Test
    void manySequentialMatchesAreAllKept() {
        final Store store = new Store();
        for (int i = 0; i < 200; i++) {
            store.set(i, text("v" + i));
        }
        assertThat(store.size()).isEqualTo(200);
        for (int i = 0; i < 200; i++) {
            assertThat(store.get(i)).isEqualTo(text("v" + i));
        }
    }

    /** A store outlives the record that filled it, so clearing has to leave it usable. */
    @Test
    void clearingLeavesTheStoreReusable() {
        final Store store = new Store();
        store.set(9, text("old"));
        store.clear();

        assertThat(store.size()).isZero();
        assertThat(store.lastIndex()).isEqualTo(-1);
        assertThat(store.get(9)).isNull();
        assertThat(store.latest()).isNull();

        store.set(1, text("new"));
        assertThat(store.latest()).isEqualTo(text("new"));
        assertThat(store.lastIndex()).isEqualTo(1);
    }

    @Test
    void keepsValuesByMatchNumber() {
        final Store store = new Store();
        store.set(0, text("first"));
        store.set(1, text("second"));

        assertThat(store.get(0)).isEqualTo(text("first"));
        assertThat(store.get(1)).isEqualTo(text("second"));
        assertThat(store.get(2)).isNull();
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void growsToReachAnIndexAndLeavesAHole() {
        final Store store = new Store();
        store.set(3, text("fourth"));

        assertThat(store.get(3)).isEqualTo(text("fourth"));
        // The gap is a gap, not a shift: index 3 still means the fourth match.
        assertThat(store.get(0)).isNull();
        assertThat(store.get(1)).isNull();
        assertThat(store.size()).isEqualTo(4);
    }

    @Test
    void clearingAValueLeavesTheOthersWhereTheyAre() {
        final Store store = new Store();
        store.set(0, text("a"));
        store.set(1, text("b"));
        store.remove(0);

        // Without this, an unmatched capture would read as the previous record's value.
        assertThat(store.get(0)).isNull();
        assertThat(store.get(1)).isEqualTo(text("b"));
    }

    @Test
    void findsTheMostRecentValue() {
        final Store store = new Store();
        assertThat(store.lastIndex()).isEqualTo(-1);
        assertThat(store.latest()).isNull();

        store.set(0, text("a"));
        store.set(4, text("e"));
        assertThat(store.lastIndex()).isEqualTo(4);
        assertThat(store.latest()).isEqualTo(text("e"));

        store.remove(4);
        // The most recent surviving value, not the highest index ever used.
        assertThat(store.lastIndex()).isZero();
        assertThat(store.latest()).isEqualTo(text("a"));
    }

    // -----------------------------------------------------------------------------------
    // Typed values
    // -----------------------------------------------------------------------------------

    @Test
    void numbersKeepTheirType() {
        assertThat(new TypedValue.Integer(42).asString()).isEqualTo("42");
        assertThat(new TypedValue.Integer(-17).asString()).isEqualTo("-17");
        assertThat(new TypedValue.Integer(42).asNumber()).isEqualTo(42.0);
        // A whole number does not acquire a decimal point on the way out.
        assertThat(new TypedValue.Double(3.0).asString()).isEqualTo("3");
        assertThat(new TypedValue.Double(3.5).asString()).isEqualTo("3.5");
        // Whole but beyond a long: the cast would saturate to Long.MAX_VALUE and render the
        // wrong number, so it keeps its floating form instead.
        assertThat(new TypedValue.Double(1e19).asString()).isEqualTo("1.0E19");
        assertThat(new TypedValue.Bool(true).asString()).isEqualTo("true");
        assertThat(new TypedValue.Bool(true).asNumber()).isEqualTo(1.0);
        assertThat(new TypedValue.Bool(false).asNumber()).isZero();
    }

    @Test
    void bytesBecomeNumbersOnlyIfTheyAreNumbers() {
        assertThat(text("123").asNumber()).isEqualTo(123.0);
        assertThat(text("12.5").asNumber()).isEqualTo(12.5);
        assertThat(text(" 7 ").asNumber()).isEqualTo(7.0);
        // Not a number is null rather than an exception: data is often not what a configuration
        // hoped, and that is a condition being false, not a failure.
        assertThat(text("hello").asNumber()).isNull();
        assertThat(text("").asNumber()).isNull();
    }

    @Test
    void numbersRenderAsAsciiWhateverTheInputWas() {
        assertThat(new TypedValue.Integer(42).asBytes()).isEqualTo("42".getBytes(StandardCharsets.US_ASCII));
        assertThat(new TypedValue.Bool(false).asBytes()).isEqualTo("false".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void onlyBytesCanBeEmpty() {
        assertThat(text("").isEmpty()).isTrue();
        assertThat(text("x").isEmpty()).isFalse();
        // A number is never "absent" the way a missing capture is.
        assertThat(new TypedValue.Integer(0).isEmpty()).isFalse();
    }

    @Test
    void bytesCompareByValueNotByIdentity() {
        // Records compare their components, and an array component would compare by identity —
        // which would make every stored capture unequal to an identical one.
        assertThat(text("abc")).isEqualTo(text("abc"));
        assertThat(text("abc")).hasSameHashCodeAs(text("abc"));
        assertThat(text("abc")).isNotEqualTo(text("abd"));
    }

    // -----------------------------------------------------------------------------------
    // Scopes
    // -----------------------------------------------------------------------------------

    @Test
    void innerScopesShadowOuterOnesAndReleaseOnTheWayOut() {
        // The table is what the compiler would have handed the run: a name is a slot in it.
        final VarName name = new VarName("name", 1);
        final VarRegistry vars = new VarRegistry(table(name));
        vars.store(name).set(0, text("outer"));

        vars.push();
        vars.shadow(name);
        vars.store(name).set(0, text("inner"));
        assertThat(vars.store(name).get(0)).isEqualTo(text("inner"));

        vars.pop();
        // Releasing is the point: a deep recursion would otherwise accumulate every level's
        // captures for the length of the stream.
        assertThat(vars.store(name).get(0)).isEqualTo(text("outer"));
    }

    @Test
    void shadowingTwiceInOneScopeStillRestoresWhatWasOutsideIt() {
        // The undo log unwinds in reverse, so the intermediate binding is restored and then the
        // outer one — which is what a name shadowed twice has to come back to. It matters
        // because a recursive apply shadows every candidate's capture names, and two candidates
        // can declare the same one.
        final VarName name = new VarName("name", 1);
        final VarRegistry vars = new VarRegistry(table(name));
        vars.store(name).set(0, text("outer"));

        vars.push();
        vars.shadow(name);
        vars.store(name).set(0, text("first"));
        vars.shadow(name);
        // Already this scope's, so the second shadow is the no-op the per-scope map made it.
        assertThat(vars.store(name).get(0)).isEqualTo(text("first"));

        vars.pop();
        assertThat(vars.store(name).get(0)).isEqualTo(text("outer"));
    }

    @Test
    void nestedScopesUnwindOneAtATime() {
        final VarName name = new VarName("name", 1);
        final VarRegistry vars = new VarRegistry(table(name));
        vars.store(name).set(0, text("outer"));

        vars.push();
        vars.shadow(name);
        vars.store(name).set(0, text("middle"));
        vars.push();
        vars.shadow(name);
        vars.store(name).set(0, text("inner"));

        vars.pop();
        assertThat(vars.store(name).get(0)).isEqualTo(text("middle"));
        vars.pop();
        assertThat(vars.store(name).get(0)).isEqualTo(text("outer"));
    }

    @Test
    void nameFirstWrittenInsideAScopeDoesNotSurviveIt() {
        final VarName name = new VarName("name", 1);
        final VarRegistry vars = new VarRegistry(table(name));

        vars.push();
        vars.store(name).set(0, text("local"));
        vars.pop();

        // Creating in the innermost scope undoes the same way shadowing does.
        assertThat(vars.get(name)).isNull();
    }

    @Test
    void pushingShadowsTheNamesTheCompilerSettled() {
        // Every push in the interpreter knows its shadow set before the run starts, so the two
        // operations are one call over a compile-time array.
        final VarName first = new VarName("first", 1);
        final VarName second = new VarName("second", 2);
        final VarRegistry vars = new VarRegistry(table(first, second));
        vars.store(first).set(0, text("outer one"));
        vars.store(second).set(0, text("outer two"));

        vars.push(new VarName[]{first, second});
        assertThat(vars.store(first).get(0)).isNull();
        assertThat(vars.store(second).get(0)).isNull();

        vars.pop();
        assertThat(vars.store(first).get(0)).isEqualTo(text("outer one"));
        assertThat(vars.store(second).get(0)).isEqualTo(text("outer two"));
    }

    @Test
    void nameReadOutOfTheDataGetsASlotOfItsOwn() {
        // A key-value capture binds under a name from the input. One the configuration never
        // mentions is kept rather than dropped: nothing can read it, but a capture has to keep
        // operating for something outside the run to present it.
        final VarRegistry vars = new VarRegistry(table());

        vars.store("from the data").set(0, text("value"));

        assertThat(vars.get("from the data")).isNotNull();
        assertThat(vars.get("from the data").getFirst().get(0)).isEqualTo(text("value"));
    }

    @Test
    void nameTheConfigurationMentionsIsTheSameSlotEitherWay() {
        // The failure this guards: a capture writing by name and a reference reading by slot
        // must land on the same variable, or the read is of the wrong slot and says nothing.
        final VarName name = new VarName("shared", 1);
        final VarRegistry vars = new VarRegistry(table(name));

        vars.store("shared").set(0, text("written by name"));

        assertThat(vars.store(name).get(0)).isEqualTo(text("written by name"));
    }

    @Test
    void writeWithoutShadowingReachesTheScopeThatHoldsTheName() {
        final VarName name = new VarName("name", 1);
        final VarRegistry vars = new VarRegistry(table(name));
        vars.store(name).set(0, text("outer"));

        vars.push();
        vars.store(name).set(0, text("changed"));
        vars.pop();

        // No shadow, so to write went to the scope that already had the name.
        assertThat(vars.store(name).get(0)).isEqualTo(text("changed"));
    }
}
