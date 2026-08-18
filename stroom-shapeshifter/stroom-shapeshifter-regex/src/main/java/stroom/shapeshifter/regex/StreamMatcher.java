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

package stroom.shapeshifter.regex;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * Finds successive matches in a stream, growing the window whenever the engine reports that it
 * cannot yet decide.
 * <p>
 * This is the loop that {@link MatchOutcome#NEED_MORE_INPUT} exists to drive, and it is where
 * the guarantees turn into behaviour: a record whose delimiter has not yet arrived is never
 * reported as a short match, because the engine says "not yet" and this asks for more instead
 * of accepting what it can see.
 *
 * <h2>Bounded memory</h2>
 * Bytes before the last match are dropped, so memory is governed by the longest single match
 * rather than by the length of the stream. A match that outgrows {@code maxWindow} is a failure
 * with a diagnostic — never a silent truncation, which is the failure mode this design exists
 * to remove.
 *
 * <h2>Line anchors</h2>
 * {@code ^} treats the window start as a line start, so the window is only compacted up to the
 * end of the previous match — which for record-oriented data is a record boundary.
 *
 * <pre>{@code
 * try (StreamMatcher matcher = new StreamMatcher(pattern, in, 8192, 1 << 20)) {
 *     while (matcher.findNext()) {
 *         String host = matcher.groupString(1);
 *     }
 * }
 * }</pre>
 * Not thread safe.
 */
public final class StreamMatcher implements AutoCloseable {

    private final ByteMatcher matcher;
    private final InputStream input;
    private final int maxWindow;

    private byte[] buffer;
    private int start;
    private int end;
    /** Absolute stream offset of {@code buffer[0]}; the window's own origin adds {@code start}. */
    private long bufferOrigin;
    private boolean exhausted;
    /** Absolute end of the previous match, so an empty match abutting it is not yielded twice. */
    private long previousEnd = -1;

    public StreamMatcher(final BytePattern pattern,
                         final InputStream input,
                         final int initialWindow,
                         final int maxWindow) {
        if (initialWindow <= 0 || maxWindow < initialWindow) {
            throw new IllegalArgumentException(
                    "initialWindow must be positive and maxWindow at least as large");
        }
        this.matcher = pattern.matcher();
        this.input = input;
        this.maxWindow = maxWindow;
        this.buffer = new byte[initialWindow];
    }

    /**
     * Advances to the next match.
     *
     * @return true if one was found; the group accessors are then valid until the next call.
     */
    public boolean findNext() {
        while (true) {
            final ByteWindow window =
                    new ByteWindow(buffer, start, end, bufferOrigin + start, exhausted);
            final MatchOutcome outcome = matcher.match(window, start, Anchoring.UNANCHORED);

            switch (outcome) {
                case MATCH -> {
                    final int matchEnd = matcher.end();
                    final boolean empty = matchEnd == matcher.start();
                    // Advance past this match, guarding against a zero-width one looping.
                    start = empty
                            ? Math.min(matchEnd + 1, end)
                            : matchEnd;
                    if (empty && bufferOrigin + matcher.start() == previousEnd) {
                        // An empty match immediately after a non-empty one reports the same
                        // position a second time; a match iterator yields it once.
                        continue;
                    }
                    previousEnd = bufferOrigin + matchEnd;
                    return true;
                }
                case NO_MATCH -> {
                    return false;
                }
                case NEED_MORE_INPUT -> {
                    if (!fill()) {
                        // EOF reached: the next attempt sees a complete window and decides.
                        if (end == start) {
                            return false;
                        }
                    }
                }
            }
        }
    }

    /** The absolute offset of the current match within the stream, not within the buffer. */
    public long matchStart() {
        return bufferOrigin + matcher.start();
    }

    /** The absolute offset one past the current match. */
    public long matchEnd() {
        return bufferOrigin + matcher.end();
    }

    public int start() {
        return matcher.start();
    }

    public int end() {
        return matcher.end();
    }

    public ByteSpan group(final int group) {
        return matcher.group(group);
    }

    public ByteSpan group(final String name) {
        return matcher.group(name);
    }

    public String groupString(final int group) {
        return matcher.groupString(group);
    }

    /**
     * Reads more bytes, compacting away what is already consumed and growing when it must.
     *
     * @return false if the stream is exhausted.
     */
    private boolean fill() {
        if (exhausted) {
            return false;
        }
        if (start > 0) {
            // Everything before the current search position is finished with.
            final int retained = end - start;
            System.arraycopy(buffer, start, buffer, 0, retained);
            bufferOrigin += start;
            start = 0;
            end = retained;
        }
        if (end == buffer.length) {
            if (buffer.length >= maxWindow) {
                throw new WindowOverflowException(
                        "no match completed within " + maxWindow + " bytes from stream offset "
                        + bufferOrigin + "; a pattern that can never complete would otherwise "
                        + "buffer without limit");
            }
            buffer = Arrays.copyOf(buffer, Math.min(maxWindow, Math.max(buffer.length * 2, 1)));
        }

        final int read;
        try {
            read = input.read(buffer, end, buffer.length - end);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        if (read < 0) {
            exhausted = true;
            return false;
        }
        end += read;
        return true;
    }

    @Override
    public void close() {
        try {
            input.close();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Thrown when a match would require buffering more than the caller permitted. */
    public static final class WindowOverflowException extends RuntimeException {

        public WindowOverflowException(final String message) {
            super(message);
        }
    }
}
