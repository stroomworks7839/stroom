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

        // Nothing is written, and — this is the part worth pinning — nothing is warned about
        // either. A guard that says "not mine" is a template declining to apply, not a template
        // failing to consume, so the check for unconsumed content never runs.
        assertThat(result.output()).isEmpty();
        assertThat(result.messages()).isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // Messages
    // -----------------------------------------------------------------------------------

    @Test
    void ignoreErrorsSilencesTheUnconsumedWarning() {
        final String config = """
                {
                  "name": "ignore", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": false, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                     "match": {"regex": {"pattern": "^nomatch$"}}, "ignore_errors": IGNORE,
                     "body": [{"text": "x"}]}
                  ]
                }
                """;
        assertThat(run(config.replace("IGNORE", "false"), "abc").messages()).isNotEmpty();
        assertThat(run(config.replace("IGNORE", "true"), "abc").messages()).isEmpty();
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

        assertThat(result.messages()).anyMatch(message ->
                message.severity() == Severity.ERROR
                && message.text().contains("Expected at least 2 matches but got 0"));
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
