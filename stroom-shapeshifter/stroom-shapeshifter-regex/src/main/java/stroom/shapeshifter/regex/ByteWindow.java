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

import java.nio.charset.StandardCharsets;

/**
 * A view of the bytes currently available to match against.
 *
 * @param array        the backing buffer.
 * @param start        first valid offset.
 * @param end          one past the last offset of the match region.
 * @param contextEnd   one past the last byte of the array that may be consulted at all — as
 *                     surrounding context for zero-width assertions and character-boundary
 *                     checks, never as match material. A bounded search over a fully-held
 *                     haystack sets it to the array's length; a streaming buffer sets it to
 *                     {@code end}, because the bytes past its fill point are stale.
 * @param originOffset absolute offset of {@code start} within the whole stream. Survives
 *                     compaction, so spans and error locations can be reported against the
 *                     source rather than against a buffer that keeps moving.
 * @param complete     true when no further bytes will ever follow {@code end}. This is what
 *                     lets the engine answer definitively at the edge instead of asking for
 *                     more, and getting it wrong in either direction is a correctness bug: a
 *                     window falsely marked complete truncates matches, and one falsely marked
 *                     incomplete never terminates.
 */
public record ByteWindow(byte[] array,
                         int start,
                         int end,
                         int contextEnd,
                         long originOffset,
                         boolean complete) {

    public ByteWindow {
        if (start < 0 || end < start || end > array.length) {
            throw new IndexOutOfBoundsException(
                    "window [" + start + ", " + end + ") outside array of length " + array.length);
        }
        if (contextEnd < end || contextEnd > array.length) {
            throw new IndexOutOfBoundsException(
                    "contextEnd " + contextEnd + " outside [" + end + ", " + array.length + "]");
        }
    }

    /** A window over an entire array, with nothing more to come. */
    public static ByteWindow complete(final byte[] array) {
        return new ByteWindow(array, 0, array.length, array.length, 0, true);
    }

    /** A window over part of an array, with the rest of the array as context. */
    public static ByteWindow complete(final byte[] array, final int start, final int end) {
        return new ByteWindow(array, start, end, array.length, start, true);
    }

    /** A window that may yet be extended — the engine will report {@code NEED_MORE_INPUT}. */
    public static ByteWindow partial(final byte[] array, final int start, final int end) {
        return new ByteWindow(array, start, end, end, start, false);
    }

    /** A complete window over the UTF-8 encoding of {@code text} — a test and demo convenience. */
    public static ByteWindow of(final String text) {
        return complete(text.getBytes(StandardCharsets.UTF_8));
    }

    public int length() {
        return end - start;
    }

    public boolean isEmpty() {
        return end == start;
    }

    /** The absolute stream offset of a position within this window. */
    public long absolute(final int offset) {
        return originOffset + (offset - start);
    }
}
