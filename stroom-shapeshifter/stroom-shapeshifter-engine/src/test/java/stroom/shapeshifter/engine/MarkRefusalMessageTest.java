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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refusal that tells an author to declare their encoding must describe <b>their</b>
 * configuration.
 *
 * <p>A UTF-16 byte-order mark reaching the window means the stream was not transcoded, and the
 * run stops rather than match UTF-8 machines against UTF-16 bytes. The message names what the
 * source says, and that is the whole of its value — a reader is being asked to go and change it.
 *
 * <p><b>Written because design 32 phase 3 broke it and 1,149 tests did not notice.</b> Settling
 * the encoding before compiling means an undeclared source now reads as UTF-8 by the time a run
 * starts, so a message built from the run's encoding told the author their source "is declared
 * utf-8" when they had declared nothing at all.
 */
class MarkRefusalMessageTest {

    /** A UTF-16LE mark, then a little text: enough to reach the refusal. */
    private static final byte[] MARKED_UTF16 = {(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0};

    @Test
    void anUndeclaredSourceIsNotToldItDeclaredSomething() {
        assertThat(refusal("auto"))
                .contains("declares no encoding")
                .doesNotContain("is declared");
    }

    @Test
    void declaredSourceIsQuotedBackAsItWasWritten() {
        assertThat(refusal("iso-8859-1")).contains("is declared iso-8859-1");
    }

    private static String refusal(final String encoding) {
        final String json = """
                {"name": "t", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "%s"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source", "body": [{"text": "x"}]}]}
                """.formatted(encoding);
        final List<Message> messages = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(MARKED_UTF16),
                new XmlByteSink(new ByteArrayOutputStream()));
        return messages.stream()
                .filter(m -> m.severity() == Severity.FATAL)
                .map(Message::text)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a refusal, got: " + messages));
    }
}
