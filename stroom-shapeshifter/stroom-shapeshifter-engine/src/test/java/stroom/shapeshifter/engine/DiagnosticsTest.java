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

import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.ProjectReader;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design/17 §10's diagnostics and §7's version gate, phase 5: the unknown-reference
 * refusal (which found the win_sec family's dead reads on its first corpus run), the
 * opt-in strict_values warning, and substring going 1-based behind version 5.
 */
class DiagnosticsTest {

    private static String config(final int version, final String sourceExtra, final String body) {
        return """
                {"name": "t", "version": %d,
                 "source": {"buffer_size": 1000%s},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source",
                   "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                             "mode": "doc"}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "match": {"regex": {"pattern": "([^\\\\n]*)\\\\n"}},
                   "captures": [{"name": "field", "select": {"group": 1}}],
                   "body": [%s]}]}
                """.formatted(version, sourceExtra, body);
    }

    private static Outcome run(final String json, final String input) {
        final CompiledProject compiled = Shapeshifter.compile(ProjectReader.read(json));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(compiled,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                OutputSink.of(out));
        return new Outcome(out.toString(StandardCharsets.UTF_8), messages);
    }

    private record Outcome(String output, List<Message> messages) {
    }

    // -----------------------------------------------------------------------------------
    // The unknown-reference refusal
    // -----------------------------------------------------------------------------------

    @Test
    void unknownReferenceIsRefusedNamingItAndItsTemplate() {
        final String body = "{\"value-of\": {\"parts\": [{\"capture\": {\"var_id\": \"feild\", \"group\": 0}}]}}";
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(config(4, "", body))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("feild")
                .hasMessageContaining("line");
    }

    @Test
    void knownNamesPassIncludingEngineCounters() {
        final String body = "{\"value-of\": {\"parts\": ["
                + "{\"capture\": {\"var_id\": \"field\", \"group\": 0}},"
                + "{\"capture\": {\"var_id\": \"__match_count\", \"group\": 0}}]}}";
        assertThat(run(config(4, "", body), "a\n").messages()).isEmpty();
    }

    @Test
    void anUnknownIndexVariableIsRefusedToo() {
        final String body = "{\"value-of\": {\"parts\": [{\"capture\": {\"var_id\": \"field\","
                + " \"group\": 0, \"match_index\": {\"var_ref\": \"missing_idx\"}}}]}}";
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(config(4, "", body))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("missing_idx");
    }

    @Test
    void keyValueCapturesStandTheCheckDown() {
        // Key-value captures bind names read out of the data, so the writable set is not
        // statically knowable: any read is legal in such a configuration.
        final String json = """
                {"name": "t", "version": 4,
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source",
                   "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                             "mode": "doc"}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "pair", "mode": "doc",
                   "match": {"regex": {"pattern": "(\\\\w+)=(\\\\w+)\\\\n"}},
                   "captures": [{"name": "kv", "select": {"key-value": {
                     "key_ref": {"parts": [{"capture": {"group": 1}}]},
                     "value_ref": {"parts": [{"capture": {"group": 2}}]}}}}],
                   "body": [{"value-of": {"parts": [{"capture": {"var_id": "auid", "group": 0}}]}}]}]}
                """;
        assertThat(Shapeshifter.compile(ProjectReader.read(json))).isNotNull();
    }

    // -----------------------------------------------------------------------------------
    // strict_values
    // -----------------------------------------------------------------------------------

    private static final String ADD_BODY =
            "{\"add\": {\"select\": [{\"parts\": [{\"capture\": {\"var_id\": \"field\", \"group\": 0}}]},"
            + " {\"parts\": [{\"text\": \"1\"}]}]}}";

    @Test
    void strictValuesWarnsOncePerInstructionSite() {
        // Two non-numeric records, one arithmetic site: one warning, not a flood.
        final Outcome outcome = run(config(4, ", \"strict_values\": true", ADD_BODY), "abc\nxyz\n");
        assertThat(outcome.messages())
                .filteredOn(m -> m.text().contains("strict_values"))
                .hasSize(1)
                .allMatch(m -> m.severity() == Severity.WARNING && m.text().contains("add"));
    }

    @Test
    void strictValuesIsOffByDefault() {
        assertThat(run(config(4, "", ADD_BODY), "abc\n").messages())
                .noneMatch(m -> m.text().contains("strict_values"));
    }

    // -----------------------------------------------------------------------------------
    // Version 5: substring goes 1-based
    // -----------------------------------------------------------------------------------

    private static final String SUBSTRING_BODY =
            "{\"substring\": {\"select\": [{\"parts\": [{\"capture\": {\"var_id\": \"field\","
            + " \"group\": 0}}]}], \"start\": 1, \"length\": 3}}";

    @Test
    void substringIsOneBasedFromVersionFive() {
        // The same instruction, one position apart: the gate's semantics from both sides.
        assertThat(run(config(4, "", SUBSTRING_BODY), "abcdef\n").output()).isEqualTo("bcd");
        assertThat(run(config(5, "", SUBSTRING_BODY), "abcdef\n").output()).isEqualTo("abc");
    }

    private static final String SUBSTRING_ZERO_BODY =
            "{\"substring\": {\"select\": [{\"parts\": [{\"capture\": {\"var_id\": \"field\","
            + " \"group\": 0}}]}], \"start\": 0, \"length\": 3}}";

    @Test
    void versionFiveStartBelowOneShrinksTheWindowLikeXpath() {
        // XPath: substring("abcdef", 0, 3) is "ab" — the window [0,3) intersected with the
        // string, not three characters from the front. Found by the phase 5 audit.
        assertThat(run(config(5, "", SUBSTRING_ZERO_BODY), "abcdef\n").output()).isEqualTo("ab");
        // Version 4 keeps the ported 0-based reading untouched: three from the front.
        assertThat(run(config(4, "", SUBSTRING_ZERO_BODY), "abcdef\n").output()).isEqualTo("abc");
    }

    @Test
    void theBumpWarningFiresBelowFiveAndOnlyThere() {
        assertThat(run(config(4, "", SUBSTRING_BODY), "abcdef\n").messages())
                .anyMatch(m -> m.severity() == Severity.WARNING && m.text().contains("0-based"));
        assertThat(run(config(3, "", SUBSTRING_BODY), "abcdef\n").messages())
                .anyMatch(m -> m.text().contains("0-based"));
        assertThat(run(config(5, "", SUBSTRING_BODY), "abcdef\n").messages())
                .noneMatch(m -> m.text().contains("0-based"));
    }

    @Test
    void configurationWithoutSubstringDrawsNoBumpWarning() {
        final String body = "{\"value-of\": {\"parts\": [{\"capture\": {\"var_id\": \"field\", \"group\": 0}}]}}";
        assertThat(run(config(4, "", body), "a\n").messages())
                .noneMatch(m -> m.text().contains("0-based"));
    }
}
