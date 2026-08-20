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
 * Engine behaviour the fixtures cannot reach, ported from the Rust crate's suite.
 *
 * <p>Every fixture fits in one buffer, so nothing in the corpus exercises chunking — the one
 * limitation this port deliberately kept (D33). No fixture uses {@code call-template} either, and
 * only DS3 import produces a guard. Those are exactly the places where a port can be wrong and
 * still show 48 of 48.
 */
class EngineBehaviourTest {

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

    /** A configuration that writes each line of its input in brackets. */
    private static String lines(final int bufferSize) {
        return """
                {
                  "name": "lines", "version": 3,
                  "source": {"buffer_size": SIZE, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "row",
                     "match": {"delimiter": {"delimiter": "\\n"}},
                     "body": [{"value-of": {"parts": [
                       {"text": "["}, {"capture": {"group": 1}}, {"text": "]"}]}}]}
                  ]
                }
                """.replace("SIZE", Integer.toString(bufferSize));
    }

    // -----------------------------------------------------------------------------------
    // Chunking
    // -----------------------------------------------------------------------------------

    @Test
    void readsInputLongerThanOneBuffer() {
        // Six records over a four-byte buffer: the loop has to run several times and carry
        // nothing between iterations except the variables.
        final Run result = run(lines(4), "a\nb\nc\nd\ne\nf\n");
        assertThat(result.output()).isEqualTo("[a][b][c][d][e][f]");
    }

