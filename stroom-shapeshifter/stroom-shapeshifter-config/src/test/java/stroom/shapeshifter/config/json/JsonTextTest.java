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

class JsonTextTest {

    private static final String DOCUMENT = """
            {"name": "t", "n": 5, "f": 5.0, "e": 1e3, "neg": -0.5, "yes": true, "no": false, "nil": null,
             "s": "a\\"b\\\\c\\n\\u00e9\\u0001", "list": [1, "two", [], {}], "obj": {"k": {"deep": []}}}
            """;

    @Test
    void parsesEveryKindAndKeepsNumberSpellings() {
        final JsonValue value = JsonText.parse(DOCUMENT);
        assertThat(value.get("n").asInt()).isEqualTo(5);
        assertThat(value.get("n").isIntegralNumber()).isTrue();
        assertThat(value.get("f").isIntegralNumber()).isFalse();
        assertThat(((JsonNumber) value.get("f")).literal()).isEqualTo("5.0");
        assertThat(((JsonNumber) value.get("e")).literal()).isEqualTo("1e3");
        assertThat(value.get("neg").asDouble()).isEqualTo(-0.5);
        assertThat(value.get("yes").asBoolean()).isTrue();
        assertThat(value.get("nil")).isSameAs(JsonNull.NULL);
        assertThat(value.get("s").asString()).isEqualTo("a\"b\\c\né" + (char) 1);
        assertThat(value.get("list").size()).isEqualTo(4);
        assertThat(value.get("obj").get("k").get("deep").isArray()).isTrue();
    }

    @Test
    void printsCompactAndPrettyAndBothReadBack() {
        final JsonValue value = JsonText.parse(DOCUMENT);
        final String compact = JsonText.print(value);
        assertThat(compact).doesNotContain("\n").doesNotContain(": ");
        assertThat(compact).startsWith("{\"name\":\"t\",\"n\":5,\"f\":5.0,\"e\":1e3");
        assertThat(compact).contains("\"s\":\"a\\\"b\\\\c\\né\\u0001\"");
        assertThat(JsonText.parse(compact)).isEqualTo(value);

        final String pretty = JsonText.printPretty(value);
        assertThat(pretty).startsWith("{\n  \"name\": \"t\",\n  \"n\": 5,\n");
        assertThat(pretty).contains("\"list\": [\n    1,\n    \"two\",\n    [],\n    {}\n  ],");
        assertThat(pretty).endsWith("}\n");
        assertThat(JsonText.parse(pretty)).isEqualTo(value);
    }

    @Test
    void refusesWhatIsNotJsonNamingThePlace() {
        assertThatThrownBy(() -> JsonText.parse("{\"a\": 1,}"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("line 1, column 9");
        assertThatThrownBy(() -> JsonText.parse("{\n  \"a\": tru\n}"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("line 2");
        assertThatThrownBy(() -> JsonText.parse("[1, 2] x"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("after the document");
        assertThatThrownBy(() -> JsonText.parse("{\"a\": 01}"))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> JsonText.parse("\"unterminated"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("end of the document");
        assertThatThrownBy(() -> JsonText.parse("{a: 1}"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("quoted member name");
        assertThatThrownBy(() -> JsonText.parse("\"tab\there\""))
                .isInstanceOf(ConfigException.class).hasMessageContaining("control character");
    }
}
