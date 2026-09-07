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
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.value.TypedValue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
                new XmlByteSink(output));
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
                new XmlByteSink(output));
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
                new XmlByteSink(output));
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
                new XmlByteSink(output));
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[é“]"); // U+201C, left double quotation mark
    }

    /**
     * Phase 6 (design 19): a transcode-family source is decoded whole to UTF-8 before the
     * window machinery reads it, so a UTF-16LE feed compiles and matches as a UTF-8 feed —
     * regex included. Spans are offsets into the transcoded bytes, by 01 §4.0's own trade.
     */
    @Test
    void utf16SourceIsTranscodedAndMatchesEndToEnd() {
        final String config = """
                {
                  "name": "transcoded", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-16le"},
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
        final byte[] input = "L:é中\n".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream(input),
                new XmlByteSink(output));
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[é中]");
    }

    @Test
    void malformedUtf16ReportsByDefaultAndReplacesUnderIgnoreErrors() {
        final String config = """
                {
                  "name": "malformed", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": IGNORE, "encoding": "utf-16le"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "row",
                     "match": {"regex": {"pattern": "([^\\n]*)\\n"}},
                     "body": [{"value-of": {"parts": [{"capture": {"group": 1}}]}}]}]
                }
                """;
        // A lone high surrogate: no UTF-16 decoding can honour it. The engine's contract for
        // a failing stream is a FATAL message, not a throw — everything already said stands.
        final byte[] input = {'a', 0, (byte) 0x00, (byte) 0xD8, 'b', 0, '\n', 0};
        final List<Message> reported = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config.replace("IGNORE", "false"))),
                new ByteArrayInputStream(input),
                new XmlByteSink(new ByteArrayOutputStream()));
        assertThat(reported).anyMatch(m -> m.severity() == Severity.FATAL
                && m.text().contains("UTF-16LE"));
        final ByteArrayOutputStream replaced = new ByteArrayOutputStream();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config.replace("IGNORE", "true"))),
                new ByteArrayInputStream(input),
                new XmlByteSink(replaced));
        // How many code units the decoder folds into one replacement is its own business;
        // what matters is that data flowed and the malformed span became U+FFFD, not a loss.
        assertThat(replaced.toString(StandardCharsets.UTF_8)).contains("a�"); // U+FFFD, the replacement character
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
    void refusesATranscodeFamilyTemplateOverride() {
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
                .hasMessageContaining("declare it on the source");
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

    /**
     * Phase 4 (design 19): RAW lowers as the identity table, so a regex match over binary
     * bytes works — every byte its own character, byte spans, no text pretence. The capture
     * decodes through the engine's RAW reading (byte as code point) on the way out.
     */
    @Test
    void matchesWithARegexUnderRaw() {
        final String config = """
                {
                  "name": "raw", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "raw"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "rec", "mode": "row",
                     "match": {"regex": {"pattern": "X([\\\\x80-\\\\xff]+)X"}},
                     "body": [{"value-of": {"parts": [
                       {"text": "["}, {"capture": {"group": 1}}, {"text": "]"}]}}]}]
                }
                """;
        final byte[] input = {'X', (byte) 0x93, (byte) 0xE9, 'X'};
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream(input),
                new XmlByteSink(output));
        // RAW reads each byte as its own code point: 0x93 is U+0093, 0xE9 is é.
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[\u0093é]");
    }

    /**
     * Found by the phase-0 audit, and the refusal was the smaller half: the walkers saw
     * {@code PatternRef} where the steps see the inlined sequence, so a regex inside a
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
                new XmlByteSink(output));
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[abc]");
    }

    @Test
    void refusesARegexReachedThroughALibraryPattern() {
        final String config = """
                {
                  "name": "refused", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "patterns": [
                    {"id": "00000000-0000-0000-0000-0000000000aa", "name": "word",
                     "steps": [{"Regex": {"pattern": "[a-z]+", "flags": {}}}]}],
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "line",
                     "encoding": "utf-16le", "match":
                     {"progressive": [{"PatternRef": "00000000-0000-0000-0000-0000000000aa"}]}}]
                }
                """;
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(config)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("utf-16le")
                .hasMessageContaining("declare it on the source");
    }

    /**
     * Found by the phase-3 audit: guards were evaluated with the run's project-level
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
                new XmlByteSink(output));
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[é]");
    }

    /**
     * The phase-6 audit's rule: E3's per-template encodings describe rows of a mixed byte
     * stream, and a transcoded source has none left — every template sees the decoder's
     * UTF-8. An override that would compile a machine for bytes no template can see is
     * refused, not silently mis-aimed.
     */
    @Test
    void refusesAnyTemplateEncodingOverrideUnderATranscodedSource() {
        final String config = """
                {
                  "name": "mixed", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-16le"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "line",
                     "encoding": "windows-1252",
                     "match": {"delimiter": {"delimiter": ","}}}]
                }
                """;
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(config)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("transcoded whole")
                .hasMessageContaining("windows-1252");
    }

    /**
     * Astral characters through the whole pipeline, end to end. The audit note that keeps
     * this honest: {@code InputStreamReader} never splits a pair across reads, so this test
     * cannot reach the transcoder's hold-back — {@code TranscodeTest} pins that directly,
     * with a reader that splits pairs on purpose. This one pins the pipeline.
     */
    @Test
    void surrogatePairsSurviveTheTranscoderChunkBoundary() {
        final String config = """
                {
                  "name": "chunks", "version": 4,
                  "source": {"buffer_size": 200000, "ignore_errors": false, "encoding": "utf-16le"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "row",
                     "match": {"regex": {"pattern": "([^\\n]*)\\n"}},
                     "body": [{"value-of": {"parts": [{"capture": {"group": 1}}]}}]}]
                }
                """;
        final String line = "x".repeat(8191) + "𐍈" + "y\n" // U+10348, a supplementary character: four bytes
                            + "tail𐍈\n";
        final byte[] input = line.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream(input),
                new XmlByteSink(output));
        final String out = output.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("𐍈y");
        assertThat(out).contains("tail𐍈");
    }

    // -----------------------------------------------------------------------------------
    // Design 25 phase 1: a capture is the bytes it matched, tagged; nothing is transcoded
    // until a consumer asks
    // -----------------------------------------------------------------------------------

    /** Keeps what each capture was bound to. */
    private static final class CaptureRecorder implements Instrument {

        private final List<TypedValue> bound = new ArrayList<>();

        @Override
        public void onCapture(final UUID templateId, final String name, final TypedValue value,
                              final int matchIndex) {
            bound.add(value);
        }
    }

    @Test
    void rawCaptureHoldsTheBytesItMatched() {
        final String config = """
                {
                  "name": "raw-capture", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "raw"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "rec", "mode": "row",
                     "match": {"regex": {"pattern": "X([\\\\x80-\\\\xff]+)X"}},
                     "captures": [{"name": "payload", "select": {"group": 1}}],
                     "body": [{"value-of": {"parts": [
                       {"text": "["}, {"capture": {"var_id": "payload", "group": 0}}, {"text": "]"}]}}]}]
                }
                """;
        final byte[] input = {'X', (byte) 0x93, (byte) 0xE9, 'X'};
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final CaptureRecorder recorder = new CaptureRecorder();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream(input),
                new XmlByteSink(output), recorder);
        // The store holds the two bytes read, tagged raw — not their four-byte UTF-8 image.
        assertThat(recorder.bound).singleElement()
                .isInstanceOfSatisfying(TypedValue.Bytes.class, bytes -> {
                    assertThat(bytes.value()).containsExactly(0x93, 0xE9);
                    assertThat(bytes.encoding()).isEqualTo(Encoding.RAW);
                });
        // Written to the UTF-8 sink, it decodes by its tag on the way out: the same answer as
        // before, now computed at the write rather than at the capture.
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[\u0093é]");
    }

    /**
     * The case E3's write-path split answered by provenance and the tag now answers by itself:
     * a value captured under one template's encoding, written by a template with another.
     */
    @Test
    void valueCapturedUnderOneEncodingIsWrittenRightByAnother() {
        final String config = """
                {
                  "name": "cross", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "windows-1252"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "a", "mode": "row",
                     "match": {"regex": {"pattern": "A:([^\\\\n]*)\\\\n"}},
                     "captures": [{"name": "held", "select": {"group": 1}}],
                     "body": []},
                    {"id": "00000000-0000-0000-0000-000000000003", "name": "b", "mode": "row",
                     "encoding": "iso-8859-1",
                     "match": {"regex": {"pattern": "B:\\\\n"}},
                     "body": [{"value-of": {"parts": [
                       {"text": "["}, {"capture": {"var_id": "held", "group": 0}}, {"text": "]"}]}}]}]
                }
                """;
        final byte[] input = {'A', ':', (byte) 0x93, '\n', 'B', ':', '\n'};
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream(input),
                new XmlByteSink(output));
        // 0x93 is U+201C under windows-1252, which captured it, and U+0093 under the writer's
        // Latin-1; the value's own tag decides.
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[“]");
    }

    // -----------------------------------------------------------------------------------
    // Design 25 phase 2: the sink declares what it accepts, and a write transcodes to it
    // -----------------------------------------------------------------------------------

    private static byte[] runInto(final String config, final byte[] input, final Encoding target) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream(input),
                OutputSink.of(output, target));
        assertThat(messages).noneMatch(m -> m.severity() == Severity.FATAL);
        return output.toByteArray();
    }

    @Test
    void rawIntoARawSinkIsTheBytesItMatched() {
        final byte[] input = {(byte) 0x93, (byte) 0xE9};
        assertThat(runInto(config("raw"), input, Encoding.RAW))
                .containsExactly('[', 0x93, 0xE9, ']');
    }

    @Test
    void latin1IntoALatin1SinkIsTheBytesItMatched() {
        final byte[] input = {(byte) 0xE9, (byte) 0xC7};
        assertThat(runInto(config("iso-8859-1"), input, Encoding.LATIN_1))
                .containsExactly('[', 0xE9, 0xC7, ']');
        // The same feed into the UTF-8 sink is the decoded text, as before.
        assertThat(run("iso-8859-1", input)).isEqualTo("[éÇ]");
    }

    @Test
    void literalTheSinkCannotExpressBecomesAQuestionMark() {
        // Only a literal can put a character above 0xFF into a raw sink; a raw capture never
        // has one.
        final byte[] input = {(byte) 0xE9};
        final String config = config("raw").replace("\"text\": \"[\"", "\"text\": \"€\"");
        assertThat(runInto(config, input, Encoding.RAW)).containsExactly('?', 0xE9, ']');
    }

    @Test
    void structureIntoANonUtf8SinkIsRefused() {
        final String config = """
                {
                  "name": "structured", "version": 4,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "raw"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"element": {"name": "doc", "body": [{"text": "x"}]}}]}]
                }
                """;
        final List<Message> messages = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(config)),
                new ByteArrayInputStream(new byte[]{'a'}),
                OutputSink.of(new ByteArrayOutputStream(), Encoding.RAW));
        assertThat(messages).anyMatch(m -> m.severity() == Severity.FATAL
                && m.text().contains("does not carry structure"));
    }
}
