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

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What design/17's function library does when the value is not there, or is there and is wrong.
 *
 * <p>This exists because of E28. Every instruction in the library had tests, and all of them fed
 * values that were present and well formed; the nine catalogue cases that prove the same
 * instructions against Saxon feed well-formed XML where every element carries every attribute,
 * so no field is ever absent in them either. The first configuration to meet a missing field —
 * the {@code log_sessions} fixture, on the day it was written — found a stale read. One test
 * sweeping the whole library for the case nothing covered is the answer to that, rather than
 * one more test for the instruction that happened to be caught.
 *
 * <p>Two rules are being pinned, and they are the same rule seen from either side. An
 * instruction with nothing to say <b>writes nothing</b> — "empty is absent", so a missing field
 * leaves no trace in the output rather than an empty element or a zero. And it <b>binds
 * absence</b>: naming a variable binds it, so a name never answers with the last value it
 * happened to hold. The second is the one E28 broke.
 *
 * <p>Malformed is deliberately not an error. A value that is present and is not a number has no
 * numeric reading, and the answer to a question with no answer is nothing — never an exception
 * mid-record (E26), and never a guess. {@code strict_values} is the switch for a configuration
 * that wants to hear about it, and it warns rather than fails.
 */
class AbsentAndMalformedValuesTest {

    /** A reference to the record's one field, at the entry the iteration is on. */
    private static final String FIELD =
            "{\"parts\": [{\"capture\": {\"var_id\": \"field\", \"group\": 0, "
            + "\"match_index\": {\"var_ref\": \"i\"}}}]}";

    /** A literal, for the cases that do not need a field at all. */
    private static String literal(final String text) {
        return "{\"parts\": [{\"text\": \"" + text + "\"}]}";
    }

    /**
     * One instruction, run three ways.
     *
     * @param instr JSON with a {@code %s} where the {@code name} clause goes, so the same
     *              instruction can be run for its writing and for its binding
     */
    private record Probe(String label, String instr, String present,
                         String onPresent, String onMalformed) {
    }

    // -----------------------------------------------------------------------------------
    // The sweep
    // -----------------------------------------------------------------------------------

    private static final String DATE = "2026-01-02T03:04:05Z";

    private static Probe of(final String label, final String instr,
                            final String onPresent, final String onMalformed) {
        return new Probe(label, instr, "42", onPresent, onMalformed);
    }

    private static Stream<Probe> library() {
        return Stream.of(
                of("string-join", "{\"string-join\": {\"select\": [" + FIELD
                                  + "], \"separator\": \"-\"%s}}", "42", "xx"),
                of("replace", "{\"replace\": {\"select\": [" + FIELD
                              + "], \"pattern\": \"x\", \"replacement\": \"Z\"%s}}", "42", "ZZ"),
                of("lower-case", "{\"lower-case\": {\"select\": [" + FIELD + "]%s}}", "42", "xx"),
                of("upper-case", "{\"upper-case\": {\"select\": [" + FIELD + "]%s}}", "42", "XX"),
                of("normalize-space", "{\"normalize-space\": {\"select\": [" + FIELD + "]%s}}",
                        "42", "xx"),
                of("trim", "{\"trim\": {\"select\": [" + FIELD + "]%s}}", "42", "xx"),
                of("translate", "{\"translate\": {\"select\": [" + FIELD
                                + "], \"from\": [\"x\"], \"to\": [\"Z\"]%s}}", "42", "ZZ"),
                of("substring", "{\"substring\": {\"select\": [" + FIELD
                                + "], \"start\": 1, \"length\": 1%s}}", "4", "x"),
                of("substring-before", "{\"substring-before\": {\"select\": [" + FIELD
                                       + "], \"marker\": \"x\"%s}}", "", ""),
                of("substring-after", "{\"substring-after\": {\"select\": [" + FIELD
                                      + "], \"marker\": \"x\"%s}}", "", "x"),
                of("starts-with", "{\"starts-with\": {\"select\": [" + FIELD
                                  + "], \"prefix\": \"x\"%s}}", "false", "true"),
                of("ends-with", "{\"ends-with\": {\"select\": [" + FIELD
                                + "], \"suffix\": \"x\"%s}}", "false", "true"),
                of("contains", "{\"contains\": {\"select\": [" + FIELD
                               + "], \"substring\": \"x\"%s}}", "false", "true"),
                of("string-length", "{\"string-length\": {\"select\": [" + FIELD + "]%s}}",
                        "2", "2"),
                of("tokenize", "{\"tokenize\": {\"select\": [" + FIELD
                               + "], \"delimiter\": \",\"%s}}", "42", "xx"),
                of("number", "{\"number\": {\"select\": [" + FIELD + "]%s}}", "42", ""),
                of("add", "{\"add\": {\"select\": [" + FIELD + ", " + literal("2") + "]%s}}",
                        "44", ""),
                of("subtract", "{\"subtract\": {\"select\": [" + FIELD + ", " + literal("2")
                               + "]%s}}", "40", ""),
                of("multiply", "{\"multiply\": {\"select\": [" + FIELD + ", " + literal("2")
                               + "]%s}}", "84", ""),
                of("divide", "{\"divide\": {\"select\": [" + FIELD + ", " + literal("2")
                             + "]%s}}", "21", ""),
                of("mod", "{\"mod\": {\"select\": [" + FIELD + ", " + literal("2") + "]%s}}",
                        "0", ""),
                of("round", "{\"round\": {\"select\": [" + FIELD + "]%s}}", "42", ""),
                of("floor", "{\"floor\": {\"select\": [" + FIELD + "]%s}}", "42", ""),
                of("ceiling", "{\"ceiling\": {\"select\": [" + FIELD + "]%s}}", "42", ""),
                of("abs", "{\"abs\": {\"select\": [" + FIELD + "]%s}}", "42", ""),
                of("format-number", "{\"format-number\": {\"select\": [" + FIELD
                                    + "], \"picture\": \"0.00\"%s}}", "42.00", ""),
                new Probe("parse-date", "{\"parse-date\": {\"select\": [" + FIELD
                                        + "], \"pattern\": \"iso\"%s}}", DATE, DATE, ""),
                new Probe("format-date", "{\"format-date\": {\"select\": [" + FIELD
                                         + "], \"pattern\": \"uuuu\"%s}}", DATE, "2026", ""));
    }