    @Test
    void warnsWhenATemplateSwallowsAWholeFullBuffer() {
        // A record longer than the buffer cannot be matched whole, and the engine says so rather
        // than quietly emitting half of it. This is the limitation the port kept on purpose.
        final Run result = run(lines(4), "abcdefgh\n");
        assertThat(result.messages()).isNotEmpty();
        assertThat(result.messages().getFirst().text()).contains("consumed entire buffer");
        assertThat(result.messages().getFirst().severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    void doesNotWarnWhenTheBufferMerelyRanOut() {
        // The last buffer of a stream is short, and consuming all of it means the input ended —
        // not that a record was cut in half.
        assertThat(run(lines(1024), "a\nb\n").messages()).isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // Named templates
    // -----------------------------------------------------------------------------------

    @Test
    void callsANamedTemplateWithParameters() {
        final Run result = run("""
                {
                  "name": "call", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                     "match": {"delimiter": {"delimiter": "\\n"}},
                     "body": [{"call-template": {"name": "wrap", "with-param": [
                       ["label", {"parts": [{"text": "row"}]}],
                       ["value", {"parts": [{"capture": {"group": 1}}]}]]}}]},
                    {"id": "00000000-0000-0000-0000-000000000003", "name": "wrap", "match": "named",
                     "param": [{"name": "label"}, {"name": "sep", "default": "="}],
                     "body": [{"value-of": {"parts": [
                       {"capture": {"var_id": "label", "group": 0}},
                       {"capture": {"var_id": "sep", "group": 0}},
                       {"capture": {"var_id": "value", "group": 0}},
                       {"text": ";"}]}}]}
                  ]
                }
                """, "a\nb\n");

        // "sep" was not supplied, so its declared default applies.
        assertThat(result.output()).isEqualTo("row=a;row=b;");
    }

    @Test
    void parametersDoNotOutliveTheCall() {
        // Each call gets its own scope, so one call's arguments cannot be read by the next.
        final Run result = run("""
                {
                  "name": "scope", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                     "match": {"delimiter": {"delimiter": "\\n"}},
                     "body": [
                       {"call-template": {"name": "inner", "with-param": [
                         ["p", {"parts": [{"capture": {"group": 1}}]}]]}},
                       {"value-of": {"parts": [{"text": "<"},
                                               {"capture": {"var_id": "p", "group": 0}},
                                               {"text": ">"}]}}]},
                    {"id": "00000000-0000-0000-0000-000000000003", "name": "inner", "match": "named",
                     "body": [{"value-of": {"parts": [{"capture": {"var_id": "p", "group": 0}}]}}]}
                  ]
                }
                """, "a\n");

        // The parameter is visible inside the call and gone after it.
        assertThat(result.output()).isEqualTo("a<>");
    }

    // -----------------------------------------------------------------------------------
    // Guards
    // -----------------------------------------------------------------------------------

    @Test
    void guardStopsATemplateBeforeItMatches() {
        final String config = """
                {
                  "name": "guard", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "guarded", "mode": "row",
                     "guard": {"equals": {"select": {"parts": [{"text": "no"}]}, "value": "yes"}},
                     "match": {"delimiter": {"delimiter": "\\n"}},
                     "body": [{"text": "matched"}]}
                  ]
                }
                """;
        final Run result = run(config, "a\nb\n");

        // Nothing is written, and the report that follows accuses nobody in particular: a guard
        // that says "not mine" is a template declining, so what gets reported is the level's
        // failure to match the content — DS3's own message — not the template's failure to
        // consume it (D34).
        assertThat(result.output()).isEmpty();
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().getFirst().severity()).isEqualTo(Severity.ERROR);
        assertThat(result.messages().getFirst().text())
                .contains("Expressions failed to match all of the content");
    }

    // -----------------------------------------------------------------------------------
    // Messages
    // -----------------------------------------------------------------------------------

    @Test
    void unmatchedContentIsReportedOncePerLevelAndGatedByTheContainer() {
        // A template matches the first line and nothing can match the rest. The report belongs
        // to the level, fires once, and is gated the way DS3 gates it: by the container — the
        // source configuration at the root, or the dispatching directive below it — never by
        // the templates inside.
        final String config = """
                {
                  "name": "leftover", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": SOURCE_IGNORE, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"DIRECTIVE_IGNORE}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                     "match": {"regex": {"pattern": "^a\\n"}},
                     "body": [{"text": "[a]"}]}
                  ]
                }
                """;
        final String plain = config.replace("SOURCE_IGNORE", "false").replace("DIRECTIVE_IGNORE", "");

        final Run reported = run(plain, "a\nBAD");
        assertThat(reported.output()).isEqualTo("[a]");
        assertThat(reported.messages()).hasSize(1);
        assertThat(reported.messages().getFirst().severity()).isEqualTo(Severity.ERROR);
        assertThat(reported.messages().getFirst().text())
                .contains("Expressions failed to match all of the content")
                .contains("[BAD]");

        // The root gate: DS3's ignoreErrors on the dataSplitter element itself.
        assertThat(run(config.replace("SOURCE_IGNORE", "true").replace("DIRECTIVE_IGNORE", ""),
                "a\nBAD").messages()).isEmpty();

        // The directive gate: DS3's ignoreErrors on the group whose content is dispatched.
        assertThat(run(plain.replace("\"mode\": \"row\"", "\"mode\": \"row\", \"ignore_errors\": true"),
                "a\nBAD").messages()).isEmpty();
    }

    @Test
    void reportsWhenATemplateMatchedTooFewTimes() {
        final Run result = run("""
                {
                  "name": "min", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                     "match": {"regex": {"pattern": "^nomatch$"}},
                     "match_limits": {"min_match": 2, "max_match": -1}, "ignore_errors": true,
                     "body": [{"text": "x"}]}
                  ]
                }
                """, "abc");

        // One message, not two: the minimum-match error already explains the unmatched
        // content, so the level's own report stands down — which is exactly the shape of
        // Stroom's record for fixture 014.
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().getFirst().severity()).isEqualTo(Severity.ERROR);
        assertThat(result.messages().getFirst().text())
                .contains("did not match the required number of times (match count: 0)");
    }

    // -----------------------------------------------------------------------------------
    // Dispatch (D34 / E17)
    // -----------------------------------------------------------------------------------

    @Test
    void dispatchReopensTheChoiceAfterEveryMatch() {
        // Interleaved record kinds, each with its own anchored template. Under per-template
        // exhaustion this cannot work — A finishes, B finishes, and the third line is stranded.
        // Under (A|B|C)* each pass re-opens the choice from the first template, which is DS3's
        // model and the whole point of E17.
        final Run result = run("""
                {
                  "name": "interleave", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "a", "mode": "row",
                     "match": {"regex": {"pattern": "^a=([0-9]+)[|]"}},
                     "body": [{"value-of": {"parts": [{"text": "[A:"}, {"capture": {"group": 1}},
                                                      {"text": "]"}]}}]},
                    {"id": "00000000-0000-0000-0000-000000000003", "name": "b", "mode": "row",
                     "match": {"regex": {"pattern": "^b=([0-9]+)[|]"}},
                     "body": [{"value-of": {"parts": [{"text": "[B:"}, {"capture": {"group": 1}},
                                                      {"text": "]"}]}}]}
                  ]
                }
                """, "a=1|b=2|a=3|");

        assertThat(result.output()).isEqualTo("[A:1][B:2][A:3]");
        assertThat(result.messages()).isEmpty();
    }

    @Test
    void passIsWonByListOrderAndTheSkipIsReported() {
        // The sharp edge D34 keeps, said out loud instead of silently: an earlier-listed
        // unanchored template matching later still beats a later-listed one matching earlier,
        // and the content it jumped over is consumed — with a report naming it.
        final Run result = run("""
                {
                  "name": "skip", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "late", "mode": "row",
                     "match": {"regex": {"pattern": "ZZ"}},
                     "body": [{"text": "[late]"}]},
                    {"id": "00000000-0000-0000-0000-000000000003", "name": "early", "mode": "row",
                     "match": {"regex": {"pattern": "^head"}},
                     "body": [{"text": "[early]"}]}
                  ]
                }
                """, "headZZ");

        // 'late' is listed first and wins by list order, so 'early' never sees the head it was
        // named for — and the skip says exactly what was lost.
        assertThat(result.output()).isEqualTo("[late]");
        assertThat(result.messages()).anyMatch(message ->
                message.severity() == Severity.ERROR
                && message.text().contains("failed to match from the start of the content")
                && message.text().contains("[head]"));
    }

    @Test
    void theOriginalWinSecConfigurationStrandsLoudlyNow() throws Exception {
        // The configuration E6 and E16 fixed, exactly as it was before the fixes (git,
        // 6907ad310c^). Under D34's dispatch it still strands the Object block — a pass is won
        // by list order, not buffer position — but the loss is now reported with the stranded
        // content in it. This is the fixture that would have made the original defect
        // impossible to miss, run as the acceptance test for the reports that make it so.
        final String config;
        try (var in = EngineBehaviourTest.class.getResourceAsStream("/e17/win_sec-original.project.json")) {
            config = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // A finding of its own: the original configuration set ignore_errors at the source, a
        // flag ds-rs never enforced — the author asked for silence and got it by accident
        // instead. Under D34 the flag works and would gate these reports, so the test opens the
        // gate to see them.
        final String gated = config.replace("\"ignore_errors\": true", "\"ignore_errors\": false");
        final Run result = run(gated, new String(
                stroom.shapeshifter.engine.fixture.FixtureLedger.bytes("projects/win_sec/input.txt"),
                StandardCharsets.UTF_8));

        assertThat(result.messages()).anyMatch(message ->
                message.severity() == Severity.ERROR
                && message.text().contains("failed to match from the start of the content")
                && message.text().contains("Object"));
    }

    @Test
    void multilineAnchorsAreNotMistakenForStartAnchors() {
        // Compile-time anchoring detection (D35's first optimisation) dispatches provably
        // start-anchored patterns as one attempt at the cursor. (?m) turns ^ into a line
        // anchor, so this pattern can legitimately match past the cursor — the detection must
        // leave it on the search path, and the skip must be reported as ever.
        final Run result = run("""
                {
                  "name": "multiline", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "x", "mode": "row",
                     "match": {"regex": {"pattern": "(?m)^x=([0-9])"}},
                     "body": [{"value-of": {"parts": [{"text": "[X:"}, {"capture": {"group": 1}},
                                                      {"text": "]"}]}}]}
                  ]
                }
                """, "skip\nx=7");

        assertThat(result.output()).isEqualTo("[X:7]");
        assertThat(result.messages()).anyMatch(message ->
                message.text().contains("failed to match from the start of the content"));
    }

    // -----------------------------------------------------------------------------------
    // Capture lifecycle (E19)
    // -----------------------------------------------------------------------------------

    /** Records of x= items; each record prints its most recent captured value afterwards. */
    private static final String STALE_CONFIG = """
            {
              "name": "stale", "version": 3,
              "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
              "templates": [
                {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                 "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                               "mode": "rec"}}]},
                {"id": "00000000-0000-0000-0000-000000000002", "name": "rec", "mode": "rec",
                 "match": {"delimiter": {"delimiter": ";"}},
                 "body": [
                   {"apply-templates": {"select": {"parts": [{"capture": {"group": 1}}]},
                                        "mode": "item"}},
                   {"value-of": {"parts": [{"text": "<"},
                                           {"capture": {"var_id": "val", "group": 0}},
                                           {"text": ">"}]}}]},
                {"id": "00000000-0000-0000-0000-000000000003", "name": "item", "mode": "item",
                 "match": {"regex": {"pattern": "x=([a-z]+),?"}},
                 "captures": [{"name": "val", "select": {"group": 1}}],
                 "body": []}
              ]
            }
            """;

    @Test
    void newMatchSequenceClearsTheCapturesStore() {
        // Record 1 captures twice, record 2 once. Without DS3's clear-on-new-sequence rule the
        // second record's read finds record 1's tail sitting past its own single value — the
        // divergence E19 fixed.
        assertThat(run(STALE_CONFIG, "x=a,x=b;x=c").output()).isEqualTo("<b><c>");
    }

    @Test
    void templateThatNeverMatchesLeavesItsStoreUntouched() {
        // The other half of E19, pinned as DS3-faithful rather than fixed: when the template
        // never matches at all, nothing clears, and the reference reads the previous record's
        // value. Real DS3 does exactly this — its clear only happens on the first *store* of a
        // sequence, and a template that never matches never stores. A configuration that does
        // not want the leak anchors its patterns and lets absence mean absence per record type,
        // or guards its references.
        assertThat(run(STALE_CONFIG, "x=a,x=b;y=z").output()).isEqualTo("<b><b>");
    }

    // -----------------------------------------------------------------------------------
    // Configuration errors
    // -----------------------------------------------------------------------------------

    @Test
    void refusesAPatternThatRefersToItself() {
        // Without this the compiler inlines for ever and the stack runs out, which is a much
        // worse way to learn that a configuration is circular.
        final String cyclic = """
                {
                  "name": "cycle", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "patterns": [
                    {"id": "00000000-0000-0000-0000-0000000000aa", "name": "loop",
                     "steps": [{"PatternRef": "00000000-0000-0000-0000-0000000000aa"}]}],
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "t", "match":
                     {"progressive": [{"PatternRef": "00000000-0000-0000-0000-0000000000aa"}]}}]
                }
                """;
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(cyclic)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("refers to itself");
    }

    @Test
    void refusesAPatternReferenceToNothing() {
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read("""
                {
                  "name": "missing", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "t", "match":
                     {"progressive": [{"PatternRef": "00000000-0000-0000-0000-0000000000bb"}]}}]
                }
                """)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("No pattern with id");
    }

    @Test
    void allowsAPatternUsedTwiceInDifferentPlaces() {
        // Reuse is the point of named patterns; only a pattern reached from inside itself is a
        // cycle, so unwinding the in-progress set matters.
        final String reused = """
                {
                  "name": "reuse", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "patterns": [
                    {"id": "00000000-0000-0000-0000-0000000000aa", "name": "digit",
                     "steps": [{"TakeWhile": "Numeric"}]}],
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "t", "match":
                     {"progressive": [
                       {"PatternRef": "00000000-0000-0000-0000-0000000000aa"},
                       {"Tag": "-"},
                       {"PatternRef": "00000000-0000-0000-0000-0000000000aa"}]}}]
                }
                """;
        assertThat(Shapeshifter.compile(ProjectReader.read(reused))).isNotNull();
    }

    @Test
    void refusesAConfigurationNeedingSomethingThisBuildLacks() {
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read("""
                {
                  "name": "avro", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "t", "match": {"avro": {}}}]
                }
                """)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Avro decoding");
    }
}
