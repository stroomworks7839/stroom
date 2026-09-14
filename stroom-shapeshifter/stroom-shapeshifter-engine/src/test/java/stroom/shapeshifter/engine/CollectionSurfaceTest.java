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
 * Design 35 phase 4's operation surface, at configuration level: the mutations as statements,
 * the accessors as functions, 1-based positions, a map walk binding two names, copy on store,
 * and nesting one level at a time.
 */
class CollectionSurfaceTest {

    /** One source template running once over the input, with the declarations and body given. */
    private static String config(final String declarations, final String body) {
        return """
                {"name": "t", "version": 5,
                 "source": {"buffer_size": 2000, "ignore_errors": true},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "declarations": [%s],
                   "match": "source",
                   "body": [%s]}]}
                """.formatted(declarations, body);
    }

    private static String run(final String json) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(out));
        assertThat(messages).noneMatch(m -> m.severity() == Severity.ERROR || m.severity() == Severity.FATAL);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static List<Message> messagesFrom(final String json) {
        return Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(new ByteArrayOutputStream()));
    }

    private static final String L = "{\"name\": \"l\", \"type\": \"list\"}";
    private static final String M = "{\"name\": \"m\", \"type\": \"map\"}";
    private static final String S = "{\"name\": \"s\", \"type\": \"set\"}";
    private static final String X = "{\"name\": \"x\", \"type\": \"scalar\"}";

    private static String append(final String list, final String text) {
        return "{\"append\": {\"name\": \"" + list + "\", \"select\": {\"parts\": [{\"text\": \"" + text + "\"}]}}}";
    }

    private static String walk(final String of) {
        return "{\"for-each\": {\"select\": " + of + ", \"as\": \"v\", \"body\": ["
               + "{\"value-of\": {\"parts\": [{\"capture\": {\"var_id\": \"v\", \"group\": 0}}]}}, "
                       + "{\"text\": \",\"}]}}";
    }

    private static String write(final String part) {
        return "{\"value-of\": {\"parts\": [" + part + "]}}";
    }

    // ---- lists ----

    @Test
    void positionsAreOneBasedAsXpathsAre() {
        final String body = append("l", "a") + "," + append("l", "b") + "," + append("l", "c") + ","
                + write("{\"get\": {\"of\": \"l\", \"key\": 1}}") + ",{\"text\": \"|\"},"
                        + write("{\"get\": {\"of\": \"l\", \"key\": \"3\"}}") + ",{\"text\": \"|\"},"
                                + write("{\"head\": {\"of\": \"l\"}}") + "," + write("{\"last\": {\"of\": \"l\"}}")
                                        + "," + write("{\"size\": {\"of\": \"l\"}}");
        assertThat(run(config(L, body))).isEqualTo("a|c|ac3");
    }

    @Test
    void insertPutAndRemoveOnAList() {
        final String body = append("l", "a") + "," + append("l", "c") + ","
                + "{\"insert\": {\"name\": \"l\", \"position\": 2, \"select\": {\"parts\": [{\"text\": \"b\"}]}}},"
                + "{\"put\": {\"name\": \"l\", \"key\": 3, \"select\": {\"parts\": [{\"text\": \"C\"}]}}},"
                + walk("\"l\"") + ",{\"text\": \"|\"},"
                + "{\"remove\": {\"name\": \"l\", \"key\": 1}}," + walk("\"l\"") + ",{\"text\": \"|\"},"
                + "{\"clear\": {\"name\": \"l\"}}," + write("{\"size\": {\"of\": \"l\"}}");
        assertThat(run(config(L, body))).isEqualTo("a,b,C,|b,C,|0");
    }

    @Test
    void containsOnAListIsByCanonicalEquality() {
        final String body = append("l", "1") + "," + write("{\"contains\": {\"of\": \"l\", \"value\": \"1\"}}")
                + "," + write("{\"contains\": {\"of\": \"l\", \"value\": \"2\"}}");
        assertThat(run(config(L, body))).isEqualTo("truefalse");
    }

    @Test
    void putPastTheEndIsFatalAndAnInsertAtSizePlusOneAppends() {
        final String past = append("l", "a")
                + ",{\"put\": {\"name\": \"l\", \"key\": 2, \"select\": {\"parts\": [{\"text\": \"b\"}]}}}";
        assertThat(messagesFrom(config(L, past)))
                .anyMatch(m -> m.severity() == Severity.FATAL && m.text().contains("put at position 2 of a list of 1"));
        final String atEnd = append("l", "a")
                + ",{\"insert\": {\"name\": \"l\", \"position\": 2, \"select\": {\"parts\": [{\"text\": \"b\"}]}}},"
                        + walk("\"l\"");
        assertThat(run(config(L, atEnd))).isEqualTo("a,b,");
    }

    // ---- maps ----

    @Test
    void mapIsPutIntoReadByKeyAndWalkedWithBothNamesBound() {
        final String body = "{\"put\": {\"name\": \"m\", \"key\": \"a\", "
                + "\"select\": {\"parts\": [{\"text\": \"1\"}]}}},"
                + "{\"put\": {\"name\": \"m\", \"key\": \"b\", \"select\": {\"parts\": [{\"text\": \"2\"}]}}},"
                + "{\"put\": {\"name\": \"m\", \"key\": \"a\", \"select\": {\"parts\": [{\"text\": \"3\"}]}}},"
                        + write("{\"get\": {\"of\": \"m\", \"key\": \"a\"}}") + ",{\"text\": \"|\"},"
                                + write("{\"get\": {\"of\": \"m\", \"key\": \"z\", \"default\": \"none\"}}")
                                        + ",{\"text\": \"|\"}," + write("{\"size\": {\"of\": \"m\"}}") + ","
                                                + write("{\"contains\": {\"of\": \"m\", \"value\": \"b\"}}")
                                                        + ",{\"text\": \"|\"},"
                + "{\"for-each\": {\"select\": \"m\", \"as\": \"v\", \"as_key\": \"k\", \"body\": ["
                + "{\"value-of\": {\"parts\": [{\"capture\": {\"var_id\": \"k\", \"group\": 0}}, {\"text\": \"=\"},"
                + "{\"capture\": {\"var_id\": \"v\", \"group\": 0}}, {\"text\": \" \"}]}}]}},"
                + "{\"remove\": {\"name\": \"m\", \"key\": \"a\"}}," + write("{\"size\": {\"of\": \"m\"}}");
        assertThat(run(config(M, body))).isEqualTo("3|none|2true|a=3 b=2 1");
    }

    @Test
    void keysAndValuesAreListsInInsertionOrder() {
        final String body = "{\"put\": {\"name\": \"m\", \"key\": \"b\", "
                + "\"select\": {\"parts\": [{\"text\": \"2\"}]}}},"
                + "{\"put\": {\"name\": \"m\", \"key\": \"a\", \"select\": {\"parts\": [{\"text\": \"1\"}]}}},"
                + walk("{\"parts\": [{\"keys\": {\"of\": \"m\"}}]}") + ",{\"text\": \"|\"},"
                + walk("{\"parts\": [{\"values\": {\"of\": \"m\"}}]}");
        assertThat(run(config(M, body))).isEqualTo("b,a,|2,1,");
    }

    @Test
    void mapDeclaredWithEntriesStartsAsThatTable() {
        final String months = "{\"name\": \"months\", \"type\": \"map\", \"entries\": [{\"from\": \"Jan\", "
                + "\"to\": \"01\"}]}";
        final String body = write("{\"get\": {\"of\": \"months\", \"key\": \"Jan\"}}") + ","
                + write("{\"size\": {\"of\": \"months\"}}");
        assertThat(run(config(months, body))).isEqualTo("011");
    }

    // ---- sets ----

    @Test
    void setDropsRepeatsAndKeepsFirstAppearanceOrder() {
        final String put = "{\"put\": {\"name\": \"s\", \"select\": {\"parts\": [{\"text\": \"%s\"}]}}}";
        final String body = put.formatted("b") + "," + put.formatted("a") + "," + put.formatted("b") + ","
                + walk("\"s\"") + "," + write("{\"size\": {\"of\": \"s\"}}") + ","
                        + write("{\"contains\": {\"of\": \"s\", \"value\": \"a\"}}")
                + ",{\"remove\": {\"name\": \"s\", \"key\": \"a\"}},"
                        + walk("{\"parts\": [{\"values\": {\"of\": \"s\"}}]}");
        assertThat(run(config(S, body))).isEqualTo("b,a,2trueb,");
    }

    // ---- scalars ----

    @Test
    void putWithNoKeyAssignsAScalar() {
        final String body = "{\"put\": {\"name\": \"x\", \"select\": {\"parts\": [{\"text\": \"7\"}]}}},"
                + write("{\"capture\": {\"var_id\": \"x\", \"group\": 0}}");
        assertThat(run(config(X, body))).isEqualTo("7");
    }

    // ---- nesting, copies, ownership ----

    @Test
    void mapOfListsIsBuiltExplicitlyAndMutatedInPlaceThroughGet() {
        // The former key: a list per key, filled through the accessor. The empty list put under
        // a new key is a copy of a declared one, so the template stays empty.
        final String empty = "{\"name\": \"empty\", \"type\": \"list\"}";
        final String putEmpty = "{\"put\": {\"name\": \"m\", \"key\": \"k\", "
                + "\"select\": {\"parts\": [{\"capture\": {\"var_id\": \"empty\", \"group\": 0}}]}}}";
        final String appendNested = "{\"append\": {\"target\": {\"parts\": [{\"get\": {\"of\": \"m\", "
                + "\"key\": \"k\"}}]}, \"select\": {\"parts\": [{\"text\": \"%s\"}]}}}";
        final String body = putEmpty + "," + appendNested.formatted("1") + "," + appendNested.formatted("2") + ","
                + walk("{\"parts\": [{\"get\": {\"of\": \"m\", \"key\": \"k\"}}]}") + ","
                        + write("{\"size\": {\"of\": {\"parts\": [{\"get\": {\"of\": \"m\", \"key\": \"k\"}}]}}}")
                                + "," + write("{\"size\": {\"of\": \"empty\"}}");
        assertThat(run(config(M + "," + empty, body))).isEqualTo("1,2,20");
    }

    @Test
    void storedCollectionIsACopy() {
        // Appending to l after it was put into m leaves m's copy as it was (design 35 §11).
        final String body = append("l", "a") + ","
                + "{\"put\": {\"name\": \"m\", \"key\": \"k\", "
                        + "\"select\": {\"parts\": [{\"capture\": {\"var_id\": \"l\", \"group\": 0}}]}}},"
                + append("l", "b") + ","
                        + write("{\"size\": {\"of\": {\"parts\": [{\"get\": {\"of\": \"m\", \"key\": \"k\"}}]}}}")
                                + "," + write("{\"size\": {\"of\": \"l\"}}");
        assertThat(run(config(L + "," + M, body))).isEqualTo("12");
    }

    @Test
    void theLiveElementCounterCountsNestedCollections() {
        final String body = append("l", "a") + "," + append("l", "b") + ","
                + "{\"put\": {\"name\": \"m\", \"key\": \"k\", "
                        + "\"select\": {\"parts\": [{\"capture\": {\"var_id\": \"l\", \"group\": 0}}]}}},"
                + "{\"append\": {\"target\": {\"parts\": [{\"get\": {\"of\": \"m\", \"key\": \"k\"}}]}, "
                        + "\"select\": {\"parts\": [{\"text\": \"c\"}]}}}";
        // Two in l, the key and its copy's two, and one appended into the copy: six.
        final String limited = config(L + ","
                + M, body).replace("\"ignore_errors\": true}", "\"ignore_errors\": true, \"max_sequence_entries\": 6}");
        assertThat(messagesFrom(limited)).noneMatch(m -> m.severity() == Severity.FATAL);
        final String tighter = limited.replace("\"max_sequence_entries\": 6", "\"max_sequence_entries\": 5");
        assertThat(messagesFrom(tighter)).anyMatch(m -> m.severity() == Severity.FATAL &&
                m.text().contains("max_sequence_entries (5)"));
    }

    // ---- the checks ----

    @Test
    void anOperationDisagreeingWithTheDeclaredTypeIsRefused() {
        for (final String[] c : List.of(
                new String[]{"{\"append\": {\"name\": \"m\", \"select\": {\"parts\": [{\"text\": \"a\"}]}}}",
                        "appends to 'm'"},
                new String[]{"{\"put\": {\"name\": \"l\", \"select\": {\"parts\": [{\"text\": \"a\"}]}}}",
                        "puts into 'l'"},
                new String[]{"{\"put\": {\"name\": \"x\", \"key\": \"k\", "
                        + "\"select\": {\"parts\": [{\"text\": \"a\"}]}}}", "puts at a key of 'x'"},
                new String[]{"{\"clear\": {\"name\": \"x\"}}", "clears 'x'"},
                new String[]{"{\"for-each\": {\"select\": \"x\", \"body\": []}}", "walks 'x'"},
                new String[]{write("{\"keys\": {\"of\": \"l\"}}"), "keys reads 'l'"},
                new String[]{write("{\"last\": {\"of\": \"m\"}}"), "last reads 'm'"})) {
            assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(config(L + "," + M + "," + X, c[0]))))
                    .as(c[0])
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining(c[1]);
        }
    }

    @Test
    void countOfNothingIsZeroAndNothingContainsNothing() {
        // A lookup that misses is absent; size and contains still answer, as sum(()) does.
        final String body = write("{\"size\": {\"of\": {\"parts\": [{\"get\": {\"of\": \"m\", \"key\": \"none\"}}]}}}")
                + "," + write("{\"contains\": {\"of\": {\"parts\": [{\"get\": {\"of\": \"m\", "
                        + "\"key\": \"none\"}}]}, \"value\": \"x\"}}")
                + ",{\"text\": \"[\"},"
                        + write("{\"last\": {\"of\": {\"parts\": [{\"get\": {\"of\": \"m\", "
                                + "\"key\": \"none\"}}]}}}") + ",{\"text\": \"]\"}";
        assertThat(run(config(M, body))).isEqualTo("0false[]");
    }

    /** A key or position is a scalar: a collection there is refused where it is written. */
    @Test
    void collectionAsAKeyIsRefused() {
        final String body = "{\"put\": {\"name\": \"m\","
                + " \"key\": {\"parts\": [{\"capture\": {\"var_id\": \"l\", \"group\": 0}}]},"
                + " \"select\": {\"parts\": [{\"text\": \"a\"}]}}}";
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(config(L + "," + M, body))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("reads 'l', which is declared as a list");
    }

    /** A capture assigns a scalar or appends to a list; into a map or a set it would overwrite the collection. */
    @Test
    void captureIntoAMapIsRefused() {
        final String json = """
                {"name": "t", "version": 5,
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                   "declarations": [{"name": "m", "type": "map"}],
                   "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "doc"}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "match": {"regex": {"pattern": "([^\\n]*)\\n"}},
                   "captures": [{"name": "m", "select": {"group": 1}}],
                   "body": []}]}
                """;
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(json)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("captures into 'm'")
                .hasMessageContaining("declared as a map");
    }

    @Test
    void anAccessorOnANestedValueIsTypedAtRunTimeAndAnswersAbsent() {
        // One level deep (design 35 §5): the outer get is checked, the inner is a run-time fact.
        final String body = "{\"put\": {\"name\": \"m\", \"key\": \"k\", "
                + "\"select\": {\"parts\": [{\"text\": \"scalar\"}]}}},"
                + "{\"text\": \"[\"},"
                        + write("{\"last\": {\"of\": {\"parts\": [{\"get\": {\"of\": \"m\", \"key\": \"k\"}}]}}}")
                                + ",{\"text\": \"]\"}";
        assertThat(run(config(M, body))).isEqualTo("[]");
    }
}
