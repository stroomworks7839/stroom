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

package stroom.shapeshifter.config.json;

import stroom.shapeshifter.config.ConfigException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tree's two rules the mapping leans on: a number is its spelling, because the spelling
 * is a literal's declared type (design 17 §8); and an id is text of one shape.
 */
class JsonTreeTest {

    @Test
    void wholeAndFractionalStayApart() {
        assertThat(JsonNumber.of(80).isIntegralNumber()).isTrue();
        assertThat(JsonNumber.of(80.0).isIntegralNumber()).isFalse();
        assertThat(JsonNumber.of(80.0).literal()).isEqualTo("80.0");
        assertThat(new JsonNumber("1e3").isIntegralNumber()).isFalse();
        assertThat(new JsonNumber("1e3").asDouble()).isEqualTo(1000.0);
        assertThat(new JsonNumber("42").asLong()).isEqualTo(42L);
    }

    @Test
    void numberReadsAsTextBySpellingButNotTheReverse() {
        assertThat(JsonNumber.of(7).asString()).isEqualTo("7");
        assertThatThrownBy(() -> new JsonString("7").asInt()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wholeNumberFieldRefusesAFraction() {
        final JsonObject node = new JsonObject().put("n", 2.5);
        assertThatThrownBy(() -> JsonFields.integer(node, "n", "test"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("whole number");
        assertThat(JsonFields.integer(new JsonObject().put("n", 2), "n", "test")).isEqualTo(2);
    }

    @Test
    void anIdIsEightFourFourFourTwelve() {
        assertThat(JsonFields.isUuid("00000000-0000-0000-0000-0000000000ff")).isTrue();
        assertThat(JsonFields.isUuid("00000000-0000-0000-0000-0000000000FF")).isTrue();
        assertThat(JsonFields.isUuid("00000000-0000-0000-0000-0000000000f")).isFalse();
        assertThat(JsonFields.isUuid("00000000_0000-0000-0000-0000000000ff")).isFalse();
        assertThat(JsonFields.isUuid("0000000g-0000-0000-0000-0000000000ff")).isFalse();
        assertThatThrownBy(() -> JsonFields.uuid(new JsonString("not-an-id"), "template id"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Not a valid id");
    }

    @Test
    void objectsKeepInsertionOrderAndNullsAreNullNodes() {
        final JsonObject node = new JsonObject().put("b", "1").put("a", (JsonValue) null);
        assertThat(node.entries()).extracting(java.util.Map.Entry::getKey).containsExactly("b", "a");
        assertThat(node.get("a")).isSameAs(JsonNull.NULL);
        assertThat(node.has("a")).isTrue();
        assertThat(JsonFields.optional(node, "a")).isNull();
    }
}
