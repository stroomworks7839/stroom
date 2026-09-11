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

package stroom.shapeshifter.engine.match;

import stroom.shapeshifter.engine.text.Encoding;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escaped delimiters in a split field, and the array that records where they were.
 *
 * <p>The positions were a {@code List<Integer>} — a boxed offset per escape, on the path that
 * splits every field of every delimited row — and are now an {@code int[]} that doubles. **The
 * growth had no coverage**: the golden fixtures use escapes, so a count that fails to advance is
 * caught, but none has a field with more than four in it, so breaking the doubling passed the
 * whole suite (design 33 §11's sweep).
 */
class SplitterEscapesTest {

    private static String unescaped(final String field) {
        final byte[] data = field.getBytes(StandardCharsets.UTF_8);
        final MatchResult result = Splitter.split(data, 0, data.length,
                ",".getBytes(StandardCharsets.UTF_8), "\\".getBytes(StandardCharsets.UTF_8),
                null, null, Encoding.UTF_8);
        // Group 2 is the content with the escapes stripped.
        return result.group(2).asString();
    }

    @Test
    void oneEscapedDelimiterIsKeptAsContent() {
        assertThat(unescaped("a\\,b,rest")).isEqualTo("a,b");
    }

    /** Five escapes in one field: one more than the array starts with, so it has to grow. */
    @Test
    void moreEscapesThanTheArrayStartsWithAreAllStripped() {
        assertThat(unescaped("a\\,b\\,c\\,d\\,e\\,f,rest")).isEqualTo("a,b,c,d,e,f");
    }

    /** Far more than one doubling can hold, so it grows repeatedly. */
    @Test
    void manyEscapesInOneFieldAreAllStripped() {
        final StringBuilder field = new StringBuilder("x");
        final StringBuilder expected = new StringBuilder("x");
        for (int i = 0; i < 40; i++) {
            field.append("\\,").append(i);
            expected.append(',').append(i);
        }
        field.append(",rest");
        assertThat(unescaped(field.toString())).isEqualTo(expected.toString());
    }
}
