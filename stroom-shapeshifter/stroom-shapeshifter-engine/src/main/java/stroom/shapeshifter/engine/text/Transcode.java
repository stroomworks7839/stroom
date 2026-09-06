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

package stroom.shapeshifter.engine.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;

/**
 * The whole-source `transcode` stage (design 19 phase 6; regex 01 §6.6): input in an
 * encoding the matcher cannot compile for — UTF-16, the CJK multi-byte family — becomes
 * UTF-8 bytes before the window machinery sees it, and everything downstream runs exactly
 * as it does for a UTF-8 feed. The target is always UTF-8 <em>bytes</em>, never {@code char}s:
 * the engine stays byte-level and surrogate handling stays in here.
 *
 * <p>Spans downstream are offsets into the transcoded bytes. That is §4.0's dividing line,
 * accepted with eyes open: for these encodings offset preservation was never on the table,
 * and the checkpointed source-offset map §6.6 describes belongs to the scoped `transcode`
 * combinator, which stays with the composition layer's vocabulary (E14) rather than this
 * whole-source stage.
 *
 * <p>Malformed input is governed as §6.6 rules — there is no default that silently corrupts:
 * by default a malformed sequence is an error naming the charset ({@code report}), and the
 * source's {@code ignore_errors} flag selects {@code replace} (U+FFFD), the engine's existing
 * meaning for that flag applied here.
 */
public final class Transcode {

    private Transcode() {
    }

    /** Wraps {@code in}, decoding {@code from} and emitting UTF-8 bytes. */
    public static InputStream wrap(final InputStream in,
                                   final Charset from,
                                   final boolean replaceMalformed) {
        final CharsetDecoder decoder = from.newDecoder()
                .onMalformedInput(replaceMalformed
                        ? CodingErrorAction.REPLACE
                        : CodingErrorAction.REPORT)
                .onUnmappableCharacter(replaceMalformed
                        ? CodingErrorAction.REPLACE
                        : CodingErrorAction.REPORT);
        return new TranscodingInputStream(new InputStreamReader(in, decoder), from);
    }

    /**
     * The reader-fed entry, for tests. The {@code Reader} contract does not promise that a
     * surrogate pair arrives within one {@code read} — {@code InputStreamReader} happens to
     * guarantee it through {@code StreamDecoder}'s leftover-char handling, which is why the
     * hold-back below cannot be exercised through {@link #wrap} and is pinned through here
     * instead, against a reader that splits pairs on purpose.
     */
    static InputStream wrap(final Reader reader, final Charset from) {
        return new TranscodingInputStream(reader, from);
    }

    /** Chunked reader-to-UTF-8 bridge, holding back a trailing high surrogate per chunk. */
    private static final class TranscodingInputStream extends InputStream {

        private static final int CHUNK = 8192;

        private final Reader reader;
        private final Charset from;
        private final char[] chars = new char[CHUNK + 1];

        /** A high surrogate the last chunk ended on, completed by the next chunk's first char. */
        private char pending;
        private boolean hasPending;

        private byte[] encoded = new byte[0];
        private int at;
        private boolean done;

        TranscodingInputStream(final Reader reader, final Charset from) {
            this.reader = reader;
            this.from = from;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            final int n = read(one, 0, 1);
            return n < 0
                    ? -1
                    : one[0] & 0xFF;
        }

        @Override
        public int read(final byte[] into, final int off, final int len) throws IOException {
            while (at >= encoded.length) {
                if (done) {
                    return -1;
                }
                fill();
            }
            final int n = Math.min(len, encoded.length - at);
            System.arraycopy(encoded, at, into, off, n);
            at += n;
            return n;
        }

        private void fill() throws IOException {
            int start = 0;
            if (hasPending) {
                chars[0] = pending;
                start = 1;
                hasPending = false;
            }
            final int n;
            try {
                n = reader.read(chars, start, CHUNK);
            } catch (final MalformedInputException | UnmappableCharacterException e) {
                // §6.6's report: an error naming the charset, never a silent substitution. The
                // text rides the cause, because the run's stream-failure message prints
                // the cause and would otherwise lose it.
                throw new UncheckedIOException(new IOException(
                        "the input is not valid " + from.name() + " (set ignore_errors to "
                        + "replace malformed sequences instead)", e));
            }
            if (n < 0) {
                if (start > 0) {
                    // The stream ended on a lone high surrogate: malformed by definition.
                    encoded = "�".getBytes(StandardCharsets.UTF_8);
                    at = 0;
                }
                done = true;
                return;
            }
            int end = start + n;
            if (end > 0 && Character.isHighSurrogate(chars[end - 1])) {
                pending = chars[end - 1];
                hasPending = true;
                end--;
            }
            encoded = new String(chars, 0, end).getBytes(StandardCharsets.UTF_8);
            at = 0;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
