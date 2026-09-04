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

import java.nio.charset.StandardCharsets;
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

    /** A locator over an input and the output the run produced from it. */
    Resolver resolver(final byte[] input, final byte[] output) {
        return new Resolver(input, output);
    }

    final class Resolver implements Locator {

        private final long[] inputLineStarts;
        private final int[] outputLineStartChars;
        private final int[] outputByteOfChar;
        private int line = -1;
        private int column = -1;

        private Resolver(final byte[] input, final byte[] output) {
            this.inputLineStarts = lineStarts(input);
            final String text = new String(output, StandardCharsets.UTF_8);
            this.outputLineStartChars = lineStartChars(text);
            this.outputByteOfChar = byteOfChar(text);
        }

        /** Move to the input position behind the parser's current output position. */
        void at(final Locator parser) {
            final long outputOffset = outputOffset(parser.getLineNumber(), parser.getColumnNumber());
            final Span span = innermost(outputOffset);
            if (span == null || span.inputOffset >= Instrument.UNLOCATABLE) {
                line = -1;
                column = -1;
                return;
            }
            final int at = lineIndex(inputLineStarts, span.inputOffset);
            line = at + 1;
            column = (int) (span.inputOffset - inputLineStarts[at]) + 1;
        }

        private long outputOffset(final int parserLine, final int parserColumn) {
            if (parserLine < 1 || parserLine > outputLineStartChars.length) {
                return -1;
            }
            final int charIndex = Math.min(
                    outputByteOfChar.length - 1,
                    outputLineStartChars[parserLine - 1] + Math.max(0, parserColumn - 1));
            // The parser's position is just after what it reported; the byte before it is
            // inside the thing that produced it.
            return Math.max(0, outputByteOfChar[charIndex] - 1);
        }

        private Span innermost(final long outputOffset) {
            Span best = null;
            for (final Span span : spans) {
                if (outputOffset >= span.offset && outputOffset < span.offset + span.length
                    && (best == null || span.length < best.length)) {
                    best = span;
                }
            }
            return best;
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

    private static long[] lineStarts(final byte[] bytes) {
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

    private static int[] lineStartChars(final String text) {
        int[] starts = new int[16];
        int n = 0;
        starts[n++] = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (n == starts.length) {
                    starts = Arrays.copyOf(starts, n * 2);
                }
                starts[n++] = i + 1;
            }
        }
        return Arrays.copyOf(starts, n);
    }

    /** The byte offset at which each character starts, plus the total at the end. */
    private static int[] byteOfChar(final String text) {
        final int[] table = new int[text.length() + 1];
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            table[i] = bytes;
            final char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)) {
                bytes += 4;
                table[++i] = bytes; // the low surrogate has no start of its own
            } else {
                bytes += 3;
            }
        }
        table[text.length()] = bytes;
        return table;
    }

    private static int lineIndex(final long[] lineStarts, final long offset) {
        int at = Arrays.binarySearch(lineStarts, offset);
        if (at < 0) {
            at = -at - 2;
        }
        return Math.max(0, Math.min(at, lineStarts.length - 1));
    }
}
