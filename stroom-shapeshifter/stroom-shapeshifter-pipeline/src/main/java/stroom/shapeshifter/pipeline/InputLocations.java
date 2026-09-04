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

import stroom.shapeshifter.engine.Instrument;
import stroom.shapeshifter.engine.OutputSink;

import org.xml.sax.Locator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Where in the <i>input</i> each part of the output came from — the locator work D10 owed and
 * design 21 phase 1b left open.
 *
 * <p>Recorded as an {@link Instrument} while the configuration runs: every match's input offset
 * ({@link #onMatch}) paired with its output byte span ({@link #onOutput}, in bytes because the
 * reader runs the byte sink). Then, while the output is parsed and forwarded, the parser's
 * position — a line and column in the generated text — is turned into an output byte offset,
 * the innermost span holding that offset names the match, and the match's input offset becomes
 * a line and column in the input. That pair is what the {@link Locator} handed to the pipeline
 * reports, so stepping and error indicators point at the record that produced an event rather
 * than at text the user never sees.
 *
 * <p>Spans nest, so "innermost" is the shortest span containing the offset. Content that came
 * from a variable has no input position ({@link Instrument#UNLOCATABLE}) and reports none. The
 * parser's column counts characters; the input's column is in bytes — the same for the ASCII
 * these configurations mostly carry, and named here so the difference is not mistaken for a bug.
 */
final class InputLocations implements Instrument {

    private record Span(long offset, long length, long inputOffset) {

        long end() {
            return offset + length;
        }
    }

    private record Open(UUID templateId, int matchIndex, long inputOffset) {

    }

    /**
     * Matches whose bodies are running, innermost last. {@code onMatch} opens one and the
     * {@code onOutput} for the same template and match closes it — they bracket the body in
     * order — so a span carries its own input offset and no key is needed. A match index restarts
     * with each parent match, which is why a key would not have worked. An eater reports a match
     * and no output; its entry is discarded when the enclosing match closes.
     */
    private final Deque<Open> open = new ArrayDeque<>();
    private final List<Span> spans = new ArrayList<>();

    // -----------------------------------------------------------------------------------
    // Recording, during the run
    // -----------------------------------------------------------------------------------

    @Override
    public void onMatch(final UUID templateId,
                        final String templateName,
                        final long inputOffset,
                        final int inputLength,
                        final int matchIndex,
                        final int depth) {
        open.push(new Open(templateId, matchIndex, inputOffset));
    }

    @Override
    public void onOutput(final UUID templateId,
                         final int matchIndex,
                         final long outputOffset,
                         final long outputLength,
                         final OutputSink.Unit unit) {
        if (unit != OutputSink.Unit.BYTES) {
            return;
        }
        while (!open.isEmpty()) {
            final Open match = open.pop();
            if (match.templateId.equals(templateId) && match.matchIndex == matchIndex) {
                spans.add(new Span(outputOffset, outputLength, match.inputOffset));
                return;
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Resolving, during the parse
    // -----------------------------------------------------------------------------------

    /**
     * A locator over an input and the output the run produced from it. The input is described
     * by where its lines start, because a streamed input (design 22) is not held to be scanned:
     * {@link LineIndex} records the starts as the bytes go by.
     */
    Resolver resolver(final long[] inputLineStarts, final byte[] output) {
        return new Resolver(inputLineStarts, output);
    }

    /** Where each line of a byte sequence starts, for one that is held whole. */
    static long[] lineStarts(final byte[] bytes) {
        long[] starts = new long[16];
        int n = 0;
        starts[n++] = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                if (n == starts.length) {
                    starts = Arrays.copyOf(starts, n * 2);
                }
                starts[n++] = i + 1L;
            }
        }
        return Arrays.copyOf(starts, n);
    }

    /** An input stream that records where its lines start as they are read. */
    static final class LineIndex extends java.io.FilterInputStream {

        private long[] starts = new long[16];
        private int count = 1;
        private long position;

        LineIndex(final java.io.InputStream in) {
            super(in);
        }

        long[] lineStarts() {
            return Arrays.copyOf(starts, count);
        }

        @Override
        public int read() throws java.io.IOException {
            final int b = super.read();
            if (b >= 0) {
                note((byte) b);
                position++;
            }
            return b;
        }

        @Override
        public int read(final byte[] into, final int offset, final int length) throws java.io.IOException {
            final int n = super.read(into, offset, length);
            for (int i = 0; i < n; i++) {
                note(into[offset + i]);
                position++;
            }
            return n;
        }

        private void note(final byte b) {
            if (b == '\n') {
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, count * 2);
                }
                starts[count++] = position + 1;
            }
        }
    }

    /**
     * Resolves in a single forward sweep. The parser reports positions in output order, so the
     * spans — sorted by offset, parents before the children they enclose — are opened as the
     * sweep reaches them and closed as it passes their ends; the innermost open span is the top
     * of that stack. Amortised linear in events plus spans, and nothing is decoded: a column,
     * which the parser counts in characters, is walked to on the bytes of its own line.
     */
    final class Resolver implements Locator {

        private final byte[] output;
        private final long[] inputLineStarts;
        private final long[] outputLineStarts;
        private final List<Span> ordered;
        private final Deque<Span> active = new ArrayDeque<>();
        private int next;
        private long lastOffset = -1;
        private int line = -1;
        private int column = -1;

        private Resolver(final long[] inputLineStarts, final byte[] output) {
            this.output = output;
            this.inputLineStarts = inputLineStarts;
            this.outputLineStarts = lineStarts(output);
            this.ordered = new ArrayList<>(spans);
            this.ordered.sort((a, b) -> a.offset != b.offset
                    ? Long.compare(a.offset, b.offset)
                    : Long.compare(b.length, a.length));
        }

        /** Move to the input position behind the parser's current output position. */
        void at(final Locator parser) {
            final long outputOffset = outputOffset(parser.getLineNumber(), parser.getColumnNumber());
            if (outputOffset < lastOffset) {
                // Not expected of a parser, but a sweep that went backwards would answer wrongly.
                active.clear();
                next = 0;
            }
            lastOffset = outputOffset;
            while (!active.isEmpty() && active.peek().end() <= outputOffset) {
                active.pop();
            }
            while (next < ordered.size() && ordered.get(next).offset <= outputOffset) {
                final Span span = ordered.get(next++);
                if (span.end() > outputOffset) {
                    active.push(span);
                }
            }
            final Span span = active.peek();
            if (span == null || span.inputOffset >= Instrument.UNLOCATABLE) {
                line = -1;
                column = -1;
                return;
            }
            final int at = lineIndex(inputLineStarts, span.inputOffset);
            line = at + 1;
            column = (int) (span.inputOffset - inputLineStarts[at]) + 1;
        }

        /**
         * The output byte just before the parser's position, which is inside whatever it has just
         * reported. The parser's column counts UTF-16 units, so a four-byte sequence counts two.
         */
        private long outputOffset(final int parserLine, final int parserColumn) {
            if (parserLine < 1 || parserLine > outputLineStarts.length) {
                return -1;
            }
            long at = outputLineStarts[parserLine - 1];
            int units = 0;
            while (units < parserColumn - 1 && at < output.length) {
                final int lead = output[(int) at] & 0xFF;
                final int width = lead < 0x80 ? 1 : lead < 0xE0 ? 2 : lead < 0xF0 ? 3 : 4;
                units += width == 4 ? 2 : 1;
                at += width;
            }
            return Math.max(0, at - 1);
        }

        @Override
        public int getLineNumber() {
            return line;
        }

        @Override
        public int getColumnNumber() {
            return column;
        }

        @Override
        public String getPublicId() {
            return null;
        }

        @Override
        public String getSystemId() {
            return null;
        }
    }

    // -----------------------------------------------------------------------------------
    // Tables
    // -----------------------------------------------------------------------------------

    private static int lineIndex(final long[] lineStarts, final long offset) {
        int at = Arrays.binarySearch(lineStarts, offset);
        if (at < 0) {
            at = -at - 2;
        }
        return Math.max(0, Math.min(at, lineStarts.length - 1));
    }
}
