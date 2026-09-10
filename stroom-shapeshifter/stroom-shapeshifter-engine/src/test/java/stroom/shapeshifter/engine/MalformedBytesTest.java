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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a value pattern sees when a UTF-8 feed carries bytes that are not UTF-8 (E45).
 *
 * <p>Nothing validates them. {@code TypedValue.of(bytes, encoding)} wraps a UTF-8-compatible
 * feed's bytes as they were read, because design 25 decided a value is not decoded until a
 * consumer asks for text — so "a value's internal form is UTF-8" is an assumption about the feed
 * rather than a checked invariant, and a real log with a truncated write or a stray byte breaks
 * it.
 *
 * <p>D38 ruled that <b>undecodable bytes match nothing</b>, and leniency comes only from
 * composition. A condition that decodes its subject before matching cannot honour that: the
 * replacement character it produces is a character, and a pattern can match it. This pins the
 * ruling on the one path that was quietly deciding otherwise.
 */
class MalformedBytesTest {

    /** A lone {@code 0xFF}: valid in no UTF-8 sequence, and U+FFFD once decoded. */
    private static final byte BAD = (byte) 0xFF;

    @Test
    void anUndecodableByteMatchesNothing() {
        assertThat(run("^.$")).isEqualTo("<out/>");
    }

    @Test
    void decodableValueStillMatches() {
        // The same configuration over a good byte, so the pattern is known to work at all.
        assertThat(run("^.$", (byte) 'a')).isEqualTo("<out>yes</out>");
    }

    private static String run(final String pattern) {
        return run(pattern, BAD);
    }

    private static String run(final String pattern, final byte subject) {
        final String json = """
                {"name": "t", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source",
                   "body": [
                     {"element": {"name": "out", "body": [
                       {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                         "mode": "doc"}}]}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "match": {"regex": {"pattern": "([^\\n]*)\\n"}},
                   "captures": [{"name": "field", "select": {"group": 1}}],
                   "body": [
                     {"if": {"test": {"matches": {
                         "select": {"parts": [{"capture": {"var_id": "field", "group": 0}}]},
                         "pattern": "%1$s"}},
                       "then": [{"text": "yes"}]}}]}]}
                """.formatted(pattern);
        final byte[] input = {subject, (byte) '\n'};
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input), new XmlByteSink(out));
        return out.toString(StandardCharsets.UTF_8).trim();
    }
}
