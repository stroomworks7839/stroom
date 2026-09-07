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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.text.Encoding;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * DS3's sliding window over the input (E13, design 23), and the two ways of reading the
 * whole of it.
 *
 * <p>The contract is DS3's and is deliberate: memory is bounded by the configured buffer
 * size, and a single match must fit the window's capacity or it cannot be made. What slides
 * is the window, not the contract — the unconsumed tail is kept, the window refills behind
 * it, and a record is never failed for merely straddling where a read happened to end.
 * Failures depend on record size, never on stream position. The refill is lazy where DS3's
 * is eager, because eager compaction would copy the whole window per match: consumption
 * advances an offset, and the window compacts and refills only when the level asks.
 *
 * <p>The window knows bytes, not templates. It says where the live region is, how much of
 * the input has been consumed so far in absolute terms, whether the input can still grow
 * the region, and — once, at the front — whether the input opened with a byte-order mark,
 * which is part of the input and counts toward every absolute offset.
 */
final class InputWindow {

    private final PushbackInputStream source;
    private final byte[] window;
    private int start;
    private int filled;
    private boolean eof;
    private long consumedTotal;
    private final Encoding.ByteOrderMark mark;

    private InputWindow(final InputStream input, final int capacity) {
        // One byte of pushback: "the window is full" and "the stream is exhausted" can
        // coincide, and a refusal must not fire on the first when only the second is true.
        this.source = new PushbackInputStream(input, 1);
        this.window = new byte[capacity];
        this.filled = fillAndBlankTail(source, window, 0);
        this.eof = filled < capacity;
        this.mark = byteOrderMark(window, filled);
        if (mark != null) {
            start = Math.min(mark.length(), filled);
            consumedTotal = start;
        }
    }

    /** Open a window of the given capacity over the input, filled once, its mark read. */
    static InputWindow open(final InputStream input, final int capacity) {
        return new InputWindow(input, capacity);
    }

    /** The window's capacity: the configuration's buffer size, which a record must fit. */
    int capacity() {
        return window.length;
    }

    /** The byte-order mark the input opened with, or null; its bytes are already consumed. */
    Encoding.ByteOrderMark mark() {
        return mark;
    }

    /** The mark at the front of {@code length} bytes of {@code data}, or null. */
    static Encoding.ByteOrderMark byteOrderMark(final byte[] data, final int length) {
        return Encoding.detectByteOrderMark(Arrays.copyOf(data, Math.min(length, 4)));
    }

    /**
     * The window's array. Valid from {@link #start()} to {@link #filled()}; blanked beyond the
     * fill, which the matcher's one-byte look-ahead relies on; shifted by {@link #refill}, after
     * which {@code start()} and {@code filled()} must be read again.
     */
    byte[] bytes() {
        return window;
    }

    /** Where the live region begins. */
    int start() {
        return start;
    }

    /** Where the live region ends: the fill level. */
    int filled() {
        return filled;
    }

    /** Whether the live region is empty. */
    boolean isEmpty() {
        return start >= filled;
    }

    /** How many bytes of the input precede the live region, the mark included. */
    long consumed() {
        return consumedTotal;
    }

    /** The absolute input offset of a position in the window. */
    long offsetOf(final int position) {
        return consumedTotal + (position - start);
    }

    /** Consume {@code length} bytes from the front of the live region. */
    void consume(final int length) {
        start += length;
        consumedTotal += length;
    }

    /**
     * Whether a refill could bring more: the input has not ended and there is room, either
     * consumed bytes to compact away or capacity never filled.
     */
    boolean canGrow() {
        return !eof && (start > 0 || filled < window.length);
    }

    /**
     * Whether a match ending at {@code end} ran into the window's edge with input possibly
     * unread — it may have matched a truncated view — where a refill is the answer rather
     * than a refusal. A match that fills a whole window from its start is the other case:
     * nothing could be compacted away to make room, so it is judged, not refilled.
     */
    boolean edgeCanRecede(final int end) {
        return end == filled && !eof && !(start == 0 && filled == window.length);
    }

    /**
     * Whether a match ending at {@code end} reached the window's edge with the end of input not
     * yet seen. Asked after {@link #edgeCanRecede} has said no, so a yes is the full-window case:
     * nothing can be compacted away, and the level decides with a probe.
     */
    boolean reachesEdge(final int end) {
        return end == filled && !eof;
    }

    /**
     * Compact the live region to the front and read behind it.
     *
     * @return whether anything new arrived
     */
    boolean refill() {
        filled = compact(window, start, filled);
        start = 0;
        final int before = filled;
        filled += fillAndBlankTail(source, window, filled);
        eof = filled < window.length;
        return filled > before;
    }

    /**
     * Whether the input is exhausted, learned by one probe byte — pushed back if it exists.
     * May block on a live source, which is why the level reserves it for the one decision
     * that needs the certainty: a match reaching the edge of a full window is either the
     * record ending where the input did, or a record larger than the window.
     */
    boolean probeExhausted() {
        try {
            final int probe = source.read();
            if (probe < 0) {
                eof = true;
                return true;
            }
            source.unread(probe);
            return false;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Shift the live region to the window's front, returning the new fill level. */
    private static int compact(final byte[] window, final int start, final int filled) {
        System.arraycopy(window, start, window, 0, filled - start);
        return filled - start;
    }

    /**
     * Read into the window and blank whatever the previous buffer left beyond the new fill.
     *
     * <p>The matcher's contract is that the array holds the caller's data up to its length: it
     * probes one byte past the region to decide whether the region ends mid-character, which
     * is right for a slice of a full array and wrong for a reused window, where those bytes
     * are the last buffer's. Left stale, a continuation byte sitting at the fill point tells
     * the matcher a character continues past the region and a legal empty match at the tail
     * is refused — silently, and depending on what an earlier buffer happened to contain.
     * Blanking the tail makes the contract true. It costs a memset of whatever the read left
     * short, which is nothing until the last buffer of a stream, since {@link #fill} loops
     * until the window is full.
     */
    static int fillAndBlankTail(final InputStream input, final byte[] window, final int from) {
        final int got = fill(input, window, from);
        Arrays.fill(window, from + got, window.length, (byte) 0);
        return got;
    }

    /** Read until the window is full or the input ends; returns how many bytes arrived. */
    private static int fill(final InputStream input, final byte[] window, final int from) {
        try {
            int total = from;
            while (total < window.length) {
                final int got = input.read(window, total, window.length - total);
                if (got < 0) {
                    break;
                }
                total += got;
            }
            return total - from;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Read one buffer of up to {@code size} bytes, or the whole input for
     * {@link Integer#MAX_VALUE}; null at the end of the input. The other way of reading: the
     * whole-buffer and chunk-at-a-time roots address their input in pieces that never slide.
     */
    static byte[] read(final InputStream input, final int size) {
        try {
            if (size == Integer.MAX_VALUE) {
                final byte[] all = input.readAllBytes();
                return all.length == 0 ? null : all;
            }
            final byte[] buffer = new byte[size];
            int total = 0;
            while (total < size) {
                final int read = input.read(buffer, total, size - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            return total == 0 ? null : (total == size ? buffer : Arrays.copyOf(buffer, total));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
