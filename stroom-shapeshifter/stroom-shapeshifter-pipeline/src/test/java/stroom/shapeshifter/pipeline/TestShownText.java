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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

// How the preview shows the bytes the engine counted (design 46 §6.5)
class TestShownText {

    @Test
    void anInputIsShownInTheEncodingItsProjectDeclares() {
        // UTF-16: two bytes to a character, so offset 4 is character 2
        final byte[] utf16 = "ab".getBytes(StandardCharsets.UTF_16LE);
        final ShownText sixteen = ShownText.input(utf16, "utf-16le");
        assertThat(sixteen.text(utf16)).isEqualTo("ab");
        assertThat(sixteen.index(utf16)).containsExactly(0, 0, 1, 1, 2);
        // A single-byte encoding, a byte to a character
        final byte[] latin = {'c', (byte) 0xE9};
        assertThat(ShownText.input(latin, "iso-8859-1").text(latin)).isEqualTo("cé");
        // auto reads the byte order mark, as the engine does
        final byte[] marked = {(byte) 0xFF, (byte) 0xFE, 'a', 0};
        assertThat(ShownText.input(marked, "auto").charset()).isEqualTo(StandardCharsets.UTF_16LE);
    }

    @Test
    void anInputThatIsNoTextIsShownAByteToACharacter() {
        final byte[] binary = {5, 'A', (byte) 0x80, (byte) 0xFF};
        assertThat(ShownText.input(binary, "utf-8")).isEqualTo(ShownText.BYTES);
        assertThat(ShownText.input(binary, "raw")).isEqualTo(ShownText.BYTES);
        // UTF-16 declared over an odd number of bytes is no UTF-16
        assertThat(ShownText.input(new byte[]{'a', 0, 'b'}, "utf-16le")).isEqualTo(ShownText.UTF_8);
        // and text is UTF-8 where nothing else is said
        assertThat(ShownText.input("é".getBytes(StandardCharsets.UTF_8), null)).isEqualTo(ShownText.UTF_8);
    }

    @Test
    void utf8IsCheckedAsTheStandardSaysWithoutDecoding() {
        assertThat(ShownText.isUtf8("aé€😀".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(ShownText.isUtf8(new byte[0])).isTrue();
        // A continuation alone, an overlong form, a surrogate, past U+10FFFF, a sequence cut short
        assertThat(ShownText.isUtf8(new byte[]{(byte) 0x80})).isFalse();
        assertThat(ShownText.isUtf8(new byte[]{(byte) 0xC0, (byte) 0x80})).isFalse();
        assertThat(ShownText.isUtf8(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80})).isFalse();
        assertThat(ShownText.isUtf8(new byte[]{(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80})).isFalse();
        assertThat(ShownText.isUtf8(new byte[]{'a', (byte) 0xC3})).isFalse();
    }

    @Test
    void aSampleIsEncodedInTheCharsetItWasShownIn() {
        assertThat(ShownText.ofSample("ISO-8859-1", "\u0080ÿ")).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(ShownText.ofSample(null, "x")).isEqualTo(StandardCharsets.UTF_8);
        // A name that is no charset, or a text it cannot hold, is UTF-8
        assertThat(ShownText.ofSample("no-such-charset", "x")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(ShownText.ofSample("ISO-8859-1", "€")).isEqualTo(StandardCharsets.UTF_8);
    }
}
