/*
 * Copyright 2026 Crown Copyright
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

import stroom.shapeshifter.engine.text.Encoding;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/// How the preview shows the client bytes the engine counted (design 46 §6.5): the charset the
/// bytes are shown in, and for every byte offset the character offset it falls at, so that what
/// the engine counted is what the client points at.
///
/// The input is shown in the encoding the project declares for it, where that is one whose
/// characters can be counted from the bytes alone — UTF-8, a single-byte encoding, UTF-16 — and
/// the bytes are well formed in it. Anything else is shown as UTF-8 where the bytes are UTF-8,
/// and otherwise a byte to a character: a binary record, which is no text in any encoding.
///
/// @param charset The charset the text is shown in.
/// @param width The bytes to a character, or 0 for UTF-8's own varying width.
record ShownText(Charset charset, int width) {

    /// As UTF-8.
    static final ShownText UTF_8 = new ShownText(StandardCharsets.UTF_8, 0);
    /// A byte to a character.
    static final ShownText BYTES = new ShownText(StandardCharsets.ISO_8859_1, 1);

    /// How an input is shown.
    ///
    /// @param bytes The input.
    /// @param declared The encoding the project declares for its source, or null.
    /// @return How it is shown.
    static ShownText input(final byte[] bytes, final String declared) {
        Encoding encoding = Encoding.fromLabel(declared);
        if (encoding == null || encoding == Encoding.AUTO) {
            final Encoding.ByteOrderMark bom = Encoding.detectByteOrderMark(bytes);
            encoding = bom == null
                    ? Encoding.UTF_8
                    : bom.encoding();
        }
        if ((encoding == Encoding.UTF_16LE || encoding == Encoding.UTF_16BE) && bytes.length % 2 == 0
            && decodes(bytes, encoding.charset())) {
            return new ShownText(encoding.charset(), 2);
        }
        if (encoding.isAvailable() && encoding.isSingleByte() && encoding != Encoding.ASCII
            && encoding != Encoding.RAW) {
            return new ShownText(encoding.charset(), 1);
        }
        return output(bytes);
    }

    /// How output, or an input in no countable encoding, is shown: UTF-8 where it is UTF-8, and
    /// otherwise a byte to a character.
    ///
    /// @param bytes The bytes.
    /// @return How they are shown.
    static ShownText output(final byte[] bytes) {
        return isUtf8(bytes)
                ? UTF_8
                : BYTES;
    }

    /// The charset a pasted sample is encoded in to give back the bytes it was shown from.
    ///
    /// @param name The charset's name, as the trace gave it, or null for UTF-8.
    /// @param text The text.
    /// @return The charset, or UTF-8 where the name is no charset that can encode the text.
    static Charset ofSample(final String name, final String text) {
        if (name == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            final Charset charset = Charset.forName(name);
            return charset.newEncoder().canEncode(text)
                    ? charset
                    : StandardCharsets.UTF_8;
        } catch (final IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /// @param bytes The bytes.
    /// @return The text shown.
    String text(final byte[] bytes) {
        return new String(bytes, charset);
    }

    /// For each byte offset 0..n, the character offset it falls at.
    ///
    /// @param bytes The bytes.
    /// @return The index, one longer than the bytes.
    int[] index(final byte[] bytes) {
        final int[] index = new int[bytes.length + 1];
        if (width > 0) {
            for (int i = 0; i <= bytes.length; i++) {
                index[i] = i / width;
            }
            return index;
        }
        int chars = 0;
        for (int i = 0; i < bytes.length; i++) {
            index[i] = chars;
            final int b = bytes[i] & 0xff;
            // A continuation byte (10xxxxxx) does not start a character; everything else does,
            // and a UTF-16 surrogate pair - a 4-byte sequence - counts as two characters.
            if ((b & 0xC0) != 0x80) {
                chars += b >= 0xF0
                        ? 2
                        : 1;
            }
        }
        index[bytes.length] = chars;
        return index;
    }

    /// Whether bytes are well-formed UTF-8, read once, without decoding them.
    ///
    /// @param bytes The bytes.
    /// @return Whether they are.
    static boolean isUtf8(final byte[] bytes) {
        int i = 0;
        while (i < bytes.length) {
            final int b = bytes[i] & 0xff;
            if (b < 0x80) {
                i++;
                continue;
            }
            final int more;
            if (b >= 0xC2 && b <= 0xDF) {
                more = 1;
            } else if (b >= 0xE0 && b <= 0xEF) {
                more = 2;
            } else if (b >= 0xF0 && b <= 0xF4) {
                more = 3;
            } else {
                return false;
            }
            if (i + more >= bytes.length) {
                // A sequence the bytes end inside
                return false;
            }
            final int second = bytes[i + 1] & 0xff;
            // Overlong forms, the surrogates, and past U+10FFFF are not UTF-8
            if (b == 0xE0 && second < 0xA0 || b == 0xED && second > 0x9F
                || b == 0xF0 && second < 0x90 || b == 0xF4 && second > 0x8F) {
                return false;
            }
            for (int k = 1; k <= more; k++) {
                if ((bytes[i + k] & 0xC0) != 0x80) {
                    return false;
                }
            }
            i += more + 1;
        }
        return true;
    }

    private static boolean decodes(final byte[] bytes, final Charset charset) {
        try {
            charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (final CharacterCodingException e) {
            return false;
        }
    }
}
