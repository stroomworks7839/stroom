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
import stroom.shapeshifter.engine.fixture.EngineHarness;
import stroom.shapeshifter.engine.fixture.EngineHarness.Outcome;

import org.junit.jupiter.api.Test;
import org.xml.sax.helpers.DefaultHandler;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 21 phase 2b: the compiler refuses what it can see, the sink refuses what it cannot,
 * and a structured body serialises as Stroom would.
 */
class StructureTest {

    private static String project(final String body) {
        return """
                {"name": "structure", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "root", "match": "source",
                   "body": [%s]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "lines",
                   "match": {"regex": {"pattern": "([a-z]+)=([^\\n]*)\\n"}},
                   "body": [{"element": {"name": "pair", "body": [
                      {"attribute": {"name": "key", "body": [{"value-of": {"parts": [{"capture": {"group": 1}}]}}]}},
                      {"value-of": {"parts": [{"capture": {"group": 2}}]}}]}}]}
                 ]}
                """.formatted(body);
    }

    private static final String APPLY = """
            {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "lines"}}""";

    /**
     * E37: the document template's body runs over no match, so a capture read there was silently
     * empty; the compiler refuses it by name. The apply-templates select is the idiom and stays,
     * and a variable the body binds itself is readable there as anywhere.
     */
    @Test
    void captureReadInTheDocumentTemplatesBodyIsACompileError() {
        final String positional = project(APPLY + ", {\"value-of\": {\"parts\": [{\"capture\": {\"group\": 0}}]}}");
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(positional)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Template 'root' reads capture group 0 in its body")
                .hasMessageContaining("empty for ever");
        // The select is the idiom and exempt; a parameter's value beside it is a read like any other.
        final String named = project("{\"apply-templates\": {\"select\": {\"parts\": [{\"capture\": {\"group\": 0}}]},"
                                     + " \"mode\": \"lines\", \"with-param\": [[\"p\","
                                     + " {\"parts\": [{\"capture\": {\"group\": 1}}]}]]}}");
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(named)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("reads capture group 1 in its body");
        assertThat(Shapeshifter.compile(ProjectReader.read(project(APPLY)))).isNotNull();
        final String variable = project(
                "{\"variable\": {\"name\": \"v\", \"body\": [{\"text\": \"x\"}]}}, "
                + "{\"value-of\": {\"parts\": [{\"capture\": {\"var_id\": \"v\", \"group\": 0}}]}}, "
                + APPLY);
        assertThat(Shapeshifter.compile(ProjectReader.read(variable))).isNotNull();
    }

    /** A named template reached from the document template's body runs over the same no-match. */
    @Test
    void captureReadInATemplateTheDocumentTemplateCallsIsACompileError() {
        final String helper = """
                {"name": "e37", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "root", "match": "source",
                   "body": [%s %s]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "lines",
                   "match": {"regex": {"pattern": "([a-z]+)=([^\\n]*)\\n"}},
                   "body": [{"call-template": {"name": "outer", "with-param": []}}]},
                  {"id": "00000000-0000-0000-0000-000000000003", "name": "outer", "match": "named",
                   "body": [{"call-template": {"name": "inner", "with-param": []}}]},
                  {"id": "00000000-0000-0000-0000-000000000004", "name": "inner", "match": "named",
                   "body": [{"value-of": {"parts": [{"capture": {"group": 2}}]}}]}
                 ]}
                """;
        final String fromRoot = helper.formatted(APPLY + ",",
                "{\"call-template\": {\"name\": \"outer\", \"with-param\": []}}");
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(fromRoot)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(
                        "Template 'root' calls 'outer', which calls 'inner', which reads capture group 2");
        // The same helpers called from the matching template alone are fine.
        assertThat(Shapeshifter.compile(ProjectReader.read(helper.formatted(APPLY, "")))).isNotNull();
    }

    /** A field capture source is read by the model and bound by nothing, so it is refused by name (design 27). */
    @Test
    void fieldCaptureSourceIsRefusedAtCompileTime() {
        final String json = """
                {"name": "field", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "root", "match": "source",
                   "body": [%s]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "lines",
                   "match": {"regex": {"pattern": "([a-z]+)\\n"}},
                   "captures": [{"name": "f", "select": {"field": "x"}}],
                   "body": []}
                 ]}
                """.formatted(APPLY);
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(json)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(
                        "Template 'line' needs a field capture source, which this build does not support");
    }

