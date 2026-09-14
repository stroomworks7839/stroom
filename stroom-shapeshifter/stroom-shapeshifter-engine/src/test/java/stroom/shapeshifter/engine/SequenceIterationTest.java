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
 * Iteration over a declared collection (design/16 phase 1, under design 35 phase 4's surface):
 * the walk itself, what it binds, the lifetime that makes it possible, grouping, sorting, the
 * folds as functions, and the checks that keep a collection's use honest.
 */
class SequenceIterationTest {

    /**
     * A configuration whose record template captures a field per line, appends it to a list
     * declared on the source, and — after the level that fills it has finished, which is what
     * makes the summary possible at all — walks it.
     */
    private static String config(final String epilogue, final String recordBody) {
        return """
                {"name": "t", "version": 5,
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "declarations": [{"name": "field", "type": "list"}, {"name": "items", "type": "list"},
                                    {"name": "by_value", "type": "map"}, {"name": "empty", "type": "list"},
                                    {"name": "seen", "type": "set"},
                                    {"name": "parts", "type": "list"},
                                    {"name": "n", "type": "scalar"}],
                   "match": "source",
                   "body": [
                     {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                       "mode": "doc"}},
                     %s]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "declarations": [{"name": "p", "type": "list"}],
                   "match": {"regex": {"pattern": "([^\\n]*)\\n"}},
                   "captures": [{"name": "field", "select": {"group": 1}}],
                   "body": [%s]}]}
                """.formatted(epilogue, recordBody);
    }

    private static final String APPEND_FIELD =
            "{\"append\": {\"name\": \"items\", \"select\": {\"parts\": ["
            + "{\"last\": {\"of\": \"field\"}}]}}}";

    private static String run(final String json, final String input) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(out));
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
                     {"function": {"name": "position"}},
                     {"text": "/"},
                     {"function": {"name": "last"}},
                     {"text": " "}]}}]}}
                """;
        assertThat(run(config(epilogue, APPEND_FIELD), "a\nb\nc\n"))
                .isEqualTo("1/3 2/3 3/3 ");
    }

    @Test
    void theIndexReachesAParallelListAtTheSameEntry() {
        // Two captures of one template share a position, which is the whole of current-group():
        // the list carries positions, and field[at] reads the record they name.
        final String record = APPEND_FIELD.replace("\"select\": {\"parts\": ["
                        + "{\"last\": {\"of\": \"field\"}}]}",
                "\"select\": {\"parts\": [{\"function\": {\"name\": \"matchCount\"}}]}");
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
    void anEmptyListRunsTheBodyNoTimes() {
        final String epilogue = """
                {"text": "<none/>"}, {"for-each": {"select": "items", "body": [{"text": "x"}]}}
                """;
        assertThat(run(config(epilogue, "{\"text\": \"\"}"), "a\n")).isEqualTo("<none/>");
    }

    @Test
    void anAbsentValueAppendsNothingRatherThanAHole() {
        // The optional capture never matches, so the list stays empty and the iteration has
        // nothing to walk: the value was not there, so there was nothing to add. (A capture
        // into a list appends absence to keep positions aligned; an append does not.)
        final String json = """
                {"name": "t", "version": 5,
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "declarations": [{"name": "items", "type": "list"}],
                   "match": "source",
                   "body": [
                     {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                       "mode": "doc"}},
                     {"for-each": {"select": "items", "body": [{"text": "x"}]}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "declarations": [{"name": "never", "type": "scalar"}],
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
                new XmlByteSink(new ByteArrayOutputStream()));
    }

    private static String withSource(final String extra, final String epilogue) {
        return config(epilogue, APPEND_FIELD)
                .replace("\"version\": 5,", "\"version\": 5, \"source\": {" + extra + "},");
    }

    @Test
    void collectionsOutgrowingTheLimitStopTheRun() {
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
    void collectionsInsideTheLimitAreUntouched() {
        // The limit bounds the run's collections together (design 35 §11): three items and
        // three fields is six elements.
        final String json = withSource("\"max_sequence_entries\": 6",
                "{\"value-of\": {\"parts\": [{\"size\": {\"of\": \"items\"}}]}}");
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
    void perRecordListIsFineUnderAChunkedRoot() {
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
                   "declarations": [{"name": "f", "type": "scalar"}, {"name": "parts", "type": "list"}],
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
        final String json = withSource("\"buffer_size\": 8",
                "{\"value-of\": {\"parts\": [{\"size\": {\"of\": \"items\"}}]}}");
        assertThat(messagesFrom(json, "aaaa\nbbbb\n"))
                .noneMatch(m -> m.severity() == Severity.FATAL);
    }

    // -----------------------------------------------------------------------------------
    // A lookup is a map of positions, built where it is written (design 35 §5)
    // -----------------------------------------------------------------------------------

    /** Files every item's position under its value, then looks one value up. */
    private static String lookup(final String wanted) {
        final String epilogue = """
                {"for-each": {"select": "items", "as": "e", "body": [
                  {"if": {"test": {"not": {"exists": {"select": {"parts": [{"get": {"of": "by_value",
                       "key": {"parts": [{"capture": {"var_id": "e", "group": 0}}]}}}]}}}},
                   "then": [{"put": {"name": "by_value",
                       "key": {"parts": [{"capture": {"var_id": "e", "group": 0}}]},
                       "select": {"parts": [{"capture": {"var_id": "empty", "group": 0}}]}}}]}},
                  {"append": {"target": {"parts": [{"get": {"of": "by_value",
                       "key": {"parts": [{"capture": {"var_id": "e", "group": 0}}]}}}]},
                     "select": {"parts": [{"function": {"name": "index"}}]}}}]}},
                {"value-of": {"parts": [{"size": {"of": {"parts": [{"get": {"of": "by_value", "key": "%s"}}]}}}]}},
                {"text": ":"},
                {"for-each": {"select": {"parts": [{"get": {"of": "by_value", "key": "%s"}}]}, "as": "h", "body": [
                  {"value-of": {"parts": [{"capture": {"var_id": "field", "group": 0,
                     "match_index": {"var_ref": "h"}}}]}},
                  {"text": ","}]}}
                """.formatted(wanted, wanted);
        return run(config(epilogue, APPEND_FIELD), "a\nb\na\nc\n");
    }

    @Test
    void mapOfPositionsReachesItsEntriesByValue() {
        assertThat(lookup("a")).isEqualTo("2:a,a,");
        assertThat(lookup("c")).isEqualTo("1:c,");
    }

    @Test
    void lookupThatMissesIsAbsentAndAWalkOverItRunsNoTimes() {
        // The same non-answer XSLT's key() gives: a walk runs zero times and size says 0
        // rather than an error, which is what lets an author self-close the empty case.
        assertThat(lookup("zzz")).isEqualTo("0:");
    }

    @Test
    void mapKeyMustBePresent() {
        // A map's keys are values (design 35 §5): get and contains answer absent for an absent
        // key, so a put under one could never be read back and stops the run instead. (The
        // grouping keeps its own rule — entries with no key form a group — because a walk over
        // groups is not a lookup.) Two records have no category, and the first of them is
        // where the run stops.
        final String json = """
                {"name": "t", "version": 5,
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "declarations": [{"name": "by_cat", "type": "map"}, {"name": "empty", "type": "list"},
                                    {"name": "cat", "type": "list"},
                                    {"name": "items", "type": "list"}],
                   "match": "source",
                   "body": [
                     {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                       "mode": "doc"}},
                     {"for-each": {"select": "items", "as": "e", "body": [
                       {"put": {"name": "by_cat",
                           "key": {"parts": [{"capture": {"var_id": "cat", "group": 0,
                              "match_index": {"function": "index"}}}]},
                           "select": {"parts": [{"capture": {"var_id": "empty", "group": 0}}]}}}]}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "match": {"regex": {"pattern": "(?:(x)|y)\\n"}},
                   "captures": [{"name": "cat", "select": {"group": 1}}],
                   "body": [{"append": {"name": "items", "select": {"parts": [
                      {"function": {"name": "matchCount"}}]}}}]}]}
                """;
        assertThat(messagesFrom(json, "x\ny\ny\n"))
                .anyMatch(m -> m.severity() == Severity.FATAL && m.text().contains("absent key"));
    }

    @Test
    void readingAKeyOfSomethingUndeclaredIsRefusedByName() {
        final String epilogue = """
                {"value-of": {"parts": [{"get": {"of": "nosuch", "key": "a"}}]}}
                """;
        assertThatThrownBy(() -> Shapeshifter.compile(
                ProjectReader.read(config(epilogue, APPEND_FIELD))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("nosuch");
    }

    // -----------------------------------------------------------------------------------
    // Grouping (design/16 §6)
    // -----------------------------------------------------------------------------------

    /** Emits each group as key:members, members read back through the position set. */
    private static final String GROUP_BODY = """
            {"for-each-group": {"select": "items", "body": [
              {"value-of": {"parts": [{"function": {"name": "groupKey"}}]}},
              {"text": ":"},
              {"value-of": {"parts": [{"function": {"name": "groupSize"}}]}},
              {"text": "("},
              {"for-each": {"select": "group()", "as": "i", "body": [
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
    void theGroupIsAPositionSetSoParallelListsAreReachable() {
        // The members are positions, so reading a *different* capture at each one is how
        // current-group() is reached without a tree.
        final String record = "{\"append\": {\"name\": \"items\", \"select\": {\"parts\": ["
                + "{\"function\": {\"name\": \"matchCount\"}}]}}}";
        final String body = """
                {"for-each-group": {"select": "items",
                  "group_by": {"parts": [{"capture": {"var_id": "field", "group": 0,
                     "match_index": {"function": "index"}}}]},
                  "body": [
                    {"value-of": {"parts": [{"function": {"name": "groupKey"}}]}},
                    {"text": "="},
                    {"value-of": {"parts": [{"function": {"name": "groupSize"}}]}},
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
                  {"value-of": {"parts": [{"function": {"name": "groupSize"}}]}},
                  {"text": ">"}]}}
                """;
        assertThat(run(config(body, APPEND_FIELD), "a\na\nb\n")).isEqualTo("<g 2><g 1>");
    }

    @Test
    void entriesWithNoKeyFormTheirOwnGroup() {
        // A deliberate divergence from XSLT, which excludes an item whose group-by yields an
        // empty sequence. Silently dropping records is the wrong default for a log engine:
        // "the ones with no category" is a thing worth summarising, so absence is a group,
        // and its groupKey() reads absent.
        final String json = """
                {"name": "t", "version": 5,
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "declarations": [{"name": "cat", "type": "list"}, {"name": "items", "type": "list"}],
                   "match": "source",
                   "body": [
                     {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                       "mode": "doc"}},
                     {"for-each-group": {"select": "items",
                       "group_by": {"parts": [{"capture": {"var_id": "cat", "group": 0,
                          "match_index": {"function": "index"}}}]},
                       "body": [
                       {"text": "["},
                       {"value-of": {"parts": [{"function": {"name": "groupKey"}}]}},
                       {"text": "="},
                       {"value-of": {"parts": [{"function": {"name": "groupSize"}}]}},
                       {"text": "]"}]}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "match": {"regex": {"pattern": "(?:(x)|y)\\n"}},
                   "captures": [{"name": "cat", "select": {"group": 1}}],
                   "body": [{"append": {"name": "items", "select": {"parts": [
                      {"function": {"name": "matchCount"}}]}}}]}]}
                """;
        // The list carries positions, so the key is read *at* each record rather than as
        // "the last", which would have made the keyless record inherit its predecessor's.
        assertThat(run(json, "x\ny\nx\n")).isEqualTo("[x=2][=1]");
    }

    @Test
    void groupKeyCannotSeeTheGroupItIsForming() {
        // The key is what forms the group, so this grouping's own names are not available to
        // it — the same mistake as reading a position in a sort key (phase 4 audit).
        final String body = """
                {"for-each-group": {"select": "items",
                  "group_by": {"parts": [{"function": {"name": "groupKey"}}]},
                  "body": []}}
                """;
        assertThat(Shapeshifter.compile(ProjectReader.read(config(body, APPEND_FIELD))).warnings())
                .anyMatch(m -> m.text().contains("groupKey() outside any for-each-group"));
    }

    @Test
    void groupingNamesOutsideAGroupingDrawTheLint() {
        final String json = config("{\"text\": \"\"}",
                "{\"value-of\": {\"parts\": [{\"function\": {\"name\": \"groupKey\"}}]}}");
        assertThat(Shapeshifter.compile(ProjectReader.read(json)).warnings())
                .anyMatch(m -> m.text().contains("groupKey() outside any for-each-group"));
    }

    @Test
    void mutatingTheGroupIsRefused() {
        // group() answers the frame's list: walk it, size it, copy it, but a mutation of it
        // would change what the engine says without any variable changing.
        final String body = """
                {"for-each-group": {"select": "items", "body": [
                  {"append": {"target": {"parts": [{"function": {"name": "group"}}]},
                     "select": {"parts": [{"text": "9"}]}}}]}}
                """;
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(config(body, APPEND_FIELD))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("appends to group(), which is a function");
    }

    @Test
    void theGroupCanBeCopiedSizedAndIndexed() {
        // A read of group() is any list read: size, get by position, a copy into a declared list.
        final String body = """
                {"for-each-group": {"select": "items", "body": [
                  {"value-of": {"parts": [{"size": {"of": "group()"}}, {"text": ":"},
                     {"get": {"of": "group()", "key": 1}}, {"text": " "}]}}]}}
                """;
        assertThat(run(config(body, APPEND_FIELD), "b\na\nb\n")).isEqualTo("2:1 1:2 ");
    }

    @Test
    void walkingTheGroupOutsideAGroupingDrawsTheLint() {
        // group() is a function read, and the lint on reading a grouping's function outside a
        // grouping is what catches it — a walk over it is a read of it.
        final String json = config(
                "{\"for-each\": {\"select\": \"group()\", \"body\": []}}", APPEND_FIELD);
        assertThat(Shapeshifter.compile(ProjectReader.read(json)).warnings())
                .anyMatch(m -> m.text().contains("group() outside any for-each-group"));
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
        // ascending position, which is data order. That is the tie-break, said once by the
        // sort being stable rather than twice by an explicit fallback.
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
                     {"function": {"name": "position"}},
                     {"text": ":"},
                     {"function": {"name": "index"}},
                     {"text": " "}]}}]}}
                """.formatted(key);
        // Sorted 2,9,10 came from positions 3,1,2 — position renumbers, index does not.
        assertThat(run(config(epilogue, APPEND_FIELD), "9\n10\n2\n")).isEqualTo("1:3 2:1 3:2 ");
    }

    @Test
    void sortKeyCannotSeeAPositionAndIsToldSo() {
        // Nothing has a position until the keys have been compared — and in a nested walk the
        // key would otherwise resolve outward and read the enclosing walk's position, which
        // is a meaningless value that looks like a real one (phase 3 audit).
        final String key = "{\"by\": {\"parts\": [{\"function\": {\"name\": \"position\"}}]}}";
        final String epilogue = """
                {"for-each": {"select": "items", "as": "item", "sort": [%s], "body": [
                  {"value-of": {"parts": [{"capture": {"var_id": "item", "group": 0}}]}}]}}
                """.formatted(key);
        final var compiled = Shapeshifter.compile(ProjectReader.read(config(epilogue, APPEND_FIELD)));
        assertThat(compiled.warnings())
                .anyMatch(m -> m.text().contains("position() in a sort key"));
    }

    @Test
    void sortKeyReadingTheIndexDrawsNothing() {
        // index() names the record, which is known before any comparison, and is how a key
        // reaches a parallel list.
        final String key = "{\"by\": {\"parts\": [{\"capture\": {\"var_id\": \"field\","
                + " \"group\": 0, \"match_index\": {\"function\": \"index\"}}}]}}";
        final String epilogue = """
                {"for-each": {"select": "items", "sort": [%s], "body": []}}
                """.formatted(key);
        assertThat(Shapeshifter.compile(ProjectReader.read(config(epilogue, APPEND_FIELD)))
                .warnings())
                .noneMatch(m -> m.text().contains("sort key"));
    }

    @Test
    void sortKeyCanReadAParallelListAtTheSameEntry() {
        // The key is evaluated with index() bound, so it can order by a sibling field.
        final String key = "{\"by\": {\"parts\": [{\"capture\": {\"var_id\": \"field\","
                + " \"group\": 0, \"match_index\": {\"function\": \"index\"}}}]},"
                + " \"as\": \"number\"}";
        assertThat(sortedBy(key, "9\n10\n2\n")).isEqualTo("2,9,10,");
    }

    // -----------------------------------------------------------------------------------
    // The folds, as functions over a collection (design 35 §5)
    // -----------------------------------------------------------------------------------

    private static String fold(final String accessor, final String input) {
        return run(config("{\"value-of\": {\"parts\": [" + accessor + "]}}", APPEND_FIELD), input);
    }

    @Test
    void sizeIsTheNumberOfEntries() {
        assertThat(fold("{\"size\": {\"of\": \"items\"}}", "a\nb\nc\n")).isEqualTo("3");
    }

    @Test
    void sumIsExactOverWholeNumbersAndPromotesOverFractions() {
        assertThat(fold("{\"sum\": {\"of\": \"items\"}}", "2\n3\n4\n")).isEqualTo("9");
        assertThat(fold("{\"sum\": {\"of\": \"items\"}}", "1.5\n2.5\n")).isEqualTo("4");
    }

    @Test
    void theEmptyListAnswersAsXpathDoes() {
        // Not the same answer twice: a total of nothing is zero, a mean of nothing is not a
        // number, and returning zero for it would be a number that looks like an answer.
        assertThat(fold("{\"sum\": {\"of\": \"items\"}}", "")).isEqualTo("0");
        assertThat(fold("{\"avg\": {\"of\": \"items\"}}", "")).isEmpty();
        assertThat(fold("{\"size\": {\"of\": \"items\"}}", "")).isEqualTo("0");
    }

    @Test
    void avgIsTheMean() {
        assertThat(fold("{\"avg\": {\"of\": \"items\"}}", "1\n2\n3\n")).isEqualTo("2");
        assertThat(fold("{\"avg\": {\"of\": \"items\"}}", "1\n2\n")).isEqualTo("1.5");
    }

    @Test
    void nonNumericEntryMakesSumAndAvgAbsent() {
        assertThat(fold("{\"sum\": {\"of\": \"items\"}}", "1\nn/a\n")).isEmpty();
        assertThat(fold("{\"avg\": {\"of\": \"items\"}}", "1\nn/a\n")).isEmpty();
    }

    @Test
    void minAndMaxOrderByStringFormUntilToldOtherwise() {
        // Uncast is the string reading, where "9" is larger than "10" — the documented
        // total ordering (17 §8), not a bug. as:number is how an author says otherwise.
        assertThat(fold("{\"max\": {\"of\": \"items\"}}", "9\n10\n")).isEqualTo("9");
        assertThat(fold("{\"max\": {\"of\": \"items\", \"as\": \"number\"}}", "9\n10\n"))
                .isEqualTo("10");
        assertThat(fold("{\"min\": {\"of\": \"items\", \"as\": \"number\"}}", "9\n10\n"))
                .isEqualTo("9");
    }

    @Test
    void anEntryThatFailsItsCastDoesNotParticipate() {
        // The same "did not participate" that reads false in a condition and sorts last.
        assertThat(fold("{\"max\": {\"of\": \"items\", \"as\": \"number\"}}", "5\nn/a\n7\n"))
                .isEqualTo("7");
        assertThat(fold("{\"max\": {\"of\": \"items\", \"as\": \"number\"}}", "n/a\n")).isEmpty();
    }

    @Test
    void setKeepsFirstAppearanceOrderAndDropsRepeats() {
        // What distinct-values used to do, as a declared set filled by a walk (design 35 §5).
        final String epilogue = """
                {"for-each": {"select": "items", "as": "v", "body": [
                  {"put": {"name": "seen", "select": {"parts": [{"capture": {"var_id": "v", "group": 0}}]}}}]}},
                {"for-each": {"select": "seen", "as": "s", "body": [
                  {"value-of": {"parts": [{"capture": {"var_id": "s", "group": 0}}]}},
                  {"text": ","}]}}
                """;
        assertThat(run(config(epilogue, APPEND_FIELD), "b\na\nb\nc\na\n")).isEqualTo("b,a,c,");
    }

    @Test
    void tokenizeBindsAListAndStillWritesJoined() {
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
    // The checks that keep a collection's use honest
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
    void appendingToAnUndeclaredNameIsRefusedByName() {
        final String json = config("{\"text\": \"\"}", APPEND_FIELD.replace("items", "nosuch"));
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(json)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("nosuch")
                .hasMessageContaining("no declaration names");
    }

    @Test
    void appendingToAScalarIsRefusedByType() {
        final String json = config("{\"text\": \"\"}", APPEND_FIELD.replace("items", "n"));
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(json)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("appends to 'n'")
                .hasMessageContaining("declared as a scalar");
    }

    @Test
    void walkingSomethingUndeclaredIsRefused() {
        final String epilogue = "{\"for-each\": {\"select\": \"nosuch\", \"body\": []}}";
        assertThatThrownBy(() -> Shapeshifter.compile(
                ProjectReader.read(config(epilogue, APPEND_FIELD))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("nosuch");
    }

    @Test
    void readingAListWhereTextIsWantedIsRefused() {
        // A collection has no text form; the read that used to be bare is last(l).
        final String epilogue = "{\"value-of\": {\"parts\": [{\"capture\": {\"var_id\": \"items\", \"group\": 0}}]}}";
        assertThatThrownBy(() -> Shapeshifter.compile(
                ProjectReader.read(config(epilogue, APPEND_FIELD))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("reads 'items'")
                .hasMessageContaining("get or last");
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
        final String json = config("{\"text\": \"\"}",
                "{\"value-of\": {\"parts\": [{\"function\": {\"name\": \"position\"}}]}}");
        assertThat(Shapeshifter.compile(ProjectReader.read(json)).warnings())
                .anyMatch(m -> m.text().contains("position() outside any for-each"));
    }

    @Test
    void anIndexReferenceOutsideAnIterationDrawsItToo() {
        final String json = config("{\"text\": \"\"}",
                "{\"value-of\": {\"parts\": [{\"capture\": {\"var_id\": \"field\","
                + " \"group\": 0, \"match_index\": {\"function\": \"index\"}}}]}}");
        assertThat(Shapeshifter.compile(ProjectReader.read(json)).warnings())
                .anyMatch(m -> m.text().contains("index() outside any for-each"));
    }

    @Test
    void appendingDuringAWalkDoesNotExtendIt() {
        // The entries are taken before the body runs, so a body that appends to the list it
        // is walking terminates. The alternative is a loop that never ends.
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

    // -----------------------------------------------------------------------------------
    // Naming a variable binds it, absence included
    // -----------------------------------------------------------------------------------

    /**
     * Found by the {@code log_sessions} fixture, not by reading the code: a log line whose
     * tags field was empty was given the previous line's tags. Nothing to split is the empty
     * list, and the name has to say so; leaving it alone leaves the last record's pieces
     * standing where a walk will find them.
     */
    @Test
    void tokenizingAnAbsentValueBindsTheEmptyListNotTheLastRecordsPieces() {
        final String body = """
                {"text": "<r>"},
                {"tokenize": {"select": [{"parts": [{"last": {"of": "field"}}]}],
                  "delimiter": ",", "name": "p"}},
                {"for-each": {"select": "p", "as": "t", "body": [
                   {"text": "<t>"},
                   {"value-of": {"parts": [{"capture": {"var_id": "t", "group": 0}}]}},
                   {"text": "</t>"}]}},
                {"text": "</r>"}""";
        assertThat(run(config("{\"text\": \"\"}", body), "a,b\n\nc\n"))
                .isEqualTo("<r><t>a</t><t>b</t></r><r></r><r><t>c</t></r>");
    }

    /**
     * The same rule for a scalar, in the place design/16 made ordinary. Inside an iteration
     * an entry with no answer that skipped its bind would leave the previous entry's answer
     * to be read.
     */
    @Test
    void anAbsentComputedValueInsideAnIterationBindsAbsence() {
        final String epilogue = """
                {"for-each": {"select": "items", "as": "item", "body": [
                  {"text": "<r>"},
                  {"number": {"select": [{"parts": [{"capture": {"var_id": "item", "group": 0}}]}],
                    "name": "n"}},
                  {"value-of": {"parts": [{"capture": {"var_id": "n", "group": 0}}]}},
                  {"text": "</r>"}]}}
                """;
        assertThat(run(config(epilogue, APPEND_FIELD), "12\nxx\n34\n"))
                .isEqualTo("<r>12</r><r></r><r>34</r>");
    }
}