    /**
     * Every value-producing instruction, over a present value, an absent one and a malformed
     * one, written and bound.
     *
     * <p>The middle record is the point. Its field is an empty capture, which is absent, and
     * it sits <i>between</i> two records that do have values so that a stale read has something
     * to be stale with — the shape E28 was.
     */
    @TestFactory
    Stream<DynamicTest> theLibraryOverPresentAbsentAndMalformedValues() {
        return library().map(probe -> DynamicTest.dynamicTest(probe.label(), () -> {
            final String expected = "w[" + probe.onPresent() + "] b[" + probe.onPresent() + "] "
                                    + "w[] b[] "
                                    + "w[" + probe.onMalformed() + "] b[" + probe.onMalformed()
                                    + "] ";
            assertThat(run(probe.instr(), probe.present() + "\n\nxx\n")).isEqualTo(expected);
        }));
    }

    // -----------------------------------------------------------------------------------
    // Present, and at the edges
    // -----------------------------------------------------------------------------------

    private record Edge(String label, String instr, String expected) {
    }

    private static Edge edge(final String label, final String instr, final String expected) {
        return new Edge(label, instr, expected);
    }

    /**
     * The answers at the edges, pinned because every one of them is a decision.
     *
     * <p>Three groups. <b>Arithmetic that cannot be done in a long</b> promotes to a double
     * rather than wrapping, because a wrapped total is a number that looks like an answer; the
     * one silent wrap Java has, {@code MIN_VALUE / -1}, is the reason the guard exists (E26).
     * <b>Division by zero</b> has no answer and produces none. <b>Strings are counted and cut
     * in code points</b>, so a character outside the basic plane is one character and not two,
     * and the bounds follow XPath's rule: positions are 1-based, a start before the string is
     * not an error, and a length past the end is not either.
     */
    @TestFactory
    Stream<DynamicTest> theEdgesOfTheNumericAndStringFunctions() {
        return Stream.of(
                edge("divide by zero has no answer",
                        "{\"divide\": {\"select\": [" + literal("10") + ", " + literal("0")
                        + "]%s}}", ""),
                edge("mod by zero has no answer",
                        "{\"mod\": {\"select\": [" + literal("10") + ", " + literal("0")
                        + "]%s}}", ""),
                edge("MIN_VALUE / -1 promotes rather than wrapping",
                        "{\"divide\": {\"select\": [" + literal("-9223372036854775808") + ", "
                        + literal("-1") + "]%s}}", "9.223372036854776E18"),
                edge("overflow promotes rather than wrapping",
                        "{\"add\": {\"select\": [" + literal("9223372036854775807") + ", "
                        + literal("1") + "]%s}}", "9.223372036854776E18"),
                edge("a start past the end yields nothing",
                        "{\"substring\": {\"select\": [" + literal("abc")
                        + "], \"start\": 10, \"length\": 2%s}}", ""),
                edge("a length past the end is clamped",
                        "{\"substring\": {\"select\": [" + literal("abc")
                        + "], \"start\": 1, \"length\": 100%s}}", "abc"),
                edge("start 0 takes only position 1, as XPath does",
                        "{\"substring\": {\"select\": [" + literal("abc")
                        + "], \"start\": 0, \"length\": 2%s}}", "a"),
                edge("a start before the string is not an error",
                        "{\"substring\": {\"select\": [" + literal("abc")
                        + "], \"start\": -1, \"length\": 2%s}}", ""),
                edge("length counts code points, not UTF-16 units",
                        "{\"string-length\": {\"select\": [" + literal("a👍b")
                        + "]%s}}", "3"),
                edge("substring cuts on code points",
                        "{\"substring\": {\"select\": [" + literal("a👍b")
                        + "], \"start\": 2, \"length\": 1%s}}", "👍"),
                edge("translate drops what has no counterpart",
                        "{\"translate\": {\"select\": [" + literal("abc")
                        + "], \"from\": [\"ab\"], \"to\": [\"x\"]%s}}", "xc"),
                edge("format-number groups",
                        "{\"format-number\": {\"select\": [" + literal("1234567.891")
                        + "], \"picture\": \"###,###.##\"%s}}", "1,234,567.89"),
                edge("round goes half towards positive infinity",
                        "{\"round\": {\"select\": [" + literal("-2.5") + "]%s}}", "-2"),
                edge("round goes half up for positives",
                        "{\"round\": {\"select\": [" + literal("2.5") + "]%s}}", "3"),
                edge("a date that is not a date does not parse",
                        "{\"parse-date\": {\"select\": [" + literal("2026-13-45T99:99:99Z")
                        + "], \"pattern\": \"iso\"%s}}", ""),
                edge("text is not a date to format",
                        "{\"format-date\": {\"select\": [" + literal("hello")
                        + "], \"pattern\": \"uuuu\"%s}}", ""),
                edge("a number too large to be one is not one",
                        "{\"number\": {\"select\": [" + literal("1e309") + "]%s}}", ""),
                edge("hex is not a number",
                        "{\"number\": {\"select\": [" + literal("0x10") + "]%s}}", ""),
                edge("surrounding space does not stop it being a number",
                        "{\"number\": {\"select\": [" + literal(" 42 ") + "]%s}}", "42"))
                .map(e -> DynamicTest.dynamicTest(e.label(), () ->
                        assertThat(run(e.instr(), "one\n"))
                                .isEqualTo("w[" + e.expected() + "] b[" + e.expected() + "] ")));
    }

