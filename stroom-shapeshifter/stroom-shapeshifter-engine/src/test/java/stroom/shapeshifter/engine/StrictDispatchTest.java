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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D36's dispatch modes, behaviourally: the cursor moves only by matching at it, skipping is
 * authored, and the modes are named points in the unbundled control-flow space
 * ({@code design/11-strict-dispatch.md}).
 */
class StrictDispatchTest {

    private record Run(String output, List<Message> messages) {

    }

    private static Run run(final String json, final String input) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                OutputSink.of(output));
        return new Run(output.toString(StandardCharsets.UTF_8), messages);
    }

    /** A version-4 project: one source template applying mode "row", plus the given rows. */
    private static String project(final int version, final String dispatch, final String rows) {
        return """
                {
                  "name": "dispatch", "version": VERSION,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"DISPATCH}}]},
                    ROWS
                  ]
                }
                """
                .replace("VERSION", String.valueOf(version))
                .replace("DISPATCH", dispatch == null ? "" : ", \"dispatch\": \"" + dispatch + "\"")
                .replace("ROWS", rows);
    }

    private static String row(final String id, final String name, final String pattern,
                              final String extra, final String body) {
        return """
                {"id": "00000000-0000-0000-0000-0000000000ID", "name": "NAME", "mode": "row",
                 "match": {"regex": {"pattern": "PATTERN"}}EXTRA,
                 "body": [BODY]}"""
                .replace("ID", id).replace("NAME", name).replace("PATTERN", pattern)
                .replace("EXTRA", extra).replace("BODY", body);
    }

    private static String emit(final String tag) {
        return """
                {"value-of": {"parts": [{"text": "[TAG:"}, {"capture": {"group": 1}}, {"text": "]"}]}}"""
                .replace("TAG", tag);
    }

    // -----------------------------------------------------------------------------------
    // The version gate and the strict rule
    // -----------------------------------------------------------------------------------

    @Test
    void versionThreeStaysLaxAndSkipsWithAReport() {
        // The migrated era: an unanchored match past the cursor consumes the skip, reported.
        final Run result = run(project(3, null,
                row("02", "field", "x=([0-9])", "", emit("X"))), "junk x=1");
        assertThat(result.output()).isEqualTo("[X:1]");
        assertThat(result.messages()).anyMatch(m ->
                m.text().contains("failed to match from the start"));
    }

    @Test
    void versionFourIsStrictAndTheCursorNeverMovesImplicitly() {
        // Same configuration, new era: nothing matches at the cursor, nothing moves, and the
        // content is reported unmatched instead of silently searched.
        final Run result = run(project(4, null,
                row("02", "field", "x=([0-9])", "", emit("X"))), "junk x=1");
        assertThat(result.output()).isEmpty();
        assertThat(result.messages()).anyMatch(m ->
                m.text().contains("failed to match all of the content"));
    }

    @Test
    void strictLevelIteratesWithAnAuthoredLineEater() {
        // The idiomatic strict shape: anchored fields, and a consume-marked line eater whose
        // body says what it swallowed. Skipping is visible, intentional, and reported by the
        // author rather than the engine.
        final Run result = run(project(4, null,
                row("02", "field", "a=([0-9])\\n", "", emit("A")) + ",\n"
                + row("03", "junk_line", "[^\\n]*\\n", ", \"consume\": true",
                        """
                        {"emit-error": {"severity": "warning",
                         "message": {"parts": [{"text": "unrecognised line: "},
                                               {"capture": {"group": 0}}]}}}""")),
                "a=1\nzzz\na=2\n");
        assertThat(result.output()).isEqualTo("[A:1][A:2]");
        assertThat(result.messages()).anyMatch(m ->
                m.severity() == Severity.WARNING && m.text().equals("unrecognised line: zzz\n"));
        assertThat(result.messages()).noneMatch(m ->
                m.text().contains("failed to match all of the content"));
    }

    @Test
    void eatersAreExemptFromMatchLimits() {
        // An eater's matches do not count, so a match limit cannot exhaust it: three junk
        // fields fall to one eater declared with max_match 1.
        final Run result = run(project(4, null,
                row("02", "eat", "x;", ", \"match_limits\": {\"max_match\": 1}, \"consume\": true", "")),
                "x;x;x;");
        assertThat(result.output()).isEmpty();
        assertThat(result.messages()).noneMatch(m -> m.severity() == Severity.ERROR);
    }

    @Test
    void zeroAdvanceIsAnErrorNotASilentStop() {
        // A pattern that matches empty cannot move the level: a grammar bug, said loudly.
        final Run result = run(project(3, null,
                row("02", "empty", "x*", "", emit("X"))), "yyy");
        assertThat(result.output()).isEmpty();
        assertThat(result.messages()).anyMatch(m ->
                m.severity() == Severity.ERROR && m.text().contains("matched without advancing"));
    }

    // -----------------------------------------------------------------------------------
    // The other modes
    // -----------------------------------------------------------------------------------

    @Test
    void classifyRunsEveryMatchingTemplateAndConsumesNothing() {
        final Run result = run(project(4, "classify",
                row("02", "has_a", "a", "", "{\"value-of\": {\"parts\": [{\"text\": \"[A]\"}]}}") + ",\n"
                + row("03", "has_b", "b", "", "{\"value-of\": {\"parts\": [{\"text\": \"[B]\"}]}}") + ",\n"
                + row("04", "has_c", "c", "", "{\"value-of\": {\"parts\": [{\"text\": \"[C]\"}]}}")),
                "xaxb");
        assertThat(result.output()).isEqualTo("[A][B]");
        // A mode that consumes nothing cannot leave anything unmatched.
        assertThat(result.messages()).noneMatch(m -> m.severity() == Severity.ERROR);
    }

    @Test
    void lexerTakesTheLongestMatchAndTiesGoToListOrder() {
        // "ab" then "a": under strict's first-match the short template would shadow the long
        // one; maximal munch picks the longest at each position.
        final Run result = run(project(4, "lexer",
                row("02", "short", "a", "", "{\"value-of\": {\"parts\": [{\"text\": \"[A]\"}]}}") + ",\n"
                + row("03", "long", "ab", "", "{\"value-of\": {\"parts\": [{\"text\": \"[AB]\"}]}}")),
                "aba");
        assertThat(result.output()).isEqualTo("[AB][A]");
    }

    @Test
    void fatalEmissionAbortsTheRun() {
        final Run result = run(project(4, null,
                row("02", "field", "a=([0-9])\\n", "", emit("A") + ",\n"
                        + """
                        {"emit-error": {"severity": "fatal",
                         "message": {"parts": [{"text": "stop everything"}]}}}""")),
                "a=1\na=2\n");
        // The first match emitted, the fatal fired, the second record never ran.
        assertThat(result.output()).isEqualTo("[A:1]");
        assertThat(result.messages().getLast().severity()).isEqualTo(Severity.FATAL);
        assertThat(result.messages().getLast().text()).isEqualTo("stop everything");
    }

    // -----------------------------------------------------------------------------------
    // Compile-time rules
    // -----------------------------------------------------------------------------------

    @Test
    void consumeTemplatesMayNotDeclareCaptures() {
        final String json = project(4, null,
                row("02", "eater", "[^\\n]*\\n",
                        ", \"consume\": true, \"captures\": [{\"name\": \"junk\", \"select\": {\"group\": 0}}]", ""));
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(json)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("eater")
                .hasMessageContaining("no index to bind");
    }

    @Test
    void anyDispatchIsRefusedUntilItExists() {
        final String json = project(4, "any", row("02", "field", "x", "", ""));
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(json)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("E18");
    }

    @Test
    void lineAnchoredPatternsInStrictLevelsDrawAWarning() {
        final Run result = run(project(4, null,
                row("02", "field", "(?m)^a=([0-9])\\n", "", emit("A"))), "a=1\n");
        // The pattern still works — ^ holds at the cursor — but the compiler says what the
        // line anchor does not do here.
        assertThat(result.output()).isEqualTo("[A:1]");
        assertThat(result.messages()).anyMatch(m ->
                m.severity() == Severity.WARNING && m.text().contains("line-anchored"));
    }
}
