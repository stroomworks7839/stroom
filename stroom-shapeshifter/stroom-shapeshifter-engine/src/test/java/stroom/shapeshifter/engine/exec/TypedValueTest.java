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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The casting table of design/17 §3.1, one assertion per cell — the single source for every
 * conversion in the engine, pinned so a cell cannot drift without a test saying which one.
 *
 * <p>Every cast is total and never throws; its failure value is absent, spelt null here
 * (§2's rule). The {@code Instant} row arrives with phase 4 and joins this test then.
 */
class TypedValueTest {

    // -----------------------------------------------------------------------------------
    // Bytes — the untyped row: text earns a type only by parsing as one
    // -----------------------------------------------------------------------------------

    @Test
    void bytesToString() {
        assertThat(TypedValue.of("héllo").asString()).isEqualTo("héllo");
    }

    @Test
    void bytesToNumber() {
        assertThat(TypedValue.of(" 42.5 ").asNumber()).isEqualTo(42.5);
        assertThat(TypedValue.of("n/a").asNumber()).isNull();
    }

    @Test
    void bytesToInteger() {
        assertThat(TypedValue.of(" 42 ").asInteger()).isEqualTo(42L);
        // Written with a point it is not integral text, whatever its value.
        assertThat(TypedValue.of("42.0").asInteger()).isNull();
        assertThat(TypedValue.of("n/a").asInteger()).isNull();
    }

    @Test
    void bytesToBooleanIsTheLexicalCastNotNonEmptiness() {
        assertThat(TypedValue.of("true").asBoolean()).isTrue();
        assertThat(TypedValue.of("1").asBoolean()).isTrue();
        // The cell the review corrected: under the non-emptiness rule this would be true.
        assertThat(TypedValue.of("false").asBoolean()).isFalse();
        assertThat(TypedValue.of("0").asBoolean()).isFalse();
        assertThat(TypedValue.of("yes").asBoolean()).isNull();
        assertThat(TypedValue.of("").asBoolean()).isNull();
    }

    // -----------------------------------------------------------------------------------
    // Int
    // -----------------------------------------------------------------------------------

    @Test
    void intCasts() {
        final TypedValue value = new TypedValue.Int(-7);
        assertThat(value.asString()).isEqualTo("-7");
        assertThat(value.asNumber()).isEqualTo(-7.0);
        assertThat(value.asInteger()).isEqualTo(-7L);
        assertThat(value.asBoolean()).isTrue();
        assertThat(new TypedValue.Int(0).asBoolean()).isFalse();
    }

    // -----------------------------------------------------------------------------------
    // Real
    // -----------------------------------------------------------------------------------

    @Test
    void realToStringDropsWholeNumberPoint() {
        // The Rust-style rendering the engine already had, kept by ruling (§16.8).
        assertThat(new TypedValue.Real(5.0).asString()).isEqualTo("5");
        assertThat(new TypedValue.Real(5.5).asString()).isEqualTo("5.5");
    }

    @Test
    void realToNumber() {
        assertThat(new TypedValue.Real(2.5).asNumber()).isEqualTo(2.5);
    }

    @Test
    void realToIntegerRefusesToTruncate() {
        assertThat(new TypedValue.Real(9.0).asInteger()).isEqualTo(9L);
        // Absent, not 9 — silent truncation is how 9.99 becomes 9 (§3.1).
        assertThat(new TypedValue.Real(9.99).asInteger()).isNull();
        assertThat(new TypedValue.Real(Double.POSITIVE_INFINITY).asInteger()).isNull();
        // Integral by rint but too wide for a long: the cast would saturate to the wrong number.
        assertThat(new TypedValue.Real(0x1p63).asInteger()).isNull();
    }

    @Test
    void realToBoolean() {
        assertThat(new TypedValue.Real(0.5).asBoolean()).isTrue();
        assertThat(new TypedValue.Real(0.0).asBoolean()).isFalse();
    }

    // -----------------------------------------------------------------------------------
    // Bool
    // -----------------------------------------------------------------------------------

    @Test
    void boolCasts() {
        final TypedValue value = new TypedValue.Bool(true);
        assertThat(value.asString()).isEqualTo("true");
        assertThat(value.asNumber()).isEqualTo(1.0);
        assertThat(value.asInteger()).isEqualTo(1L);
        assertThat(value.asBoolean()).isTrue();
        assertThat(new TypedValue.Bool(false).asNumber()).isEqualTo(0.0);
        assertThat(new TypedValue.Bool(false).asInteger()).isEqualTo(0L);
        assertThat(new TypedValue.Bool(false).asBoolean()).isFalse();
    }

    // -----------------------------------------------------------------------------------
    // The boundary conventions the casts rest on
    // -----------------------------------------------------------------------------------

    @Test
    void emptyBytesAreAbsent() {
        assertThat(TypedValue.of("").isEmpty()).isTrue();
        assertThat(new TypedValue.Int(0).isEmpty()).isFalse();
    }

    @Test
    void numbersRenderAsAscii() {
        // asBytes is the encoding-independent form: numbers are ASCII whatever the input was.
        assertThat(new TypedValue.Int(42).asBytes()).isEqualTo("42".getBytes());
        assertThat(TypedValue.of("é").asBytes()).isEqualTo("é".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
