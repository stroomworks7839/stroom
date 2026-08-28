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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Input that is not UTF-8, run end to end.
 *
 * <p>{@link stroom.shapeshifter.engine.text.EncodingTest} shows the conversions are right;
 * this shows they are actually reached. Every configuration in the fixture corpus is UTF-8 or
 * {@code auto}, so without these the whole encoding path could be wired up backwards and every
 * fixture would still pass.
 */
class EncodedInputTest {

    /** A configuration that splits on a comma and writes each field out. */
    private static String config(final String encoding) {
        return """
                {
                  "name": "encoded",
                  "version": 3,
                  "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "ENCODING"},
                  "templates": [
                    {
                      "id": "00000000-0000-0000-0000-000000000001",
                      "name": "source",
                      "match": "source",
                      "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                    "mode": "row"}}]
                    },
                    {
                      "id": "00000000-0000-0000-0000-000000000002",
                      "name": "field",
                      "mode": "row",
                      "match": {"delimiter": {"delimiter": ","}},
                      "body": [{"value-of": {"parts": [
                        {"text": "["}, {"capture": {"group": 1}}, {"text": "]"}]}}]
                    }
                  ]
                }
                """.replace("ENCODING", encoding);
    }

    private static String run(final String encoding, final byte[] input) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config(encoding))),
                new ByteArrayInputStream(input),
                OutputSink.of(output));
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void templateEncodingOverridesTheSourcesForItsOwnContent() {
        // E3: one stream, two encodings — a legacy line whose template declares windows-1252
        // beside a UTF-8 line under the source default. 0xE9 is é only where declared.
        final String config = """
                {
                  "name": "override", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "legacy_line", "mode": "row",
                     "encoding": "windows-1252",
                     "match": {"progressive": [
                       {"Tag": "L:"},
                       {"TakeUntil": {"pattern": "\\n", "inclusive": false}},
                       {"Tag": "\\n"}]},
                     "body": [{"value-of": {"parts": [
                       {"text": "["}, {"capture": {"group": 2}}, {"text": "]"}]}}]},
                    {"id": "00000000-0000-0000-0000-000000000003", "name": "utf8_line", "mode": "row",
                     "match": {"regex": {"pattern": "U:([^\\n]*)\\n"}},
                     "body": [{"value-of": {"parts": [
                       {"text": "["}, {"capture": {"group": 1}}, {"text": "]"}]}}]}
                  ]
                }
                """;
        final byte[] input = {'L', ':', (byte) 0xE9, '\n', 'U', ':', (byte) 0xC3, (byte) 0xA9, '\n'};
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream(input),
                OutputSink.of(output));
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[é][é]");
    }

    @Test
    void readsLatin1InputAndWritesUtf8() {
        // café,naïve in Latin-1: one byte per accented character.
        final byte[] input = {'c', 'a', 'f', (byte) 0xE9, ',', 'n', 'a', (byte) 0xEF, 'v', 'e'};
        assertThat(run("iso-8859-1", input)).isEqualTo("[café][naïve]");
    }

    @Test
    void theSameBytesReadAsUtf8AreNotTheSameText() {
        // The point of declaring an encoding: these bytes are not valid UTF-8, and reading them
        // as if they were produces replacement characters rather than an error.
        final byte[] input = {'c', 'a', 'f', (byte) 0xE9};
        assertThat(run("utf-8", input)).isEqualTo("[caf\uFFFD]"); // U+FFFD REPLACEMENT CHARACTER
    }

    @Test
    void readsWindows1252WhereItDiffersFromLatin1() {
        // 0x93 and 0x94 are curly quotes in Windows-1252 and control characters in Latin-1, so
        // this is a case where naming the wrong one of the two produces silent nonsense.
        final byte[] input = {(byte) 0x93, 'h', 'i', (byte) 0x94};
        assertThat(run("windows-1252", input)).isEqualTo("[“hi”]");
        assertThat(run("iso-8859-1", input)).isEqualTo("[\u0093hi\u0094]");
    }

    @Test
    void byteOrderMarkOverridesWhatTheConfigurationSaid() {
        // The input is better evidence than the declaration: a UTF-8 mark means UTF-8 whatever
        // the configuration was expecting.
        final byte[] utf8 = "café".getBytes(StandardCharsets.UTF_8);
        final byte[] input = new byte[utf8.length + 3];
        input[0] = (byte) 0xEF;
        input[1] = (byte) 0xBB;
        input[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, input, 3, utf8.length);

        assertThat(run("iso-8859-1", input)).isEqualTo("[café]");
    }

    @Test
    void delimitersAreEncodedTheSameWayTheInputIs() {
        // The separator has to be looked for as bytes, so it is encoded the way the input is —
        // otherwise a non-ASCII delimiter would never be found.
        final String withPilcrow = config("iso-8859-1").replace("\"delimiter\": \",\"", "\"delimiter\": \"\\u00b6\"");
        final byte[] input = {'a', (byte) 0xB6, 'b'};

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(withPilcrow)),
                new ByteArrayInputStream(input),
                OutputSink.of(output));
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[a][b]");
    }

    @Test
    void refusesAnEncodingItDoesNotKnow() {
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(config("klingon"))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Unknown encoding: klingon");
    }

    // ------------------------------------------------------------------------------------
    // E29 stopgap (design 19 phase 0): regex compiles for UTF-8 only, so a template whose
    // effective encoding is anything else is refused by name rather than silently searched
    // for UTF-8 byte sequences it can never contain. The census note that belongs with
    // these: the E3 override fixture above originally matched with a regex, and passed only
    // because its pure-ASCII pattern landed in the byte-permissive plan tier — the exact
    // licensed deviation of D38. It now says the same thing in steps, the vocabulary that
    // honours the declaration.
    // ------------------------------------------------------------------------------------

    /**
     * Phase 0 refused this exact configuration; phase 3 is what the refusal was holding the
     * door for (design 19). The regex compiles for the table, so its classes mean 1252
     * characters: {@code [^\n]} consumes the {@code E9} byte as the character é, and the
     * capture decodes through the same table on the way out.
     */
    @Test
    void matchesWithARegexUnderADeclaredSingleByteEncoding() {
        final String config = """
                {
                  "name": "single-byte", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "windows-1252"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "row",
                     "match": {"regex": {"pattern": "L:([^\\n]*)\\n"}},
                     "body": [{"value-of": {"parts": [
                       {"text": "["}, {"capture": {"group": 1}}, {"text": "]"}]}}]}]
                }
                """;
        final byte[] input = {'L', ':', (byte) 0xE9, (byte) 0x93, '\n'};
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream(input),
                OutputSink.of(output));
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[é\u201C]");
    }

    /** RAW has no lowering until phase 4, and the transcode family none by design. */
    @Test
    void refusesARegexMatchUnderAnEncodingWithNoLowering() {
        final String config = """
                {
                  "name": "refused", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-16le"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "line", "match":
                     {"regex": {"pattern": "L:([^\\n]*)\\n"}}}]
                }
                """;
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(config)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("utf-16le")
                .hasMessageContaining("no lowering");
    }

    @Test
    void regexStepCompilesUnderATemplateEncodingOverride() {
        final String config = """
                {
                  "name": "override", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "line",
                     "encoding": "iso-8859-1",
                     "match": {"progressive": [
                       {"Tag": "L:"},
                       {"Regex": {"pattern": "([a-zé]+)", "flags": {}}}]}}]
                }
                """;
        assertThat(Shapeshifter.compile(ProjectReader.read(config))).isNotNull();
    }

    @Test
    void refusesARegexStepUnderAnOverrideWithNoLowering() {
        final String config = """
                {
                  "name": "refused", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "line",
                     "encoding": "utf-16le",
                     "match": {"progressive": [
                       {"Tag": "L:"},
                       {"Regex": {"pattern": "([a-z]+)", "flags": {}}}]}}]
                }
                """;
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(config)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("utf-16le")
                .hasMessageContaining("no lowering");
    }

    /**
     * The phase-3 audit's model: conditions match resolved values, whose internal form is
     * UTF-8 whatever the feed's encoding — so a {@code matches} under RAW compiles and is
     * not the match vocabulary's business. Phase 0 refused this conservatively; the audit
     * split the domains.
     */
    @Test
    void matchesConditionCompilesUnderRawBecauseValuesAreInternal() {
        final String config = """
                {
                  "name": "value-domain", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "raw"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "line",
                     "match": {"delimiter": {"delimiter": ","}},
                     "body": [{"choose": {"when": [
                       {"test": {"matches": {"select": {"parts": [{"capture": {"group": 0}}]},
                                             "pattern": "^[a-z]+$"}},
                        "body": [{"value-of": {"parts": [{"capture": {"group": 0}}]}}]}]}}]}]
                }
                """;
        assertThat(Shapeshifter.compile(ProjectReader.read(config))).isNotNull();
    }

    /** The match vocabulary sees feed bytes, and RAW has no lowering until phase 4. */
    @Test
    void refusesARegexMatchUnderRaw() {
        final String config = """
                {
                  "name": "refused", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "raw"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "line",
                     "match": {"regex": {"pattern": "([a-z]+)"}}}]
                }
                """;
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(config)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("raw")
                .hasMessageContaining("no lowering");
    }

    /**
     * Found by the phase-0 audit, and the refusal was the smaller half: the walkers saw
     * {@code PatternRef} where the executor sees the inlined steps, so a regex inside a
     * referenced library pattern was never interned — every use crashed at match time with
     * "Pattern was not compiled", regardless of encoding. Both walkers now resolve first;
     * this pins the crash's fix and the one below pins the refusal's.
     */
    @Test
    void regexInsideAReferencedLibraryPatternRuns() {
        final String config = """
                {
                  "name": "library", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "patterns": [
                    {"id": "00000000-0000-0000-0000-0000000000aa", "name": "word",
                     "steps": [{"Regex": {"pattern": "[a-z]+", "flags": {}}}]}],
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                     "match": {"progressive": [
                       {"PatternRef": "00000000-0000-0000-0000-0000000000aa"},
                       {"Tag": "\\n"}]},
                     "body": [{"value-of": {"parts": [
                       {"text": "["}, {"capture": {"group": 1}}, {"text": "]"}]}}]}
                  ]
                }
                """;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream("abc\n".getBytes(StandardCharsets.UTF_8)),
                OutputSink.of(output));
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[abc]");
    }

    @Test
    void refusesARegexReachedThroughALibraryPattern() {
        final String config = """
                {
                  "name": "refused", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-16le"},
                  "patterns": [
                    {"id": "00000000-0000-0000-0000-0000000000aa", "name": "word",
                     "steps": [{"Regex": {"pattern": "[a-z]+", "flags": {}}}]}],
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "line", "match":
                     {"progressive": [{"PatternRef": "00000000-0000-0000-0000-0000000000aa"}]}}]
                }
                """;
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(config)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("utf-16le")
                .hasMessageContaining("no lowering");
    }

    /**
     * Found by the phase-3 audit: guards were evaluated with the executor's project-level
     * encoding while their patterns were interned under the template-effective one, so a
     * guard's {@code matches} on an encoding-overridden template missed the map at run time
     * and died with "Pattern was not compiled". The guard now evaluates under the same
     * effective encoding its patterns were compiled for.
     */
    @Test
    void guardMatchesConditionHonoursTheTemplateEncodingOverride() {
        final String config = """
                {
                  "name": "guarded", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "row",
                     "encoding": "windows-1252",
                     "guard": {"matches": {"select": {"parts": [{"text": "é"}]},
                                           "pattern": "é"}},
                     "match": {"regex": {"pattern": "L:([^\\n]*)\\n"}},
                     "body": [{"value-of": {"parts": [
                       {"text": "["}, {"capture": {"group": 1}}, {"text": "]"}]}}]}]
                }
                """;
        final byte[] input = {'L', ':', (byte) 0xE9, '\n'};
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream(input),
                OutputSink.of(output));
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[é]");
    }
}
