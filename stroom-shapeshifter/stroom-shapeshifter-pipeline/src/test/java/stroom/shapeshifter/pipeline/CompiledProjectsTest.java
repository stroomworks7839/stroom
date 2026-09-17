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

package stroom.shapeshifter.pipeline;

import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.engine.ProjectReader;
import stroom.shapeshifter.engine.function.FunctionRegistry;
import stroom.shapeshifter.engine.text.Encoding;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pool, and where the reading is chosen (design 32 §4.1).
 *
 * <p>A parser is handed out before any byte of the input exists, so the encoding cannot be
 * settled where the parser is made. It is settled when a stream arrives, and the pool holds one
 * compiled model per reading a feed turns out to need.
 */
class CompiledProjectsTest {

    private static Project project(final String encoding) {
        return ProjectReader.read("""
                {"name": "t", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "%s"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source", "body": [{"text": "x"}]}]}
                """.formatted(encoding));
    }

    @Test
    void nothingIsCompiledUntilAReadingIsAskedFor() {
        // The factory used to compile in its constructor, so a configuration that was never
        // parsed with still paid for compiling.
        final CompiledProjects pool = new CompiledProjects(project("auto"), FunctionRegistry.EMPTY);
        assertThat(pool.size()).isZero();
    }

    @Test
    void oneModelPerReading() {
        final CompiledProjects pool = new CompiledProjects(project("auto"), FunctionRegistry.EMPTY);
        assertThat(pool.forEncoding(Encoding.UTF_8)).isSameAs(pool.forEncoding(Encoding.UTF_8));
        assertThat(pool.size()).isEqualTo(1);

        assertThat(pool.forEncoding(Encoding.LATIN_1)).isNotSameAs(pool.forEncoding(Encoding.UTF_8));
        assertThat(pool.size()).isEqualTo(2);
        assertThat(pool.forEncoding(Encoding.LATIN_1).encoding()).isEqualTo(Encoding.LATIN_1);
    }

    @Test
    void declaredReadingIsNotSecondGuessed() throws Exception {
        // An author who has said what the feed is should be believed, whatever the bytes look
        // like. These bytes are valid UTF-8 and the declaration says otherwise.
        final ShapeshifterReader reader =
                (ShapeshifterReader) new ShapeshifterParserFactory(project("iso-8859-1")).getParser();
        assertThat(reader.choose(stream("née")).project().encoding()).isEqualTo(Encoding.LATIN_1);
    }

    @Test
    void undeclaredReadingIsSniffedFromTheStream() throws Exception {
        final ShapeshifterReader reader =
                (ShapeshifterReader) new ShapeshifterParserFactory(project("auto")).getParser();
        assertThat(reader.choose(stream("plain ascii")).project().encoding())
                .isEqualTo(Encoding.UTF_8);
    }

    @Test
    void markOutranksTheDeclaration() throws Exception {
        // "The input is better evidence than the declaration" — the engine's rule since before
        // design 32, and it survives phase 4. What changed is where it is applied: before the
        // graph is compiled, so the regexes and delimiters follow the mark too. Only progressive
        // steps ever did, which is the asymmetry design 32 §2 exists to close.
        final ShapeshifterReader reader =
                (ShapeshifterReader) new ShapeshifterParserFactory(project("iso-8859-1")).getParser();
        assertThat(reader.choose(marked(0xEF, 0xBB, 0xBF)).project().encoding())
                .isEqualTo(Encoding.UTF_8);
    }

    @Test
    void transcodeFamilyMarkBecomesATranscodeRatherThanARefusal() throws Exception {
        // A UTF-16 mark used to stop the run: the regex library has no lowering for it, and the
        // message told the author to declare it so the stream would be transcoded whole. Settling
        // the encoding first does that for them — the graph is compiled to read UTF-8 and knows
        // what it transcodes from.
        final ShapeshifterReader reader =
                (ShapeshifterReader) new ShapeshifterParserFactory(project("auto")).getParser();
        final var chosen = reader.choose(marked(0xFF, 0xFE)).project();
        assertThat(chosen.transcodeFrom()).isEqualTo(Encoding.UTF_16LE);
        assertThat(chosen.encoding()).isEqualTo(Encoding.UTF_8);
    }

    @Test
    void declaredSourceCostsFourBytesRatherThanAWindow() throws Exception {
        // The engine streams and never holds its input whole (design 23). Sniffing a window up
        // front would break that for every run, including the ones with nothing to sniff — so a
        // mark is looked for in four bytes, and a declaration answers without reading more.
        final Counting counting = new Counting("2026-09-10 GET /index.html 200\n".repeat(500));
        final ShapeshifterReader reader =
                (ShapeshifterReader) new ShapeshifterParserFactory(project("utf-8")).getParser();
        reader.choose(counting);
        assertThat(counting.taken).isLessThanOrEqualTo(4);
    }

    /** A stream that says how much was taken from it. */
    private static final class Counting extends ByteArrayInputStream {

        private int taken;

        private Counting(final String text) {
            super(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public synchronized int read(final byte[] b, final int off, final int len) {
            final int got = super.read(b, off, len);
            taken += Math.max(0, got);
            return got;
        }
    }

    private static InputStream marked(final int... mark) {
        final byte[] head = new byte[mark.length + 4];
        for (int i = 0; i < mark.length; i++) {
            head[i] = (byte) mark[i];
        }
        return new ByteArrayInputStream(head);
    }

    @Test
    void sniffingConsumesNothing() {
        // The engine must see the input from its first byte. If the window were taken from the
        // stream, every run under an undeclared encoding would silently lose its first 8 KiB.
        final String text = "2026-09-10 GET /index.html 200\n";
        final ShapeshifterReader reader =
                (ShapeshifterReader) new ShapeshifterParserFactory(project("auto")).getParser();
        assertThat(readAll(reader, stream(text))).isEqualTo(text);
    }

    @Test
    void sniffingConsumesNothingWhenTheInputIsLongerThanTheWindow() {
        final StringBuilder big = new StringBuilder();
        while (big.length() < 40_000) {
            big.append("2026-09-10 GET /index.html 200 and some more text to fill the buffer\n");
        }
        final ShapeshifterReader reader =
                (ShapeshifterReader) new ShapeshifterParserFactory(project("auto")).getParser();
        assertThat(readAll(reader, stream(big.toString()))).isEqualTo(big.toString());
    }

    private static String readAll(final ShapeshifterReader reader, final InputStream input) {
        try {
            return new String(reader.choose(input).stream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    private static InputStream stream(final String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
