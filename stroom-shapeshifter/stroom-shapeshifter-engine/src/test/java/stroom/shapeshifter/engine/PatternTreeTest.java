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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pattern tree and the match sequence, pinned (design 38, D52): what the step interpreter's
 * two test files pinned, re-expressed on the composition that replaced it. Every outcome below
 * is the one the interpreter gave, except where D52 names a widening — a repeat that gives
 * back — and that case is pinned to its new outcome.
 */
class PatternTreeTest {

    private static final HexFormat HEX = HexFormat.of();

    /** One template over the whole input, in the encoding given, its body writing what the parts say. */
    private static String run(final String match, final String encoding, final byte[] input, final String... parts) {
        final StringBuilder body = new StringBuilder("[{\"text\": \"[\"}");
        for (final String part : parts) {
            body.append(", ").append(part);
        }
        body.append(", {\"text\": \"]\"}]");
        final String json = """
                {
                  "name": "tree", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "ENC"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                     "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "rec", "dispatch": "strict"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "rec", "mode": "rec",
                     "encoding": "ENC",
                     "match": MATCH,
                     "body": [{"value-of": {"parts": PARTS}}]}
                  ]
                }
                """.replace("ENC", encoding).replace("MATCH", match).replace("PARTS", body);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shapeshifter.runWhole(Shapeshifter.compile(ProjectReader.read(json)), input, new XmlByteSink(out));
        return out.toString(StandardCharsets.UTF_8).replace("\n", "");
    }

    private static String run(final String match, final String input, final String... parts) {
        return run(match, "utf-8", input.getBytes(StandardCharsets.UTF_8), parts);
    }

    private static String raw(final String match, final String hex, final String... parts) {
        return run(match, "raw", HEX.parseHex(hex), parts);
    }

    private static String whole() {
        return "{\"capture\": {\"group\": 0}}";
    }

    private static String label(final String name) {
        return "{\"capture\": {\"label\": \"" + name + "\"}}";
    }

    // -----------------------------------------------------------------------------------
    // Atoms
    // -----------------------------------------------------------------------------------

    @Test
    void textAtomsConsumeWhatTheyMatch() {
        final String match = """
                {"pattern": {"sequence": [{"tag": "id="}, {"take_while": "[0-9]", "label": "n"},
                                          {"take_until": ";", "label": "rest"}, {"take_through": ";"}]}}""";
        assertThat(run(match, "id=42 more;tail", label("n"), "{\"text\": \"|\"}", label("rest")))
                .isEqualTo("[42| more]");
    }

    @Test
    void takeUntilWithAMultiByteTerminatorIsALazyRunToALookahead() {
        final String match = """
                {"pattern": {"sequence": [{"take_until": " pid=", "label": "msg"}, {"tag": " pid="},
                                          {"take_while": "[0-9]", "label": "pid"}]}}""";
        assertThat(run(match, "hello world pid=7", label("msg"), "{\"text\": \"/\"}", label("pid")))
                .isEqualTo("[hello world/7]");
    }

    @Test
    void classesClassifyUnderTheEffectiveEncoding() {
        final String match = """
                {"pattern": {"sequence": [{"take_while": "\\\\p{L}", "label": "word"}]}}""";
        assertThat(run(match, "café!", label("word"))).isEqualTo("[café]");
    }

    @Test
    void anyTakesAWholeCharacterInUtf8AndOneByteInRaw() {
        final String match = """
                {"pattern": {"any": true, "label": "c"}}""";
        assertThat(run(match, "é", label("c"))).isEqualTo("[é]");
        assertThat(raw(match, "4142", label("c"))).as("one match per byte").isEqualTo("[A][B]");
    }

    // -----------------------------------------------------------------------------------
    // Binary casts
    // -----------------------------------------------------------------------------------

    @Test
    void numbersReadInBothByteOrdersAndWidths() {
        final String match = """
                {"pattern": {"sequence": [{"take": 2, "label": "le", "as": "uint16le"},
                                          {"take": 2, "label": "be", "as": "uint16be"},
                                          {"take": 4, "label": "s", "as": "int32le"},
                                          {"take": 8, "label": "d", "as": "float64be"}]}}""";
        assertThat(raw(match, "0201" + "0102" + "ffffffff" + "4022000000000000",
                label("le"), "{\"text\": \",\"}", label("be"), "{\"text\": \",\"}", label("s"),
                "{\"text\": \",\"}", label("d")))
                .isEqualTo("[258,258,-1,9]");
    }

    @Test
    void varintsReadSevenBitsAtATimeAndZigzagUndoesTheSign() {
        final String varint = """
                {"sequence": [{"take_while": "[\\\\x80-\\\\xff]", "min": 0},
                              {"take_while": "[\\\\x00-\\\\x7f]", "max": 1}], "label": "v", "as": "CAST"}""";
        assertThat(raw("{\"pattern\": " + varint.replace("CAST", "varint") + "}", "ac02", label("v")))
                .isEqualTo("[300]");
        assertThat(raw("{\"pattern\": " + varint.replace("CAST", "zigzag") + "}", "c701", label("v")))
                .isEqualTo("[-100]");
    }

