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

    private static TypedValue text(final String value) {
        return TypedValue.of(value.getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------------------
    // Store
    // -----------------------------------------------------------------------------------

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
        final VarRegistry vars = new VarRegistry();
        vars.store("name").set(0, text("outer"));

        vars.push();
        vars.shadow("name");
        vars.store("name").set(0, text("inner"));
        assertThat(vars.store("name").get(0)).isEqualTo(text("inner"));

        vars.pop();
        // Releasing is the point: a deep recursion would otherwise accumulate every level's
        // captures for the length of the stream.
        assertThat(vars.store("name").get(0)).isEqualTo(text("outer"));
    }

    @Test
    void writeWithoutShadowingReachesTheScopeThatHoldsTheName() {
        final VarRegistry vars = new VarRegistry();
        vars.store("name").set(0, text("outer"));

        vars.push();
        vars.store("name").set(0, text("changed"));
        vars.pop();

        // No shadow, so the write went to the scope that already had the name.
        assertThat(vars.store("name").get(0)).isEqualTo(text("changed"));
    }
}