    // -----------------------------------------------------------------------------------
    // Running one
    // -----------------------------------------------------------------------------------

    /**
     * Run an instruction inside an iteration, once written and once bound and read back.
     *
     * <p>Inside a walk the enclosing match index does not move, so every entry binds the same
     * cell — which is exactly why a skipped bind is readable as the previous entry's answer,
     * and why this is the shape worth sweeping.
     */
    private static String run(final String instruction, final String input) {
        final String json = """
                {"name": "t", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source",
                   "body": [
                     {"sequence": {"name": "items"}},
                     {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                       "mode": "doc"}},
                     {"for-each": {"select": "items", "as": "i", "body": [
                        {"text": "w["},
                        %1$s,
                        {"text": "] b["},
                        %2$s,
                        {"value-of": {"parts": [{"capture": {"var_id": "out", "group": 0}}]}},
                        {"text": "] "}]}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "match": {"regex": {"pattern": "([^\\\\n]*)\\\\n"}},
                   "captures": [{"name": "field", "select": {"group": 1}}],
                   "body": [{"append": {"name": "items", "select": {"parts": [
                     {"capture": {"var_id": "__match_count", "group": 0}}]}}}]}]}
                """.formatted(instruction.formatted(""),
                instruction.formatted(", \"name\": \"out\""));

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(out));
        // Nothing here is an error. A missing field is normal and a malformed one is answered,
        // not raised; anything louder than silence is the finding (E26).
        assertThat(messages)
                .as("no instruction may raise on an absent or malformed value")
                .noneMatch(m -> m.severity() == Severity.ERROR || m.severity() == Severity.FATAL);
        return out.toString(StandardCharsets.UTF_8);
    }
}