    @Test
    void wrongWidthIsAbsentNotAWrongNumber() {
        final String match = """
                {"pattern": {"sequence": [{"take_while": "[\\\\x00-\\\\xff]", "label": "n", "as": "uint32le"}]}}""";
        assertThat(raw(match, "010203", label("n"))).isEqualTo("[]");
    }

    @Test
    void positionIsTheOffsetOfALabelledEmptyNode() {
        final String match = """
                {"pattern": {"sequence": [{"take": 3}, {"sequence": [], "label": "here", "as": "position"},
                                          {"take": 2}]}}""";
        assertThat(raw(match, "0102030405", label("here"))).isEqualTo("[3]");
    }

    // -----------------------------------------------------------------------------------
    // The match sequence
    // -----------------------------------------------------------------------------------

    /**
     * A read binds what the cast would have bound from a pattern's labelled take: the same
     * table, the same value, with no pattern run (design 39, D56). Varints in both signs, a
     * fixed width, a flag, a position; an unlabelled read consumes and binds nothing.
     */
    @Test
    void readBindsTheCastsValueWithoutAPattern() {
        final String reads = """
                {"parts": [{"read": {"as": "varint", "label": "u"}}, {"read": {"as": "zigzag", "label": "z"}},
                           {"read": {"as": "uint16be", "label": "w"}}, {"read": "uint8"},
                           {"read": {"as": "bool8", "label": "b"}}, {"read": {"as": "position", "label": "p"}},
                           {"read": {"as": "float64le", "label": "d"}}]}""";
        final String patterns = """
                {"parts": [{"pattern": {"sequence": [{"take_while": "[\\\\x80-\\\\xff]", "min": 0},
                                                     {"take_while": "[\\\\x00-\\\\x7f]", "max": 1}],
                                        "label": "u", "as": "varint"}},
                           {"pattern": {"sequence": [{"take_while": "[\\\\x80-\\\\xff]", "min": 0},
                                                     {"take_while": "[\\\\x00-\\\\x7f]", "max": 1}],
                                        "label": "z", "as": "zigzag"}},
                           {"pattern": {"sequence": [{"take": 2, "label": "w", "as": "uint16be"}, {"take": 1},
                                                     {"take": 1, "label": "b", "as": "bool8"},
                                                     {"sequence": [], "label": "p", "as": "position"},
                                                     {"take": 8, "label": "d", "as": "float64le"}]}}]}""";
        final String hex = "ac02" + "c701" + "0102" + "ff" + "01" + "0000000000002240";
        final String[] parts = {label("u"), text(","), label("z"), text(","), label("w"), text(","), label("b"),
                text(","), label("p"), text(","), label("d")};
        assertThat(raw(reads, hex, parts)).isEqualTo("[300,-100,258,true,8,9]");
        assertThat(raw(patterns, hex, parts)).as("the same values from the pattern's casts")
                .isEqualTo(raw(reads, hex, parts));
    }

    @Test
    void truncatedReadFailsTheMatch() {
        assertThat(raw("""
                {"parts": [{"read": {"as": "uint32le", "label": "n"}}]}""", "010203", label("n")))
                .as("three bytes for a four-byte read").isEqualTo("");
        assertThat(raw("""
                {"parts": [{"read": {"as": "varint", "label": "n"}}]}""", "8080", label("n")))
                .as("a varint that never ends").isEqualTo("");
    }

    private static String text(final String value) {
        return "{\"text\": \"" + value + "\"}";
    }

    @Test
    void takeUsesAnEarlierLabelsValue() {
        final String match = """
                {"parts": [{"pattern": {"take": 1, "label": "len", "as": "uint8"}},
                           {"take": {"length": {"label": "len"}, "label": "body"}},
                           {"pattern": {"tag": "!"}}]}""";
        assertThat(raw(match, "03" + "616263" + "21", label("body"))).isEqualTo("[abc]");
    }

    @Test
    void takeByLiteralAndSeekForwardAndAbsolute() {
        final String match = """
                {"parts": [{"take": 2}, {"seek": 1}, {"take": {"length": 1, "label": "a"}},
                           {"seek": {"length": 6, "absolute": true}}, {"take": {"length": 1, "label": "b"}}]}""";
        assertThat(raw(match, "00000041000042", label("a"), label("b"))).isEqualTo("[AB]");
    }

    @Test
    void fewerBytesThanATakeAsksForFailsTheMatch() {
        final String match = """
                {"parts": [{"pattern": {"take": 1, "label": "len", "as": "uint8"}},
                           {"take": {"length": {"label": "len"}, "label": "body"}}]}""";
        assertThat(raw(match, "0561", label("body"))).as("nothing matched, nothing written").isEqualTo("");
    }

