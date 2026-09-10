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
import stroom.shapeshifter.engine.function.FunctionRegistry;
import stroom.shapeshifter.engine.output.XmlByteSink;
import stroom.shapeshifter.engine.text.Encoding;

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
 *
 * <p>Phase 4 added a second message beside it. A mark that merely <em>disagrees</em> no longer
 * moves the run — the graph carries its reading in every pattern, delimiter and step — so it is
 * skipped and said, because silence would leave a reader wondering why their mark had no effect.
 * Through the pipeline this cannot arise: the mark is acted on before compiling. Through the
 * engine's own API it can, and that is the caller this speaks to.
 */
class MarkRefusalMessageTest {

    /** A UTF-16LE mark, then a little text: enough to reach the refusal. */
    private static final byte[] MARKED_UTF16 = {(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0};

    /** A UTF-8 mark, which names no transcode family and so is a disagreement rather than a stop. */
    private static final byte[] MARKED_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};

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

    @Test
    void markThatDisagreesIsSkippedAndSaid() {
        // Compiled to read Latin-1, handed input marked UTF-8: the run keeps the reading it was
        // compiled for, and says so rather than letting the mark vanish silently.
        assertThat(run("iso-8859-1", Encoding.LATIN_1)).anySatisfy(m -> {
            assertThat(m.severity()).isEqualTo(Severity.WARNING);
            assertThat(m.text())
                    .contains("utf-8 byte-order mark")
                    .contains("compiled to read iso-8859-1")
                    .doesNotContain("null");
        });
    }

    @Test
    void markThatAgreesSaysNothing() {
        assertThat(run("utf-8", Encoding.UTF_8))
                .noneMatch(m -> m.severity() == Severity.WARNING);
    }

    /** Run a configuration compiled for a reading the caller chose, over UTF-8-marked input. */
    private static List<Message> run(final String declared, final Encoding compiledFor) {
        return Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(source(declared)),
                        FunctionRegistry.EMPTY, compiledFor),
                new ByteArrayInputStream(MARKED_UTF8),
                new XmlByteSink(new ByteArrayOutputStream()));
    }

    /** A configuration whose source declares an encoding, and does nothing else. */
    private static String source(final String encoding) {
        return """
                {"name": "t", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "%s"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source", "body": [{"text": "x"}]}]}
                """.formatted(encoding);
    }

    private static String refusal(final String encoding) {
        final List<Message> messages = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(source(encoding))),
                new ByteArrayInputStream(MARKED_UTF16),
                new XmlByteSink(new ByteArrayOutputStream()));
        return messages.stream()
                .filter(m -> m.severity() == Severity.FATAL)
                .map(Message::text)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a refusal, got: " + messages));
    }
}
