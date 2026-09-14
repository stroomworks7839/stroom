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

package stroom.shapeshifter.engine.value;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Equality is canonical per type (design 35 §5, phase 1): numbers numerically, text by decoded
 * content, an instant by its timeline point, and never across kinds. Two of these correct what
 * the records' generated {@code equals} had been doing: a whole double was not its integer, and
 * an instant's inert offset took part.
 */
class CanonicalEqualityTest {

    @Test
    void wholeDoubleEqualsTheIntegerOfItsValue() {
        final TypedValue whole = new TypedValue.Integer(7);
        final TypedValue real = new TypedValue.Double(7.0);
        assertThat(whole).isEqualTo(real);
        assertThat(real).isEqualTo(whole);
        assertThat(whole).hasSameHashCodeAs(real);
        assertThat(new TypedValue.Double(7.5)).isNotEqualTo(whole);
    }

    /** Exactness is {@code asInteger()}'s: a long that a double would round to is not that double. */
    @Test
    void longThatADoubleWouldRoundIsNotThatDouble() {
        final long unrepresentable = (1L << 53) + 1;
        assertThat(new TypedValue.Integer(unrepresentable))
                .isNotEqualTo(new TypedValue.Double((double) (1L << 53)));
    }

    @Test
    void instantEqualityIgnoresTheCarriedOffset() {
        final TypedValue utc = new TypedValue.Instant(1_000, 5, 0);
        final TypedValue plusOne = new TypedValue.Instant(1_000, 5, 3_600);
        assertThat(utc).isEqualTo(plusOne);
        assertThat(utc).hasSameHashCodeAs(plusOne);
        assertThat(new TypedValue.Instant(1_000, 6, 0)).isNotEqualTo(utc);
    }

    @Test
    void differentKindsAreNeverEqual() {
        assertThat(TypedValue.of("7")).isNotEqualTo(new TypedValue.Integer(7));
        assertThat(new TypedValue.Bool(true)).isNotEqualTo(new TypedValue.Integer(1));
        assertThat(TypedValue.of("true")).isNotEqualTo(new TypedValue.Bool(true));
    }
}