    @Test
    void failedPartFailsTheWholeMatch() {
        final String match = """
                {"parts": [{"pattern": {"tag": "a"}}, {"pattern": {"tag": "z"}}]}""";
        assertThat(run(match, "ab", whole())).isEqualTo("");
    }

    @Test
    void lengthFromALabelNoEarlierPartBindsIsRefused() {
        assertThatThrownBy(() -> run("""
                {"parts": [{"take": {"label": "len"}}]}""", "abc", whole()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("len");
    }

    // -----------------------------------------------------------------------------------
    // Combinators, under D52's semantics
    // -----------------------------------------------------------------------------------

    @Test
    void choiceTakesTheFirstAlternativeThatMatches() {
        final String match = """
                {"pattern": {"choice": [{"sequence": [{"tag": "ab"}, {"tag": "cd"}]},
                                        {"sequence": [{"tag": "ab"}, {"tag": "xy"}]}]}}""";
        assertThat(run(match, "abcd", whole())).isEqualTo("[abcd]");
        assertThat(run(match, "abxy", whole())).isEqualTo("[abxy]");
        assertThat(run(match, "abzz", whole())).isEqualTo("");
    }

    @Test
    void optionalAndRepeatAreGreedy() {
        final String match = """
                {"pattern": {"sequence": [{"optional": {"tag": "<<"}}, {"repeat": {"tag": "ab"}, "min": 1, "max": 2},
                                          {"tag": "!"}]}}""";
        assertThat(run(match, "<<abab!", whole())).isEqualTo("[<<abab!]");
        assertThat(run(match, "ab!", whole())).isEqualTo("[ab!]");
        assertThat(run(match, "ababab!", whole())).as("stops at its maximum").isEqualTo("");
        assertThat(run(match, "!", whole())).as("below its minimum").isEqualTo("");
    }

    /** D52's one widening: under PEG the repeat kept everything and the sequence failed; now it gives back. */
    @Test
    void repeatGivesBackWhatTheRestOfTheSequenceNeeds() {
        final String match = """
                {"pattern": {"sequence": [{"repeat": {"tag": "a"}, "min": 1}, {"tag": "a"}]}}""";
        assertThat(run(match, "aaa", whole())).isEqualTo("[aaa]");
    }

    @Test
    void peekAndNotMatchWithoutConsuming() {
        assertThat(run("""
                {"pattern": {"sequence": [{"peek": {"tag": "ab"}}, {"tag": "abc"}]}}""", "abc", whole()))
                .isEqualTo("[abc]");
        assertThat(run("""
                {"pattern": {"sequence": [{"peek": {"tag": "zz"}}, {"tag": "abc"}]}}""", "abc", whole()))
                .isEqualTo("");
        assertThat(run("""
                {"pattern": {"sequence": [{"not": {"tag": "zz"}}, {"tag": "abc"}]}}""", "abc", whole()))
                .isEqualTo("[abc]");
        assertThat(run("""
                {"pattern": {"sequence": [{"not": {"tag": "ab"}}, {"tag": "abc"}]}}""", "abc", whole()))
                .isEqualTo("");
    }

    @Test
    void labelInsideAFailedAlternativeDoesNotParticipate() {
        final String match = """
                {"pattern": {"choice": [{"sequence": [{"tag": "3a", "label": "first"}, {"tag": "Q"}]},
                                        {"take_while": "[0-9]", "label": "second"}]}}""";
        assertThat(run(match, "3abc", label("first"), "{\"text\": \"|\"}", label("second")))
                .isEqualTo("[|3]");
    }

    @Test
    void labelsAreReachableFromAnyDepthAndNumberedAsParentheses() {
        final String match = """
                {"pattern": {"sequence": [{"sequence": [{"tag": "x"}, {"tag": "y", "label": "inner"}],
                                           "label": "outer"},
                                          {"regex": "([0-9]+)"}, {"take_while": "[a-z]", "label": "tail"}]}}""";
        assertThat(run(match, "xy3abc", label("outer"), label("inner"), "{\"capture\": {\"group\": 3}}", label("tail")))
                .as("outer is 1, inner 2, the regex's own group 3, tail 4").isEqualTo("[xyy3abc]");
    }

    @Test
    void labelUsedTwiceIsRefused() {
        assertThatThrownBy(() -> run("""
                {"pattern": {"sequence": [{"tag": "a", "label": "x"}, {"tag": "b", "label": "x"}]}}""", "ab", whole()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void unknownLabelInTheBodyIsRefused() {
        assertThatThrownBy(() -> run("""
                {"pattern": {"tag": "a", "label": "x"}}""", "a", label("y")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("'y'");
    }
}
