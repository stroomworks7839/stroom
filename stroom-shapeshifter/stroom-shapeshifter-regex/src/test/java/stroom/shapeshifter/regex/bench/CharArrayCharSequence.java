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

package stroom.shapeshifter.regex.bench;

/**
 * A {@link CharSequence} over a {@code char[]} window, standing in for the buffer that
 * Stroom's existing DS3 parser hands to {@link java.util.regex.Matcher}.
 * <p>
 * DS3's {@code stroom.pipeline.xml.converter.ds3.Buffer} is a {@code CharSequence} backed by
 * a char array, so every character the regex engine inspects costs an interface-dispatched
 * {@code charAt} rather than a direct array read into a {@code String}'s private storage.
 * This class exists to measure that difference; it is deliberately minimal rather than a
 * faithful copy of DS3's buffer.
 */
final class CharArrayCharSequence implements CharSequence {

    private final char[] chars;
    private final int offset;
    private final int length;

    CharArrayCharSequence(final char[] chars) {
        this(chars, 0, chars.length);
    }

    private CharArrayCharSequence(final char[] chars, final int offset, final int length) {
        this.chars = chars;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public char charAt(final int index) {
        return chars[offset + index];
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
        return new CharArrayCharSequence(chars, offset + start, end - start);
    }

    @Override
    public String toString() {
        return new String(chars, offset, length);
    }
}
