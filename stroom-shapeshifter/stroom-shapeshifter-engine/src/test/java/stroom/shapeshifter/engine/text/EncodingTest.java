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

package stroom.shapeshifter.engine.text;

import stroom.shapeshifter.engine.text.Encoding.ByteOrderMark;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Encodings, ported from the Rust crate's own suite.
 *
 * <p>No fixture reaches any of this — every configuration in the corpus is UTF-8 or {@code auto}
 * — so these tests are the whole of the evidence that the encoding layer works. They are the
 * Rust tests' assertions, kept as they were rather than rewritten, because the point is to show
 * the two engines agree.
 *
 * <p>The interesting cases are the ones where the JDK could plausibly differ: Windows-1252's
 * range where Latin-1 has control characters, Shift JIS being multi-byte, and what happens to a
 * character an encoding cannot express.
 */
class EncodingTest {

    // -----------------------------------------------------------------------------------
    // Byte-order marks
    // -----------------------------------------------------------------------------------

    @Test
    void detectsByteOrderMarks() {
        assertThat(Encoding.detectByteOrderMark(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h'}))
                .isEqualTo(new ByteOrderMark(Encoding.UTF_8, 3));
        assertThat(Encoding.detectByteOrderMark(new byte[]{(byte) 0xFF, (byte) 0xFE, 'h', 0}))
                .isEqualTo(new ByteOrderMark(Encoding.UTF_16LE, 2));
        assertThat(Encoding.detectByteOrderMark(new byte[]{(byte) 0xFE, (byte) 0xFF, 0, 'h'}))
                .isEqualTo(new ByteOrderMark(Encoding.UTF_16BE, 2));
        assertThat(Encoding.detectByteOrderMark("hello".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(Encoding.detectByteOrderMark(new byte[0])).isNull();
    }

    // -----------------------------------------------------------------------------------
    // Decoding
    // -----------------------------------------------------------------------------------

    @Test
    void decodesUtf8() {
        assertThat(Encoding.UTF_8.decode("héllo wörld".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("héllo wörld");
    }

    @Test
    void decodesLatin1AsCodePoints() {
        // Every byte is the code point of the same number: 0xE9 is é, 0xF6 is ö.
        final byte[] input = {'h', (byte) 0xE9, 'l', 'l', 'o', ' ', 'w', (byte) 0xF6, 'r', 'l', 'd'};
        assertThat(Encoding.LATIN_1.decode(input)).isEqualTo("héllo wörld");
    }

    @Test
    void decodesWindows1252WhereLatin1HasControls() {
        // The whole reason the two are different encodings: 0x93 and 0x94 are curly quotes here
        // and control characters in Latin-1.
        assertThat(Encoding.WINDOWS_1252.decode(new byte[]{(byte) 0x93, 'h', 'i', (byte) 0x94}))
                .isEqualTo("“hi”");
        assertThat(Encoding.WINDOWS_1252.decode(new byte[]{(byte) 0x80})).isEqualTo("€");
    }

    @Test
    void decodesAMultiByteEncoding() {
        // Shift JIS: two bytes per character.
        assertThat(Encoding.SHIFT_JIS.decode(new byte[]{(byte) 0x82, (byte) 0xB1, (byte) 0x82, (byte) 0xF1}))
                .isEqualTo("こん");
    }

    @Test
    void decodesRawWithoutInterpreting() {
        // Nothing is lost and nothing is replaced, which is what makes RAW usable for binary.
        assertThat(Encoding.RAW.decode(new byte[]{0x68, 0x69, (byte) 0xFF})).isEqualTo("hiÿ");
    }

    @Test
    void decodesAsciiReplacingWhatIsNotAscii() {
        assertThat(Encoding.ASCII.decode("hello".getBytes(StandardCharsets.US_ASCII))).isEqualTo("hello");
        assertThat(Encoding.ASCII.decode(new byte[]{'h', (byte) 0xE9})).isEqualTo("h�");
    }

    @Test
    void decodesPartOfAnArray() {
        final byte[] input = "xxhello".getBytes(StandardCharsets.UTF_8);
        assertThat(Encoding.UTF_8.decode(input, 2, 5)).isEqualTo("hello");
    }

    // -----------------------------------------------------------------------------------
    // Encoding
    // -----------------------------------------------------------------------------------

    @Test
    void encodesBackToBytes() {
        assertThat(Encoding.UTF_8.encode("hello café"))
                .isEqualTo("hello café".getBytes(StandardCharsets.UTF_8));
        assertThat(Encoding.LATIN_1.encode("café"))
                .isEqualTo(new byte[]{'c', 'a', 'f', (byte) 0xE9});
        assertThat(Encoding.WINDOWS_1252.encode("“hi”"))
                .isEqualTo(new byte[]{(byte) 0x93, 'h', 'i', (byte) 0x94});
    }

    @Test
    void encodesWhatItCannotExpressAsAQuestionMark() {
        // Losing the character is bad; producing invalid bytes for the declared encoding is
        // worse, because everything downstream would then be reading a lie.
        assertThat(Encoding.ASCII.encode("café")).isEqualTo("caf?".getBytes(StandardCharsets.US_ASCII));
        assertThat(Encoding.LATIN_1.encode("euro €")).isEqualTo("euro ?".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void roundTripsThroughEveryAvailableEncoding() {
        for (final Encoding encoding : Encoding.values()) {
            if (encoding == Encoding.AUTO || !encoding.isAvailable()) {
                continue;
            }
            // Plain ASCII is expressible everywhere, so anything that cannot carry it round is
            // wired up wrongly rather than merely limited.
            assertThat(encoding.decode(encoding.encode("abc123")))
                    .as("%s", encoding.label())
                    .isEqualTo("abc123");
        }
    }

    // -----------------------------------------------------------------------------------
    // Properties
    // -----------------------------------------------------------------------------------

    @Test
    void knowsWhichEncodingsAreOneByteOneCharacter() {
        assertThat(Encoding.LATIN_1.isSingleByte()).isTrue();
        assertThat(Encoding.WINDOWS_1252.isSingleByte()).isTrue();
        assertThat(Encoding.ASCII.isSingleByte()).isTrue();
        assertThat(Encoding.KOI8_R.isSingleByte()).isTrue();
        // A code unit of one byte is not the same as one byte per character.
        assertThat(Encoding.UTF_8.isSingleByte()).isFalse();
        assertThat(Encoding.UTF_16LE.isSingleByte()).isFalse();
        assertThat(Encoding.SHIFT_JIS.isSingleByte()).isFalse();
    }

    @Test
    void knowsWhichEncodingsNeedNoConversion() {
        assertThat(Encoding.UTF_8.isUtf8Compatible()).isTrue();
        assertThat(Encoding.ASCII.isUtf8Compatible()).isTrue();
        assertThat(Encoding.AUTO.isUtf8Compatible()).isTrue();
        assertThat(Encoding.LATIN_1.isUtf8Compatible()).isFalse();
        assertThat(Encoding.UTF_16LE.isUtf8Compatible()).isFalse();
    }

    // -----------------------------------------------------------------------------------
    // Naming
    // -----------------------------------------------------------------------------------

    @Test
    void findsEncodingsByTheNamesPeopleWrite() {
        assertThat(Encoding.fromLabel("UTF-8")).isEqualTo(Encoding.UTF_8);
        assertThat(Encoding.fromLabel("utf8")).isEqualTo(Encoding.UTF_8);
        assertThat(Encoding.fromLabel("windows-1252")).isEqualTo(Encoding.WINDOWS_1252);
        assertThat(Encoding.fromLabel("CP1252")).isEqualTo(Encoding.WINDOWS_1252);
        assertThat(Encoding.fromLabel("Shift_JIS")).isEqualTo(Encoding.SHIFT_JIS);
        assertThat(Encoding.fromLabel("sjis")).isEqualTo(Encoding.SHIFT_JIS);
        assertThat(Encoding.fromLabel("latin1")).isEqualTo(Encoding.LATIN_1);
        assertThat(Encoding.fromLabel("iso-8859-1")).isEqualTo(Encoding.LATIN_1);
        assertThat(Encoding.fromLabel("auto")).isEqualTo(Encoding.AUTO);
        assertThat(Encoding.fromLabel("unknown")).isNull();
        assertThat(Encoding.fromLabel(null)).isNull();
    }

    @Test
    void everyEncodingIsFindableByItsOwnName() {
        for (final Encoding encoding : Encoding.values()) {
            assertThat(Encoding.fromLabel(encoding.label()))
                    .as("%s should be findable by its own label", encoding)
                    .isEqualTo(encoding);
        }
    }

    @Test
    void carriesTheEncodingsTheRustEngineDoes() {
        final List<Encoding> named = Arrays.stream(Encoding.values())
                .filter(encoding -> encoding != Encoding.RAW && encoding != Encoding.AUTO)
                .toList();
        assertThat(named).hasSizeGreaterThanOrEqualTo(29);

        // A named encoding this build has no charset for would fail silently at run time, so it
        // is worth knowing now which ones those are.
        final List<Encoding> unavailable = named.stream()
                .filter(encoding -> !encoding.isAvailable())
                .toList();
        assertThat(unavailable).as("encodings with no charset in this build").isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // Inheritance
    // -----------------------------------------------------------------------------------

    @Test
    void inheritsUnlessSomethingSaysOtherwise() {
        assertThat(Encoding.resolve(null, Encoding.LATIN_1)).isEqualTo(Encoding.LATIN_1);
        assertThat(Encoding.resolve(Encoding.WINDOWS_1252, Encoding.UTF_8)).isEqualTo(Encoding.WINDOWS_1252);
        // "auto" further in means "whatever was decided out there", not "detect again".
        assertThat(Encoding.resolve(Encoding.AUTO, Encoding.LATIN_1)).isEqualTo(Encoding.LATIN_1);
    }
}
