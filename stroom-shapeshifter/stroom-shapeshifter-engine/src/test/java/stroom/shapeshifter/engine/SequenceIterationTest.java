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
    // The memory contract (design/16 §10)
    // -----------------------------------------------------------------------------------

    /** Runs without asserting the messages are clean, so a fatal can be inspected. */
    private static List<Message> messagesFrom(final String json, final String input) {
        return Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                OutputSink.of(new ByteArrayOutputStream()));
    }

    private static String withSource(final String extra, final String epilogue) {
        return config(epilogue, APPEND_FIELD)
                .replace("\"version\": 5,", "\"version\": 5, \"source\": {" + extra + "},");
    }

    @Test
    void sequenceOutgrowingItsLimitStopsTheRun() {
        // Fatal rather than truncate-and-warn: a summary missing its tail is a wrong answer
        // that looks right, which is worse than one that does not arrive.
        final String json = withSource("\"max_sequence_entries\": 3", "{\"text\": \"\"}");
        final List<Message> messages = messagesFrom(json, "a\nb\nc\nd\n");
        assertThat(messages)
                .anyMatch(m -> m.severity() == Severity.FATAL
                               && m.text().contains("items")
                               && m.text().contains("max_sequence_entries (3)"));
    }

    @Test
    void sequenceInsideItsLimitIsUntouched() {
        final String json = withSource("\"max_sequence_entries\": 3", "{\"count\": {\"select\": \"items\"}}");
        assertThat(messagesFrom(json, "a\nb\nc\n"))
                .noneMatch(m -> m.severity() == Severity.FATAL);
    }

    @Test
    void accumulatingUnderAChunkedRootStopsTheRun() {
        // A classify root is read in pieces whose counters restart, so an accumulation there
        // would summarise the last piece while presenting itself as a summary of the input.
        final String json = withSource(
                "\"dispatch\": \"classify\", \"buffer_size\": 8", "{\"text\": \"\"}");
        assertThat(messagesFrom(json, "aaaa\nbbbb\ncccc\ndddd\n"))
                .anyMatch(m -> m.severity() == Severity.FATAL
                               && m.text().contains("classify or any root"));
    }

    @Test
    void perRecordSequenceIsFineUnderAChunkedRoot() {
        // It crosses no record boundary, so it cannot be summarised wrongly — and was being
        // refused fatally for a hazard it does not have (phase 6 audit).
        final String json = """
                {"name": "t", "version": 5,
                 "source": {"dispatch": "classify", "buffer_size": 8},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source",
                   "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                             "mode": "doc"}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "match": {"regex": {"pattern": "([^\\n]*)\\n"}},
                   "captures": [{"name": "f", "select": {"group": 1}}],
                   "body": [
                     {"tokenize": {"select": [{"parts": [{"capture": {"var_id": "f",
                        "group": 0}}]}], "delimiter": ",", "name": "parts"}},
                     {"for-each": {"select": "parts", "as": "p", "body": [
                        {"value-of": {"parts": [{"capture": {"var_id": "p", "group": 0}}]}},
                        {"text": "."}]}}]}]}
                """;
        assertThat(messagesFrom(json, "a,b\n")).noneMatch(m -> m.severity() == Severity.FATAL);
    }

    @Test
    void orderedRootAccumulatesNormally() {
        final String json = withSource("\"buffer_size\": 8", "{\"count\": {\"select\": \"items\"}}");
        assertThat(messagesFrom(json, "aaaa\nbbbb\n"))
                .noneMatch(m -> m.severity() == Severity.FATAL);
    }

    // -----------------------------------------------------------------------------------
    // Keys (design/16 §8)
    // -----------------------------------------------------------------------------------

    /** Builds a key over the items and looks one value up in it. */
    private static String lookup(final String wanted) {
        final String epilogue = """
                {"key": {"name": "by_value", "select": "items"}},
                {"key-get": {"key": "by_value",
                  "select": {"parts": [{"text": "%s"}]}, "name": "hits"}},
                {"count": {"select": "hits"}},
                {"text": ":"},
                {"for-each": {"select": "hits", "as": "h", "body": [
                  {"value-of": {"parts": [{"capture": {"var_id": "field", "group": 0,
                     "match_index": {"var_ref": "h"}}}]}},
                  {"text": ","}]}}
                """.formatted(wanted);
        return run(config(epilogue, APPEND_FIELD), "a\nb\na\nc\n");
    }

    @Test
    void keyReachesItsEntriesByValue() {
        assertThat(lookup("a")).isEqualTo("2:a,a,");
        assertThat(lookup("c")).isEqualTo("1:c,");
    }

    @Test
    void lookupThatMissesIsAnEmptySequenceNotAnError() {
        // The same non-answer XSLT's key() gives: a walk runs zero times and count says 0,
        // which is what lets an author self-close the empty case rather than discover it
        // after the opening tag has gone out.
        assertThat(lookup("zzz")).isEqualTo("0:");
    }

    @Test
    void keyAndASequenceMayShareAName() {
        // Keys are their own namespace; nothing at a use site can confuse the two.
        final String epilogue = """
                {"key": {"name": "items", "select": "items"}},
                {"key-get": {"key": "items",
                  "select": {"parts": [{"text": "b"}]}, "name": "hits"}},
                {"count": {"select": "hits"}}
                """;
        assertThat(run(config(epilogue, APPEND_FIELD), "a\nb\n")).isEqualTo("1");
    }

    @Test
    void anAbsentLookupFindsTheEntriesThatHadNoKey() {
        // The symmetry grouping uses: absence is a value one can ask about, not an
        // exclusion. XSLT returns empty for key('k', ()); this engine is consistent with its
        // own grouping rule instead (phase 5 audit — a decision, now pinned). The lookup
        // value is an empty literal, which "empty is absent" makes absent.
        final String json = """
                {"name": "t", "version": 5,
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source",
                   "body": [
                     {"sequence": {"name": "items"}},
                     {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                       "mode": "doc"}},
                     {"key": {"name": "by_cat", "select": "items",
                       "group_by": {"parts": [{"capture": {"var_id": "cat", "group": 0,
                          "match_index": {"var_ref": "__index"}}}]}}},
                     {"key-get": {"key": "by_cat",
                       "select": {"parts": [{"text": ""}]}, "name": "hits"}},
                     {"count": {"select": "hits"}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "match": {"regex": {"pattern": "(?:(x)|y)\\n"}},
                   "captures": [{"name": "cat", "select": {"group": 1}}],
                   "body": [{"append": {"name": "items", "select": {"parts": [
                      {"capture": {"var_id": "__match_count", "group": 0}}]}}}]}]}
                """;
        // Two records have no category; an absent lookup finds exactly those.
        assertThat(run(json, "x\ny\ny\n")).isEqualTo("2");
    }

    @Test
    void lookingUpInAKeyNothingBuildsIsRefused() {
        final String epilogue = """
                {"key-get": {"key": "nosuch",
                  "select": {"parts": [{"text": "a"}]}, "name": "hits"}}
                """;
        assertThatThrownBy(() -> Shapeshifter.compile(
                ProjectReader.read(config(epilogue, APPEND_FIELD))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("nosuch");
    }

    // -----------------------------------------------------------------------------------
    // Grouping (design/16 §6)
    // -----------------------------------------------------------------------------------

    /** Emits each group as key:members, members read back through the index set. */
    private static final String GROUP_BODY = """
            {"for-each-group": {"select": "items", "body": [
              {"value-of": {"parts": [{"capture": {"var_id": "__group_key", "group": 0}}]}},
              {"text": ":"},
              {"value-of": {"parts": [{"capture": {"var_id": "__group_size", "group": 0}}]}},
              {"text": "("},
              {"for-each": {"select": "__group", "as": "i", "body": [
                 {"value-of": {"parts": [{"capture": {"var_id": "field", "group": 0,
                    "match_index": {"var_ref": "i"}}}]}}]}},
              {"text": ") "}]}}
            """;

    @Test
    void groupsFormInOrderOfFirstAppearance() {
        // XSLT's rule, and the one a log summary wants.
        assertThat(run(config(GROUP_BODY, APPEND_FIELD), "b\na\nb\nc\na\n"))
                .isEqualTo("b:2(bb) a:2(aa) c:1(c) ");
    }

    @Test
    void theGroupIsAnIndexSetSoParallelStoresAreReachable() {
        // The members are store indices, so reading a *different* capture at each one is how
        // current-group() is reached without a tree.
        final String record = "{\"append\": {\"name\": \"items\", \"select\": {\"parts\": ["
                + "{\"capture\": {\"var_id\": \"__match_count\", \"group\": 0}}]}}}";
        final String body = """
                {"for-each-group": {"select": "items",
                  "group_by": {"parts": [{"capture": {"var_id": "field", "group": 0,
                     "match_index": {"var_ref": "__index"}}}]},
                  "body": [
                    {"value-of": {"parts": [{"capture": {"var_id": "__group_key", "group": 0}}]}},
                    {"text": "="},
                    {"value-of": {"parts": [{"capture": {"var_id": "__group_size", "group": 0}}]}},
                    {"text": " "}]}}
                """;
        assertThat(run(config(body, record), "x\ny\nx\n")).isEqualTo("x=2 y=1 ");
    }

    @Test
    void groupSizeIsKnownBeforeTheGroupOpens() {
        // Which is what lets a count be written *before* the members — the thing the engine
        // could not do when adjacent_groups found its trailing-empty-group limit.
        final String body = """
                {"for-each-group": {"select": "items", "body": [
                  {"text": "<g "},
                  {"value-of": {"parts": [{"capture": {"var_id": "__group_size", "group": 0}}]}},
                  {"text": ">"}]}}
                """;
        assertThat(run(config(body, APPEND_FIELD), "a\na\nb\n")).isEqualTo("<g 2><g 1>");
    }

    @Test
    void entriesWithNoKeyFormTheirOwnGroup() {
        // A deliberate divergence from XSLT, which excludes an item whose group-by yields an
        // empty sequence. Silently dropping records is the wrong default for a log engine:
        // "the ones with no category" is a thing worth summarising, so absence is a group,
        // and its __group_key reads absent.
        final String json = """
                {"name": "t", "version": 5,
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source",
                   "body": [
                     {"sequence": {"name": "items"}},
                     {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                       "mode": "doc"}},
                     {"for-each-group": {"select": "items",
                       "group_by": {"parts": [{"capture": {"var_id": "cat", "group": 0,
                          "match_index": {"var_ref": "__index"}}}]},
                       "body": [
                       {"text": "["},
                       {"value-of": {"parts": [{"capture": {"var_id": "__group_key",
                          "group": 0}}]}},
                       {"text": "="},
                       {"value-of": {"parts": [{"capture": {"var_id": "__group_size",
                          "group": 0}}]}},
                       {"text": "]"}]}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "match": {"regex": {"pattern": "(?:(x)|y)\\n"}},
                   "captures": [{"name": "cat", "select": {"group": 1}}],
                   "body": [{"append": {"name": "items", "select": {"parts": [
                      {"capture": {"var_id": "__match_count", "group": 0}}]}}}]}]}
                """;
        // The sequence carries positions, so the key is read *at* each record rather than
        // as "the latest", which is what a bare reference means and would have made the
        // keyless record inherit its predecessor's category.
        assertThat(run(json, "x\ny\nx\n")).isEqualTo("[x=2][=1]");
    }

    @Test
    void groupKeyCannotSeeTheGroupItIsForming() {
        // The key is what forms the group, so this grouping's own names are not available to
        // it — the same mistake as reading a position in a sort key (phase 4 audit).
        final String body = """
                {"for-each-group": {"select": "items",
                  "group_by": {"parts": [{"capture": {"var_id": "__group_key", "group": 0}}]},
                  "body": []}}
                """;
        assertThat(Shapeshifter.compile(ProjectReader.read(config(body, APPEND_FIELD))).warnings())
                .anyMatch(m -> m.text().contains("__group_key outside any for-each-group"));
    }

    @Test
    void groupingNamesOutsideAGroupingDrawTheLint() {
        final String json = config("{\"text\": \"\"}",
                "{\"value-of\": {\"parts\": [{\"capture\": {\"var_id\": \"__group_key\","
                + " \"group\": 0}}]}}");
        assertThat(Shapeshifter.compile(ProjectReader.read(json)).warnings())
                .anyMatch(m -> m.text().contains("__group_key outside any for-each-group"));
    }

    @Test
    void walkingTheGroupOutsideAGroupingDrawsTheLint() {
        // __group is writable everywhere, being a name the engine sets, so the sequence
        // check cannot catch this on its own.
        final String json = config(
                "{\"for-each\": {\"select\": \"__group\", \"body\": []}}", APPEND_FIELD);
        assertThat(Shapeshifter.compile(ProjectReader.read(json)).warnings())
                .anyMatch(m -> m.text().contains("walks __group outside any for-each-group"));
    }

    // -----------------------------------------------------------------------------------
    // Sorting (design/16 §5)
    // -----------------------------------------------------------------------------------

    private static String sortedBy(final String sortKeys, final String input) {
        final String epilogue = """
                {"for-each": {"select": "items", "as": "item", "sort": [%s], "body": [
                  {"value-of": {"parts": [{"capture": {"var_id": "item", "group": 0}}]}},
                  {"text": ","}]}}
                """.formatted(sortKeys);
        return run(config(epilogue, APPEND_FIELD), input);
    }

    private static final String BY_ITEM =
            "{\"by\": {\"parts\": [{\"capture\": {\"var_id\": \"item\", \"group\": 0}}]}}";

    @Test
    void anUncastKeyOrdersByStringForm() {
        // Which is why 10 comes before 9: the total reading, not a numeric one.
        assertThat(sortedBy(BY_ITEM, "9\n10\n2\n")).isEqualTo("10,2,9,");
    }

    @Test
    void numberCastOrdersNumerically() {
        final String key = BY_ITEM.replace("}}]}}", "}}]}, \"as\": \"number\"}");
        assertThat(sortedBy(key, "9\n10\n2\n")).isEqualTo("2,9,10,");
    }

    @Test
    void descendingReversesIt() {
        final String key = BY_ITEM.replace("}}]}}", "}}]}, \"as\": \"number\", \"order\": \"descending\"}");
        assertThat(sortedBy(key, "9\n10\n2\n")).isEqualTo("10,9,2,");
    }

    @Test
    void absentKeysSortLastInEitherDirection() {
        // Unparseable entries end up together at the bottom either way, rather than
        // migrating to the top when the order flips and reading as data.
        final String ascending = BY_ITEM.replace("}}]}}", "}}]}, \"as\": \"number\"}");
        final String descending = BY_ITEM.replace("}}]}}",
                "}}]}, \"as\": \"number\", \"order\": \"descending\"}");
        assertThat(sortedBy(ascending, "5\nn/a\n1\n")).isEqualTo("1,5,n/a,");
        assertThat(sortedBy(descending, "5\nn/a\n1\n")).isEqualTo("5,1,n/a,");
    }

    @Test
    void tiesKeepDataOrder() {
        // A constant key makes every entry tie, so what comes out is what stability gives:
        // ascending store index, which is data order. That is the tie-break, said once by
        // the sort being stable rather than twice by an explicit fallback.
        final String constant = "{\"by\": {\"parts\": [{\"text\": \"same\"}]}}";
        assertThat(sortedBy(constant, "b\na\nc\na\n")).isEqualTo("b,a,c,a,");
    }

    @Test
    void laterKeyBreaksTheEarlierKeysTies() {
        final String constant = "{\"by\": {\"parts\": [{\"text\": \"same\"}]}}";
        assertThat(sortedBy(constant + ", " + BY_ITEM, "b\na\nc\na\n"))
                .isEqualTo("a,a,b,c,");
    }

    @Test
    void positionFollowsTheOrderingWhileTheIndexStillPointsAtTheRecord() {
        final String key = BY_ITEM.replace("}}]}}", "}}]}, \"as\": \"number\"}");
        final String epilogue = """
                {"for-each": {"select": "items", "as": "item", "sort": [%s], "body": [
                  {"value-of": {"parts": [
                     {"capture": {"var_id": "__position", "group": 0}},
                     {"text": ":"},
                     {"capture": {"var_id": "__index", "group": 0}},
                     {"text": " "}]}}]}}
                """.formatted(key);
        // Sorted 2,9,10 came from store indices 3,1,2 — position renumbers, index does not.
        assertThat(run(config(epilogue, APPEND_FIELD), "9\n10\n2\n")).isEqualTo("1:3 2:1 3:2 ");
    }

    @Test
    void sortKeyCannotSeeAPositionAndIsToldSo() {
        // Nothing has a position until the keys have been compared — and in a nested walk the
        // key would otherwise resolve outward and read the enclosing walk's position, which
        // is a meaningless value that looks like a real one (phase 3 audit).
        final String key = "{\"by\": {\"parts\": [{\"capture\": {\"var_id\": \"__position\","
                + " \"group\": 0}}]}}";
        final String epilogue = """
                {"for-each": {"select": "items", "as": "item", "sort": [%s], "body": [
                  {"value-of": {"parts": [{"capture": {"var_id": "item", "group": 0}}]}}]}}
                """.formatted(key);
        final var compiled = Shapeshifter.compile(ProjectReader.read(config(epilogue, APPEND_FIELD)));
        assertThat(compiled.warnings())
                .anyMatch(m -> m.text().contains("__position in a sort key"));
    }

    @Test
    void sortKeyReadingTheIndexDrawsNothing() {
        // __index names the record, which is known before any comparison, and is how a key
        // reaches a parallel store.
        final String key = "{\"by\": {\"parts\": [{\"capture\": {\"var_id\": \"field\","
                + " \"group\": 0, \"match_index\": {\"var_ref\": \"__index\"}}}]}}";
        final String epilogue = """
                {"for-each": {"select": "items", "sort": [%s], "body": []}}
                """.formatted(key);
        assertThat(Shapeshifter.compile(ProjectReader.read(config(epilogue, APPEND_FIELD)))
                .warnings())
                .noneMatch(m -> m.text().contains("sort key"));
    }

    @Test
    void sortKeyCanReadAParallelStoreAtTheSameEntry() {
        // The key is evaluated with __index bound, so it can order by a sibling field.
        final String key = "{\"by\": {\"parts\": [{\"capture\": {\"var_id\": \"field\","
                + " \"group\": 0, \"match_index\": {\"var_ref\": \"__index\"}}}]},"
                + " \"as\": \"number\"}";
        assertThat(sortedBy(key, "9\n10\n2\n")).isEqualTo("2,9,10,");
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
