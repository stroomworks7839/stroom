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

package stroom.shapeshifter.engine;

import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.output.XmlByteSink;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.value.TypedValue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's notions of "same value". Pinned in design 35 phase 0 as they stood, then flipped
 * deliberately in phase 1 where canonical-per-type (design 35 §5) changed them — the string-form
 * family — and left as they were where it did not.
 */
class EqualityCharacterisationTest {

    /**
     * {@code distinct-values} compares canonically (design 35 §5, phase 1): a number and its text
     * are two entries. It keyed on {@code asString()} and made them one, until phase 1.
     */
    @Test
    void distinctValuesTellsANumberFromItsText() {
        assertThat(run(distinct(), "1\n1\n")).isEqualTo("1,1,");
    }


    /**
     * The typed {@code eq} already tells a number from its text: a number-cast 7 is <b>not</b>
     * equal to the text {@code "7"}. So canonical-per-type (design 35 §5) changes nothing here —
     * only the legacy {@code equals} alias above compares string forms.
     */
    @Test
    void eqAlreadyTellsANumberFromItsText() {
        assertThat(run(compare("""
                {"eq": {"left": {"ref": {"parts": [{"capture": {"var_id": "n", "group": 0}}]}},
                        "right": {"value": "7"}}}"""), "7\n")).isEqualTo("");
    }

    /**
     * {@code value-map} looks its entry up by value, canonically (design 35 §5, phase 1): a
     * number does not find a text entry and falls to the default. It matched by string form,
     * until phase 1.
     */
    @Test
    void valueMapDoesNotMatchANumberAgainstATextEntry() {
        final String json = """
                {
                  "name": "vm", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "line"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "line",
                     "match": {"regex": {"pattern": "([0-9]+)\\n"}},
                     "captures": [{"name": "n", "select": {"group": 1}, "as": "number"}],
                     "body": [{"value-map": {
                       "select": {"parts": [{"capture": {"var_id": "n", "group": 0}}]},
                       "entries": [{"from": "7", "to": "seven"}], "default": "none"}}]}
                  ]
                }
                """;
        assertThat(run(json, "7\n")).isEqualTo("none");
    }

    /**
     * The byte-backed variants already compare by decoded text: {@code Encoded.equals} short-cuts
     * on identical bytes and encoding, then falls through to {@code Arrays.equals(asUtf8(), …)}.
     * So the same text in two encodings is <b>one</b> value today, and design 35 §5's claim that
     * structural equality distinguished encodings was wrong — canonical-per-type changes nothing
     * here either. Pinned so nobody "fixes" it.
     */
    @Test
    void sameTextInTwoEncodingsIsOneValue() {
        final TypedValue utf8 = TypedValue.of("café".getBytes(StandardCharsets.UTF_8), Encoding.UTF_8);
        final TypedValue utf16 = TypedValue.of("café".getBytes(StandardCharsets.UTF_16BE), Encoding.UTF_16BE);
        assertThat(utf8.asString()).isEqualTo(utf16.asString());
        assertThat(utf8).isEqualTo(utf16);
        assertThat(utf8).hasSameHashCodeAs(utf16);
    }

    private static String distinct() {
        return """
                {
                  "name": "dv", "version": 5,
                  "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [
                       {"sequence": {"name": "items"}},
                       {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                            "mode": "line"}},
                       {"distinct-values": {"select": "items", "name": "seen"}},
                       {"for-each": {"select": "seen", "as": "s", "body": [
                         {"value-of": {"parts": [{"capture": {"var_id": "s", "group": 0}}]}},
                         {"text": ","}]}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "line",
                     "match": {"regex": {"pattern": "([0-9]+)\\n"}},
                     "captures": [{"name": "asText", "select": {"group": 1}},
                                  {"name": "asNumber", "select": {"group": 1}, "as": "number"}],
                     "body": [
                       {"append": {"name": "items",
                                   "select": {"parts": [{"capture": {"var_id": "asText", "group": 0}}]}}},
                       {"append": {"name": "items",
                                   "select": {"parts": [{"capture": {"var_id": "asNumber", "group": 0}}]}}}]}
                  ]
                }
                """;
    }

    private static String compare(final String test) {
        return """
                {
                  "name": "cmp", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "line"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "line",
                     "match": {"regex": {"pattern": "([0-9]+)\\n"}},
                     "captures": [{"name": "n", "select": {"group": 1}, "as": "number"}],
                     "body": [{"if": {"test": TEST, "then": [{"text": "yes"}]}}]}
                  ]
                }
                """.replace("TEST", test);
    }

    private static String run(final String json, final String input) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(out));
        return out.toString(StandardCharsets.UTF_8).replace("\n", "");
    }
}