    @Test
    void anAttributeAfterContentInTheSameBodyIsACompileError() {
        final String json = project("""
                {"element": {"name": "e", "body": [{"text": "x"}, {"attribute": {"name": "late", "body": []}}]}}""");
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(json)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("attribute 'late' follows content in element 'e'");
    }

    @Test
    void anAttributeAfterApplyTemplatesIsACompileErrorAndOneInsideAnIfBeforeContentIsNot() {
        final String late = project("""
                {"element": {"name": "e", "body": [%s,
                   {"namespace": {"prefix": "p", "uri": "urn:p"}}]}}""".formatted(APPLY));
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(late)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("namespace 'p' follows content");

        final String conditional = project("""
                {"variable": {"name": "flag", "body": [{"text": "yes"}]}},
                {"element": {"name": "e", "body": [
                   {"if": {"test": {"exists": {"select": {"parts": [{"capture": {"var_id": "flag", "group": 0}}]}}},
                           "then": [{"attribute": {"name": "when", "body": [{"text": "yes"}]}}]}},
                   {"text": "content"}]}}""");
        assertThat(Shapeshifter.compile(ProjectReader.read(conditional))).isNotNull();
    }

    @Test
    void structureInsideAnAttributeValueIsACompileError() {
        final String json = project("""
                {"element": {"name": "e", "body": [{"attribute": {"name": "a", "body": [
                   {"element": {"name": "no", "body": []}}]}}]}}""");
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(json)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("element 'no' inside the value of attribute 'a'");
    }

    @Test
    void contentThroughApplyTemplatesBeforeAnAttributeIsRefusedAtRunTimeAgainstTheAttribute() {
        // Two child templates, each valid on its own: the first writes text into <e>, the second
        // adds an attribute to it. Only the run knows the order, so only the sink can refuse.
        final String json = """
                {"name": "runtime", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "root", "match": "source",
                   "body": [{"element": {"name": "e", "body": [%s]}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "text-line", "mode": "lines",
                   "match": {"regex": {"pattern": "a=([^\\n]*)\\n"}},
                   "body": [{"value-of": {"parts": [{"capture": {"group": 1}}]}}]},
                  {"id": "00000000-0000-0000-0000-000000000003", "name": "attribute-line", "mode": "lines",
                   "match": {"regex": {"pattern": "b=([^\\n]*)\\n"}},
                   "body": [{"attribute": {"name": "late",
                             "body": [{"value-of": {"parts": [{"capture": {"group": 1}}]}}]}}]}
                 ]}
                """.formatted(APPLY);
        final Outcome outcome = EngineHarness.runProject(json, "a=1\nb=2\n".getBytes(StandardCharsets.UTF_8));
        assertThat(outcome.messages()).anySatisfy(message -> assertThat(message.toString())
                .startsWith("Fatal")
                .contains("attribute 'late'")
                .contains("after the content of <e>"));
    }

    @Test
    void textOutsideAnyElementOnTheEventSinkIsTheRunsLastMessageNotAnException() {
        final String json = project("{\"text\": \"<records/>\"}");
        final List<Message> messages = Shapeshifter.runWhole(
                Shapeshifter.compile(ProjectReader.read(json)),
                "x".getBytes(StandardCharsets.UTF_8),
                new SaxEventSink(new DefaultHandler()));
        assertThat(messages).singleElement().asString()
                .startsWith("Fatal")
                .contains("Output structure")
                .contains("outside any element");
    }

    @Test
    void structuredBodySerialisesAsStroomWouldAndAVariableIsItsOwnDocument() {
        final String json = project("""
                {"text": "<?xml version=\\"1.1\\" encoding=\\"UTF-8\\"?>\\n"},
                {"element": {"name": "e", "namespace": "urn:e", "body": [
                   {"namespace": {"prefix": "p", "uri": "urn:p"}},
                   {"attribute": {"name": "p:kind", "body": [{"text": "a \\"quoted\\" & <thing>"}]}},
                   %s]}}""".formatted(APPLY));
        final Outcome outcome = EngineHarness.runProject(json, "a=1\nb=x<y\n".getBytes(StandardCharsets.UTF_8));
        assertThat(outcome.messages()).isEmpty();
        assertThat(new String(outcome.output(), StandardCharsets.UTF_8)).isEqualTo("""
                <?xml version="1.1" encoding="UTF-8"?>
                <e xmlns="urn:e" xmlns:p="urn:p" p:kind="a &#34;quoted&#34; &amp; &lt;thing&gt;">
                   <pair key="a">1</pair>
                   <pair key="b">x&lt;y</pair>
                </e>
                """);
    }
}
