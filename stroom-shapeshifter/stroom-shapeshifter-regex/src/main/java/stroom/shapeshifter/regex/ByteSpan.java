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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A region of a caller-owned byte array — a matched group, without copying it.
 * <p>
 * The span is only valid while the underlying array holds the data it was matched against.
 * Call {@link #toBytes()} to take a copy that outlives it.
 * <p>
 * Equality is the record default, which compares the {@code data} component by array
 * <em>identity</em>: two spans over identical bytes in different arrays are not equal, and
 * {@code hashCode} does not read the bytes either. Do not use spans as value keys — compare
 * {@link #toBytes()} or the decoded string instead.
 */
public record ByteSpan(byte[] data, int start, int end) {

    public ByteSpan {
        if (start < 0 || end < start || end > data.length) {
            throw new IndexOutOfBoundsException("span [" + start + ", " + end + ")");
        }
    }

    public int length() {
        return end - start;
    }

    public boolean isEmpty() {
        return end == start;
    }

    public byte[] toBytes() {
        return Arrays.copyOfRange(data, start, end);
    }

    public String toString(final Charset charset) {
        return new String(data, start, length(), charset);
    }

    @Override
    public String toString() {
        return toString(StandardCharsets.UTF_8);
    }
}
