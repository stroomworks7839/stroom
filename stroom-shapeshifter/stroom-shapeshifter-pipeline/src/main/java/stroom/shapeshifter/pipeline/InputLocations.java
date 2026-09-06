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
import java.util.Arrays;
import java.util.Deque;
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
    /** The live path's line index, told what it may forget; null on the byte path. */
    private LineIndex lines;

    /** How many matches are open right now: nesting depth, never the stream's length. */
    int openMatches() {
        return open.size();
    }

    /** Bind the live line index so the trace can bound its memory as matches close. */
    void bound(final LineIndex lines) {
        this.lines = lines;
    }

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
        // The match closes whatever the sink's currency: the stack must fall back to the
        // enclosing match, and must not grow with the stream (design 23 phase 1 audit). Nothing
        // is kept of the output's position: every event is located live, as it is made.
        long closed = -1;
        while (!open.isEmpty()) {
            final Open match = open.pop();
            if (match.templateId.equals(templateId) && match.matchIndex == matchIndex) {
                closed = match.inputOffset;
                break;
            }
        }
        if (lines != null && closed >= 0) {
            // Nothing still to come can refer to a line before the outermost open match, or, with
            // nothing open, before the match that just closed — every later match begins at or
            // after it. Never the read position: the window is read ahead of the matches.
            final Open outermost = open.peekLast();
            lines.forget(outermost == null ? closed : outermost.inputOffset);
        }
    }

    // -----------------------------------------------------------------------------------
    // Locating, live
    // -----------------------------------------------------------------------------------

    /** Where the innermost running match began in the input, or {@link #UNLOCATABLE} — live. */
    long currentInputOffset() {
        final Open innermost = open.peek();
        return innermost == null ? Instrument.UNLOCATABLE : innermost.inputOffset;
    }

    /** A line and column, one-based; {@code -1} when there is none. */
    record Position(int line, int column) {

        static final Position NONE = new Position(-1, -1);
    }

    /** Lines of an input, as it is being read. */
    interface Lines {

        Position locate(long offset);
    }

    /** An input stream that records where its lines start as they are read. */
    static final class LineIndex extends java.io.FilterInputStream implements Lines {

        private long[] starts = new long[16];
        private int count = 1;
        /** The line number (zero-based) of {@code starts[0]}: what has been forgotten is counted, not kept. */
        private long base;
        private long position;

        LineIndex(final java.io.InputStream in) {
            super(in);
        }


        /** How many line starts are held right now — the bound the contract is about. */
        synchronized int held() {
            return count;
        }

        /**
         * Forget every line start before the line containing {@code floor}. The window is the
         * memory (design 23 §1); an index that remembered every line of a terabyte would not be.
         * Line numbers stay right because what is dropped is counted in {@link #base}.
         */
        synchronized void forget(final long floor) {
            int keepFrom = 0;
            while (keepFrom + 1 < count && starts[keepFrom + 1] <= floor) {
                keepFrom++;
            }
            if (keepFrom > 0) {
                System.arraycopy(starts, keepFrom, starts, 0, count - keepFrom);
                count -= keepFrom;
                base += keepFrom;
            }
        }

        /** Locate without copying: the read is always ahead of any match, so the line is known. */
        @Override
        public synchronized Position locate(final long offset) {
            if (offset >= Instrument.UNLOCATABLE) {
                return Position.NONE;
            }
            int at = Arrays.binarySearch(starts, 0, count, offset);
            if (at < 0) {
                at = -at - 2;
            }
            at = Math.max(0, Math.min(at, count - 1));
            return new Position((int) (base + at + 1), (int) (offset - starts[at]) + 1);
        }

        @Override
        public synchronized int read() throws java.io.IOException {
            final int b = super.read();
            if (b >= 0) {
                note((byte) b);
                position++;
            }
            return b;
        }

        @Override
        public synchronized int read(final byte[] into, final int offset, final int length) throws java.io.IOException {
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
}
