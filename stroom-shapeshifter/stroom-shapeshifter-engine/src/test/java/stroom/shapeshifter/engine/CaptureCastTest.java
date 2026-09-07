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

import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.output.XmlByteSink;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A capture declares its kind (design 25 §9, D50): the cast is applied once at bind, a cast
 * that fails is absent, and every consumer sees the kind without casting again.
 */
class CaptureCastTest {

    /** The current match's slot, so a record that bound nothing does not read the one before. */
    private static final String CURRENT = "\"match_index\": {\"index\": 0, \"is_offset\": true}";

    /** Rows of one capture, `n`, from group 1; the body is the caller's. */
    private static String config(final String as, final String body) {
        return """
                {
                  "name": "cast", "version": 5,
                  "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                     "match": {"regex": {"pattern": "([^\\n]*)\\n"}},
                     "captures": [{"name": "n", "select": {"group": 1}AS}],
                     "body": [BODY]}
                  ]
                }
                """.replace("AS", as == null ? "" : ", \"as\": \"" + as + "\"").replace("BODY", body);
    }

    private static String current() {
        return "{\"capture\": {\"var_id\": \"n\", \"group\": 0, " + CURRENT + "}}";
    }

    private static List<Message> warnings(final String config) {
        return Shapeshifter.compile(ProjectReader.read(config)).warnings();
    }

    private static String run(final String config, final String input) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(output));
        assertThat(messages).noneMatch(m -> m.severity() == Severity.FATAL);
        return output.toString(StandardCharsets.UTF_8);
    }

    /** Writes {@code big;} when {@code n} is greater than the literal, with no cast on the operand. */
    private static String greaterThan(final String literal) {
        return "{\"if\": {\"test\": {\"gt\": {\"left\": {\"ref\": {\"parts\": [" + current() + "]}},"
               + " \"right\": {\"value\": " + literal + "}}}, \"then\": [{\"text\": \"big;\"}]}},"
               + " {\"text\": \".\"}";
    }

    @Test
    void integerCaptureComparesNativelyWithoutACastOnTheOperand() {
        assertThat(run(config("integer", greaterThan("9")), "10\n5\n")).isEqualTo("big;..");
        // Uncast, the bytes against a number are cross-kind, and the strict rule says false.
        assertThat(run(config(null, greaterThan("9")), "10\n5\n")).isEqualTo("..");
        assertThat(run(config("double", greaterThan("9.5")), "9.75\n9.25\n")).isEqualTo("big;..");
        assertThat(run(config("number", greaterThan("9")), "10\n9.5\n")).isEqualTo("big;.big;.");
    }

    @Test
    void castThatFailsIsAbsent() {
        final String body = "{\"if\": {\"test\": {\"exists\": {\"select\": {\"parts\": [" + current() + "]}}},"
                            + " \"then\": [{\"text\": \"has;\"}]}}, {\"value-of\": {\"parts\": [" + current()
                            + "]}}, {\"text\": \"|\"}";
        // Nothing binds: exists is false and a value-of writes nothing.
        assertThat(run(config("integer", body), "abc\n")).isEqualTo("|");
        // The slot is the record's own, not the one before.
        assertThat(run(config("integer", body), "10\nabc\n7\n")).isEqualTo("has;10||has;7|");
    }

    @Test
    void booleanCaptureIsTheLexicalReading() {
        final String body = "{\"value-of\": {\"parts\": [" + current() + "]}}, {\"text\": \"|\"}";
        assertThat(run(config("boolean", body), "1\nyes\nfalse\n")).isEqualTo("true||false|");
    }

    @Test
    void dateCaptureOrdersOnTheTimeline() {
        // 2026-01-02T00:00:00+01:00 is 2026-01-01T23:00:00Z: before the literal on the timeline,
        // after it as a string.
        final String body = "{\"if\": {\"test\": {\"lt\": {\"left\": {\"ref\": {\"parts\": [" + current() + "]}},"
                            + " \"right\": {\"value\": \"2026-01-01T23:30:00Z\", \"as\": \"date\"}}},"
                            + " \"then\": [{\"text\": \"before;\"}]}}, {\"text\": \"|\"}";
        assertThat(run(config("date", body), "2026-01-02T00:00:00+01:00\n")).isEqualTo("before;|");
        assertThat(run(config("string", body), "2026-01-02T00:00:00+01:00\n")).isEqualTo("|");
    }

    @Test
    void stringCaptureKeepsItsBytesAndReadsAsText() {
        final String config = config("string", "{\"value-of\": {\"parts\": [" + current() + "]}}")
                .replace("\"encoding\": \"utf-8\"", "\"encoding\": \"windows-1252\"");
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream(new byte[]{(byte) 0x93, '\n'}), new XmlByteSink(output));
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("“");
    }

    @Test
    void selectAndKeyValueCapturesBindThroughTheCompiledReference() {
        final String config = """
                {
                  "name": "compiled", "version": 5,
                  "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                     "match": {"regex": {"pattern": "(\\\\w+) (\\\\w+) (\\\\w+)\\n"}},
                     "captures": [
                       {"name": "joined", "select": {"select": {"parts": [
                          {"capture": {"group": 1}}, {"text": "-"}, {"capture": {"group": 2}}, {"text": "+"},
                          {"capture": {"var_id": "joined", "group": 0,
                                       "match_index": {"index": -1, "is_offset": true}}}]}}},
                       {"name": "ignored", "as": "integer", "select": {"key-value": {
                          "key_ref": {"parts": [{"capture": {"group": 1}}]},
                          "value_ref": {"parts": [{"capture": {"group": 3}}]}}}}],
                     "body": [{"value-of": {"parts": [{"capture": {"var_id": "joined", "group": 0}}]}},
                              {"text": "="}, {"value-of": {"parts": [{"capture": {"var_id": "k", "group": 0,
                                 "match_index": {"index": 0, "is_offset": true}}}]}},
                              {"text": "|"}]}
                  ]
                }
                """;
        // The composite is a group, a literal and the stored variable's previous value; the
        // key-value binding under "k" takes the cast: 42 binds, "x" is absent.
        assertThat(run(config, "k v 42\nk w x\n")).isEqualTo("k-v+=42|k-w+k-v+=|");
    }

    @Test
    void lintKnowsADeclaredKind() {
        // Design 17 §8's warning is for an uncast capture against a typed literal; a capture that
        // declares its kind is exactly the intended comparison, and draws none.
        assertThat(warnings(config("integer", greaterThan("9"))))
                .noneMatch(m -> m.text().contains("uncast reference"));
        assertThat(warnings(config(null, greaterThan("9"))))
                .anyMatch(m -> m.text().contains("uncast reference"));
        // The mirror image: a text literal against a declared kind is cross-kind, false always.
        final String textEq = "{\"if\": {\"test\": {\"eq\": {\"left\": {\"ref\": {\"parts\": ["
                              + current() + "]}}, \"right\": {\"value\": \"10\"}}},"
                              + " \"then\": [{\"text\": \"same\"}]}}";
        assertThat(warnings(config("integer", textEq)))
                .anyMatch(m -> m.text().contains("declared as integer"));
        assertThat(run(config("integer", textEq), "10\n")).isEmpty();
    }

    @Test
    void unknownCastIsRefusedByName() {
        assertThatThrownBy(() -> ProjectReader.read(config("float", "{\"text\": \"x\"}")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("float");
    }
}
