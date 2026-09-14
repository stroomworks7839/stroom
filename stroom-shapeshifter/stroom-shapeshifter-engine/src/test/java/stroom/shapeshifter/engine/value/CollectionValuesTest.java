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

import stroom.shapeshifter.engine.config.Cast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The collection variants (design 35 §5, phase 1): values that hold values — mutable, ordered,
 * canonically keyed, with no text form and no place in a cast or a comparison. Nothing in the
 * engine declares one yet; these pin the shape the later phases build on.
 */
class CollectionValuesTest {

    private static TypedValue text(final String value) {
        return TypedValue.of(value);
    }

    @Test
    void listKeepsOrderAndAbsenceAndLastDoesNotSkip() {
        final TypedValue.List list = new TypedValue.List();
        list.append(text("a"));
        list.append(null);
        list.append(text("c"));
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.get(1)).isNull();
        assertThat(list.last()).isEqualTo(text("c"));
        list.append(null);
        // The last entry is absence, and last() says so rather than walking back to "c".
        assertThat(list.last()).isNull();
        assertThat(list.get(9)).isNull();
    }

    @Test
    void listInsertPutAndRemoveShiftAsXpathsDo() {
        final TypedValue.List list = new TypedValue.List();
        for (final String s : new String[] {"a", "b", "d"}) {
            list.append(text(s));
        }
        list.insert(2, text("c"));
        assertThat(list.get(2)).isEqualTo(text("c"));
        assertThat(list.get(3)).isEqualTo(text("d"));
        list.put(0, text("A"));
        assertThat(list.get(0)).isEqualTo(text("A"));
        list.remove(1);
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.get(1)).isEqualTo(text("c"));
        assertThat(list.contains(text("d"))).isTrue();
        assertThat(list.contains(text("b"))).isFalse();
    }

    @Test
    void listGrowsPastItsInitialCapacityAndKeepsWhatItHeld() {
        final TypedValue.List list = new TypedValue.List();
        for (int i = 0; i < 40; i++) {
            list.append(new TypedValue.Integer(i));
        }
        assertThat(list.size()).isEqualTo(40);
        assertThat(list.get(3)).isEqualTo(new TypedValue.Integer(3));
        assertThat(list.get(39)).isEqualTo(new TypedValue.Integer(39));
    }

    @Test
    void collectionsHaveNoTextOrNumericForm() {
        final TypedValue.List list = new TypedValue.List();
        assertThat(list.isEmpty()).isTrue();
        assertThatThrownBy(list::asString).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("list");
        assertThatThrownBy(list::asBytes).isInstanceOf(IllegalStateException.class);
        assertThat(list.asNumber()).isNull();
        assertThat(list.asInteger()).isNull();
        assertThat(list.asBoolean()).isNull();
    }

    /**
     * The cast to text would refuse anyway, through {@code asString()}; the cast to a number is
     * the one that matters, because without the guard it would answer <i>absent</i> and say
     * nothing.
     */
    @Test
    void castRefusesACollectionAndComparisonHasNoOrderForOne() {
        final TypedValue.Map map = new TypedValue.Map();
        assertThatThrownBy(() -> Comparisons.cast(map, Cast.NUMBER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("map");
        assertThatThrownBy(() -> Comparisons.cast(map, Cast.STRING))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Comparisons.compare(map, text("x"))).isNull();
        assertThat(Comparisons.compare(text("x"), new TypedValue.Set())).isNull();
    }

    @Test
    void mapKeysCompareCanonicallyAndKeepInsertionOrder() {
        final TypedValue.Map map = new TypedValue.Map();
        map.put(new TypedValue.Integer(7), text("seven"));
        map.put(text("b"), text("bee"));
        map.put(text("a"), text("ay"));
        assertThat(map.get(new TypedValue.Double(7.0))).isEqualTo(text("seven"));
        assertThat(map.get(text("7"))).isNull();
        assertThat(map.contains(new TypedValue.Double(7.0))).isTrue();
        assertThat(map.size()).isEqualTo(3);
        assertThat(map.keys().get(1)).isEqualTo(text("b"));
        assertThat(map.values().get(2)).isEqualTo(text("ay"));
        map.remove(text("b"));
        assertThat(map.contains(text("b"))).isFalse();
        map.clear();
        assertThat(map.isEmpty()).isTrue();
    }

    @Test
    void setMembershipIsCanonicalAndOrdered() {
        final TypedValue.Set set = new TypedValue.Set();
        set.add(text("b"));
        set.add(new TypedValue.Integer(1));
        set.add(new TypedValue.Double(1.0));
        set.add(text("b"));
        assertThat(set.size()).isEqualTo(2);
        assertThat(set.contains(new TypedValue.Double(1.0))).isTrue();
        assertThat(set.contains(text("1"))).isFalse();
        assertThat(set.values().get(0)).isEqualTo(text("b"));
        set.remove(text("b"));
        assertThat(set.size()).isEqualTo(1);
    }

    @Test
    void collectionIsRefusedAsAKeyOrMember() {
        final TypedValue.Map map = new TypedValue.Map();
        final TypedValue.Set set = new TypedValue.Set();
        assertThatThrownBy(() -> map.put(new TypedValue.List(), text("x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("map key");
        assertThatThrownBy(() -> set.add(map))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("set member");
        // But a collection may be a map's value, which is what makes them nest.
        map.put(text("k"), new TypedValue.List());
        assertThat(map.get(text("k"))).isInstanceOf(TypedValue.List.class);
    }

    @Test
    void collectionsCompareDeeply() {
        final TypedValue.List one = new TypedValue.List();
        final TypedValue.List two = new TypedValue.List();
        one.append(new TypedValue.Integer(1));
        two.append(new TypedValue.Double(1.0));
        assertThat(one).isEqualTo(two);
        two.append(null);
        assertThat(one).isNotEqualTo(two);
    }
}
