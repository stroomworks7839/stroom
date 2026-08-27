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
 * Iteration over a sequence (design/16 phase 1): the walk itself, what it binds, the
 * lifetime that makes it possible, and the two compile-time checks that keep an
 * accumulation's lifetime visible.
 */
class SequenceIterationTest {

    /**
     * A configuration whose record template captures a field per line, hoists it into a
     * declared sequence, and — after the level that fills it has finished, which is what
     * makes the summary possible at all — walks it.
     */
    private static String config(final String epilogue, final String recordBody) {
        return """
                {"name": "t", "version": 5,
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source",
                   "body": [
                     {"sequence": {"name": "items"}},
                     {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                       "mode": "doc"}},
                     %s]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "match": {"regex": {"pattern": "([^\\\\n]*)\\\\n"}},
                   "captures": [{"name": "field", "select": {"group": 1}}],
                   "body": [%s]}]}
                """.formatted(epilogue, recordBody);
    }

    private static final String APPEND_FIELD =
            "{\"append\": {\"name\": \"items\", \"select\": {\"parts\": ["
            + "{\"capture\": {\"var_id\": \"field\", \"group\": 0}}]}}}";

    private static String run(final String json, final String input) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                OutputSink.of(out));
        assertThat(messages).noneMatch(m -> m.severity() == Severity.ERROR
                                            || m.severity() == Severity.FATAL);
        return out.toString(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------------------
    // The walk
    // -----------------------------------------------------------------------------------

    @Test
    void iterationRunsTheBodyOncePerEntryInOrder() {
        final String epilogue = """
                {"for-each": {"select": "items", "as": "item", "body": [
                  {"text": "<i>"},
                  {"value-of": {"parts": [{"capture": {"var_id": "item", "group": 0}}]}},
                  {"text": "</i>"}]}}
                """;
        assertThat(run(config(epilogue, APPEND_FIELD), "a\nb\nc\n"))
                .isEqualTo("<i>a</i><i>b</i><i>c</i>");
    }

    @Test
    void positionAndLastAreBoundAndLastIsKnownBeforeTheFirstEntry() {
        final String epilogue = """
                {"for-each": {"select": "items", "body": [
                  {"value-of": {"parts": [
                     {"capture": {"var_id": "__position", "group": 0}},
                     {"text": "/"},
                     {"capture": {"var_id": "__last", "group": 0}},
                     {"text": " "}]}}]}}
                """;
        assertThat(run(config(epilogue, APPEND_FIELD), "a\nb\nc\n"))
                .isEqualTo("1/3 2/3 3/3 ");
    }

    @Test
    void theIndexReachesAParallelStoreAtTheSameEntry() {
        // Two captures of one template share an index, which is the whole of current-group():
        // the sequence carries positions, and $field[$__index] reads the record they name.
        final String record = APPEND_FIELD.replace("\"select\": {\"parts\": ["
                        + "{\"capture\": {\"var_id\": \"field\", \"group\": 0}}]}",
                "\"select\": {\"parts\": [{\"capture\": {\"var_id\": \"__match_count\", \"group\": 0}}]}");
        final String epilogue = """
                {"for-each": {"select": "items", "as": "at", "body": [
                  {"value-of": {"parts": [
                     {"capture": {"var_id": "field", "group": 0,
                       "match_index": {"var_ref": "at"}}},
                     {"text": ","}]}}]}}
                """;
        assertThat(run(config(epilogue, record), "x\ny\nz\n")).isEqualTo("x,y,z,");
    }

    @Test
    void isFirstAndIsLastAreExactInsideAnIteration() {
        final String epilogue = """
                {"for-each": {"select": "items", "as": "item", "body": [
                  {"if": {"test": {"is-first": {}}, "then": [{"text": "["}]}},
                  {"value-of": {"parts": [{"capture": {"var_id": "item", "group": 0}}]}},
                  {"if": {"test": {"is-last": {}}, "then": [{"text": "]"}]}},
                  {"if": {"test": {"not": {"is-last": {}}}, "then": [{"text": ","}]}}]}}
                """;
        assertThat(run(config(epilogue, APPEND_FIELD), "a\nb\nc\n")).isEqualTo("[a,b,c]");
    }

    @Test
    void anEmptySequenceRunsTheBodyNoTimes() {
        final String epilogue = """
                {"text": "<none/>"}, {"for-each": {"select": "items", "body": [{"text": "x"}]}}
                """;
        assertThat(run(config(epilogue, "{\"text\": \"\"}"), "a\n")).isEqualTo("<none/>");
    }

    @Test
    void anAbsentValueAppendsNothingRatherThanAHole() {
        // The optional capture never matches, so the sequence stays empty and the iteration
        // has nothing to walk — no hole, because a dense index is a position.
        final String json = """
                {"name": "t", "version": 5,
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source",
                   "body": [
                     {"sequence": {"name": "items"}},
                     {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                       "mode": "doc"}},
                     {"for-each": {"select": "items", "body": [{"text": "x"}]}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "match": {"regex": {"pattern": "([^\\n]*)\\n"}},
                   "captures": [{"name": "never", "select": {"group": 2}}],
                   "body": [{"append": {"name": "items", "select": {"parts": [
                      {"capture": {"var_id": "never", "group": 0}}]}}}]}]}
                """;
        assertThat(run(json, "a\nb\n")).isEmpty();
    }

    @Test
    void nestedIterationsShadowRatherThanOverwrite() {
        final String epilogue = """
                {"for-each": {"select": "items", "as": "outer", "body": [
                  {"value-of": {"parts": [{"capture": {"var_id": "outer", "group": 0}}]}},
                  {"text": "("},
                  {"for-each": {"select": "items", "as": "inner", "body": [
                     {"value-of": {"parts": [{"capture": {"var_id": "inner", "group": 0}}]}}]}},
                  {"text": ")"},
                  {"value-of": {"parts": [{"capture": {"var_id": "outer", "group": 0}}]}}]}}
                """;
        assertThat(run(config(epilogue, APPEND_FIELD), "a\nb\n"))
                .isEqualTo("a(ab)ab(ab)b");
    }

    // -----------------------------------------------------------------------------------
    // The folds (design/16 §8)
    // -----------------------------------------------------------------------------------

    private static String fold(final String instruction, final String input) {
        return run(config(instruction, APPEND_FIELD), input);
    }

    @Test
    void countIsThePopulatedEntries() {
        assertThat(fold("{\"count\": {\"select\": \"items\"}}", "a\nb\nc\n")).isEqualTo("3");
    }

    @Test
    void sumIsExactOverWholeNumbersAndPromotesOverFractions() {
        assertThat(fold("{\"sum\": {\"select\": \"items\"}}", "2\n3\n4\n")).isEqualTo("9");
        assertThat(fold("{\"sum\": {\"select\": \"items\"}}", "1.5\n2.5\n")).isEqualTo("4");
    }

    @Test
    void theEmptySequenceAnswersAsXpathDoes() {
        // Not the same answer twice: a total of nothing is zero, a mean of nothing is not a
        // number, and returning zero for it would be a number that looks like an answer.
        assertThat(fold("{\"sum\": {\"select\": \"items\"}}", "")).isEqualTo("0");
        assertThat(fold("{\"avg\": {\"select\": \"items\"}}", "")).isEmpty();
        assertThat(fold("{\"count\": {\"select\": \"items\"}}", "")).isEqualTo("0");
    }

    @Test
    void avgIsTheMean() {
        assertThat(fold("{\"avg\": {\"select\": \"items\"}}", "1\n2\n3\n")).isEqualTo("2");
        assertThat(fold("{\"avg\": {\"select\": \"items\"}}", "1\n2\n")).isEqualTo("1.5");
    }

    @Test
    void nonNumericEntryMakesSumAndAvgAbsent() {
        assertThat(fold("{\"sum\": {\"select\": \"items\"}}", "1\nn/a\n")).isEmpty();
        assertThat(fold("{\"avg\": {\"select\": \"items\"}}", "1\nn/a\n")).isEmpty();
    }

    @Test
    void minAndMaxOrderByStringFormUntilToldOtherwise() {
        // Uncast is the string reading, where "9" is larger than "10" — the documented
        // total ordering (17 §8), not a bug. as:number is how an author says otherwise.
        assertThat(fold("{\"max\": {\"select\": \"items\"}}", "9\n10\n")).isEqualTo("9");
        assertThat(fold("{\"max\": {\"select\": \"items\", \"as\": \"number\"}}", "9\n10\n"))
                .isEqualTo("10");
        assertThat(fold("{\"min\": {\"select\": \"items\", \"as\": \"number\"}}", "9\n10\n"))
                .isEqualTo("9");
    }

    @Test
    void anEntryThatFailsItsCastDoesNotParticipate() {
        // The same "did not participate" that reads false in a condition and sorts last.
        assertThat(fold("{\"max\": {\"select\": \"items\", \"as\": \"number\"}}", "5\nn/a\n7\n"))
                .isEqualTo("7");
        assertThat(fold("{\"max\": {\"select\": \"items\", \"as\": \"number\"}}", "n/a\n")).isEmpty();
    }

    @Test
    void distinctValuesKeepsFirstAppearanceOrder() {
        final String epilogue = """
                {"distinct-values": {"select": "items", "name": "seen"}},
                {"for-each": {"select": "seen", "as": "s", "body": [
                  {"value-of": {"parts": [{"capture": {"var_id": "s", "group": 0}}]}},
                  {"text": ","}]}}
                """;
        assertThat(run(config(epilogue, APPEND_FIELD), "b\na\nb\nc\na\n")).isEqualTo("b,a,c,");
    }

    @Test
    void distinctValuesCanRebindItsOwnSource() {
        // The entries are read out before the target is cleared, so a fold onto its own
        // source is not self-destructive — worth pinning, since the target is cleared first.
        final String epilogue = """
                {"distinct-values": {"select": "items", "name": "items"}},
                {"for-each": {"select": "items", "as": "s", "body": [
                  {"value-of": {"parts": [{"capture": {"var_id": "s", "group": 0}}]}}]}}
                """;
        assertThat(run(config(epilogue, APPEND_FIELD), "b\na\nb\n")).isEqualTo("ba");
    }

    @Test
    void tokenizeBindsASequenceAndStillWritesJoined() {
        // Design/17 §16.4's ruling, which has been waiting on sequences existing.
        final String bindThenWalk = """
                {"tokenize": {"select": [{"parts": [{"text": "a,b,c"}]}],
                  "delimiter": ",", "name": "parts"}},
                {"for-each": {"select": "parts", "as": "p", "body": [
                  {"text": "<"},
                  {"value-of": {"parts": [{"capture": {"var_id": "p", "group": 0}}]}},
                  {"text": ">"}]}}
                """;
        assertThat(run(config(bindThenWalk, APPEND_FIELD), "x\n")).isEqualTo("<a><b><c>");
        // Unnamed, it writes what it always wrote.
        final String write = """
                {"tokenize": {"select": [{"parts": [{"text": "a,b"}]}], "delimiter": ","}}
                """;
        assertThat(run(config(write, APPEND_FIELD), "x\n")).isEqualTo("a\nb");
    }

    // -----------------------------------------------------------------------------------
    // The checks that keep a lifetime visible (design/16 §9)
    // -----------------------------------------------------------------------------------

    @Test
    void anInstructionWithNothingToReadIsRefusedByName() {
        // Found by the phase 2 audit: tokenize and parse-date take their select by getFirst(),
        // so an empty one reached the author as a NoSuchElementException from inside the
        // compiler. Arity is refused by name now, for every one-input instruction.
        for (final String node : List.of(
                "{\"tokenize\": {\"select\": [], \"delimiter\": \",\"}}",
                "{\"parse-date\": {\"select\": [], \"pattern\": \"iso\"}}",
                "{\"upper-case\": {\"select\": []}}")) {
            assertThatThrownBy(() -> Shapeshifter.compile(
                    ProjectReader.read(config("{\"text\": \"\"}", node))))
                    .as("%s", node)
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("exactly one select");
        }
    }

    @Test
    void appendingToAnUndeclaredSequenceIsRefusedByName() {
        final String json = config("{\"text\": \"\"}", APPEND_FIELD).replace(
                "{\"sequence\": {\"name\": \"items\"}},", "");
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(json)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("items")
                .hasMessageContaining("no sequence");
    }

    @Test
    void sequenceNamedAfterACaptureIsRefused() {
        final String json = config("{\"text\": \"\"}", APPEND_FIELD)
                .replace("\"name\": \"items\"", "\"name\": \"field\"");
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(json)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("field")
                .hasMessageContaining("clears its captures");
    }

    @Test
    void walkingSomethingNothingWritesIsRefused() {
        final String epilogue = "{\"for-each\": {\"select\": \"nosuch\", \"body\": []}}";
        assertThatThrownBy(() -> Shapeshifter.compile(
                ProjectReader.read(config(epilogue, APPEND_FIELD))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("nosuch");
    }

    @Test
    void positionalConditionsOutsideAnIterationDrawTheLint() {
        final String json = config("{\"text\": \"\"}",
                "{\"if\": {\"test\": {\"is-first\": {}}, \"then\": [{\"text\": \"x\"}]}}");
        assertThat(Shapeshifter.compile(ProjectReader.read(json)).warnings())
                .anyMatch(m -> m.text().contains("is-first outside any for-each"));
    }

    @Test
    void readingAnIterationVariableOutsideAnIterationDrawsTheLint() {
        // The conditions' hazard applies to the variables too, and is worse for an index:
        // absence makes $x[$__index] fall back to the first entry rather than to nothing.
        final String json = config("{\"text\": \"\"}",
                "{\"value-of\": {\"parts\": [{\"capture\": {\"var_id\": \"__position\","
                + " \"group\": 0}}]}}");
        assertThat(Shapeshifter.compile(ProjectReader.read(json)).warnings())
                .anyMatch(m -> m.text().contains("__position outside any for-each"));
    }

    @Test
    void anIndexReferenceOutsideAnIterationDrawsItToo() {
        final String json = config("{\"text\": \"\"}",
                "{\"value-of\": {\"parts\": [{\"capture\": {\"var_id\": \"field\","
                + " \"group\": 0, \"match_index\": {\"var_ref\": \"__index\"}}}]}}");
        assertThat(Shapeshifter.compile(ProjectReader.read(json)).warnings())
                .anyMatch(m -> m.text().contains("__index outside any for-each"));
    }

    @Test
    void appendingDuringAWalkDoesNotExtendIt() {
        // The entries are snapshotted before the body runs, so a body that appends to the
        // sequence it is walking terminates. The alternative is a loop that never ends.
        final String epilogue = """
                {"for-each": {"select": "items", "as": "item", "body": [
                  {"value-of": {"parts": [{"capture": {"var_id": "item", "group": 0}}]}},
                  {"append": {"name": "items", "select": {"parts": [{"text": "extra"}]}}}]}}
                """;
        assertThat(run(config(epilogue, APPEND_FIELD), "a\nb\n")).isEqualTo("ab");
    }

    @Test
    void positionalConditionsInsideAnIterationDrawNothing() {
        final String epilogue = """
                {"for-each": {"select": "items", "body": [
                  {"if": {"test": {"is-first": {}}, "then": [{"text": "x"}]}}]}}
                """;
        assertThat(Shapeshifter.compile(ProjectReader.read(config(epilogue, APPEND_FIELD)))
                .warnings())
                .noneMatch(m -> m.text().contains("outside any for-each"));
    }
}
