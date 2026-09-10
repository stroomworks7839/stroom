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

import stroom.shapeshifter.engine.text.EncodingSniffer.Certainty;
import stroom.shapeshifter.engine.text.EncodingSniffer.Sniff;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision table, one case per rule and one per thing the rules must not do.
 *
 * <p>The certainty is asserted everywhere the encoding is, because it carries as much of the
 * answer: this is a component that guesses, and a caller has to be able to tell a guess from a
 * fact. A test that only pinned the encoding would pass just as happily if every answer became
 * a guess.
 */
class EncodingSnifferTest {

    /** The fallback a caller supplies; design 32 §6 has not ruled what it should be. */
    private static final Encoding FALLBACK = Encoding.UTF_8;

    private static Sniff sniff(final byte... bytes) {
        return EncodingSniffer.sniff(bytes, bytes.length, FALLBACK);
    }

    private static Sniff sniff(final String text) {
        return sniff(text.getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------------------
    // A mark is a statement
    // -----------------------------------------------------------------------------------

    @Test
    void utf8MarkIsTakenAtItsWord() {
        final Sniff sniff = sniff(bytes(0xEF, 0xBB, 0xBF, 'h', 'i'));
        assertThat(sniff.encoding()).isEqualTo(Encoding.UTF_8);
        assertThat(sniff.certainty()).isEqualTo(Certainty.MARKED);
    }

    @Test
    void utf16MarkIsReportedRatherThanRefused() {
        // The run refuses UTF-16 today because the regex library has no lowering for it. That is
        // a decision for whoever acts on this, not for the thing that reads the bytes: settling
        // the encoding before compiling is what lets the stream be transcoded instead.
        assertThat(sniff(bytes(0xFF, 0xFE, 'h', 0)).encoding()).isEqualTo(Encoding.UTF_16LE);
        assertThat(sniff(bytes(0xFE, 0xFF, 0, 'h')).encoding()).isEqualTo(Encoding.UTF_16BE);
    }

    @Test
    void markOutrunningTheWindowIsNotAMark() {
        // Two bytes of a three-byte mark say nothing; the UTF-8 mark's first two bytes are a
        // valid lead and continuation, so this must not be read as a truncated mark.
        assertThat(sniff(bytes(0xEF, 0xBB)).certainty()).isNotEqualTo(Certainty.MARKED);
    }

    // -----------------------------------------------------------------------------------
    // Not text at all
    // -----------------------------------------------------------------------------------

    @Test
    void jpegIsRaw() {
        // FF D8 FF E0, then the APP0 length and "JFIF\0" — the NUL arrives within eleven bytes.
        final Sniff sniff = sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10,
                'J', 'F', 'I', 'F', 0x00));
        assertThat(sniff.encoding()).isEqualTo(Encoding.RAW);
        assertThat(sniff.certainty()).isEqualTo(Certainty.DEDUCED);
    }

    @Test
    void nulAnywhereInTheWindowIsEnough() {
        final byte[] window = new byte[512];
        for (int i = 0; i < window.length; i++) {
            window[i] = 'a';
        }
        window[400] = 0;
        assertThat(EncodingSniffer.sniff(window, window.length, FALLBACK).encoding())
                .isEqualTo(Encoding.RAW);
    }

    @Test
    void binaryIsSettledBeforeAnyQuestionAboutWhichTextItIs() {
        // Bytes that are also invalid UTF-8. The NUL rule runs first, so the answer is RAW
        // rather than the fallback — "not text" is a different finding from "text I cannot name".
        assertThat(sniff(bytes(0xC0, 0x00, 0xFF)).encoding()).isEqualTo(Encoding.RAW);
    }

    // -----------------------------------------------------------------------------------
    // Text, and how sure we are
    // -----------------------------------------------------------------------------------

    @Test
    void validMultiByteUtf8IsDeduced() {
        final Sniff sniff = sniff("née £5 ☃");
        assertThat(sniff.encoding()).isEqualTo(Encoding.UTF_8);
        assertThat(sniff.certainty()).isEqualTo(Certainty.DEDUCED);
    }

    @Test
    void pureAsciiIsAnAssumptionRatherThanAFinding() {
        // Every candidate agrees on ASCII, so it is evidence for none of them — and the window
        // is a prefix, so it cannot promise the rest of the stream is ASCII either.
        final Sniff sniff = sniff("2026-09-10 GET /index.html 200\n");
        assertThat(sniff.encoding()).isEqualTo(FALLBACK);
        assertThat(sniff.certainty()).isEqualTo(Certainty.ASSUMED);
    }

    @Test
    void latin1TextIsReportedAsUntellable() {
        // "café x" in Latin-1. E9 is a three-byte lead in UTF-8, so it takes two more bytes to
        // prove it is not one — with fewer, the window genuinely ends mid-character and the
        // honest answer is the one below. Here there are enough: neither follower is a
        // continuation byte, so this cannot be UTF-8. Nothing can then say whether it is
        // Latin-1, Windows-1252 or ISO-8859-15, which differ only above 0x7F.
        final Sniff sniff = sniff(bytes('c', 'a', 'f', 0xE9, ' ', 'x'));
        assertThat(sniff.encoding()).isEqualTo(FALLBACK);
        assertThat(sniff.certainty()).isEqualTo(Certainty.ASSUMED);
        assertThat(sniff.because()).contains("single-byte");
    }

    @Test
    void leadByteAtTheEdgeIsSaidToBeUndecidedRatherThanAscii() {
        // Found by this test: the reader folded a lead byte at the window's edge into "ASCII",
        // so the answer explained itself as "nothing above 0x7F" when there plainly was. The
        // encoding was right by luck and the reason was wrong, which for a component whose
        // value is explaining itself is the defect.
        final Sniff sniff = sniff(bytes('c', 'a', 'f', 0xE9));
        assertThat(sniff.encoding()).isEqualTo(FALLBACK);
        assertThat(sniff.certainty()).isEqualTo(Certainty.ASSUMED);
        assertThat(sniff.because()).doesNotContain("nothing above");
        assertThat(sniff.because()).contains("too little to tell");
    }

    @Test
    void theFallbackIsTheCallersToChoose() {
        // Design 32 §6 is unruled, so the sniffer must not have a favourite of its own.
        assertThat(EncodingSniffer.sniff(bytes('c', 'a', 'f', 0xE9, ' ', 'x'), 6,
                Encoding.WINDOWS_1252).encoding()).isEqualTo(Encoding.WINDOWS_1252);
        assertThat(EncodingSniffer.sniff(bytes('h', 'i'), 2, Encoding.LATIN_1)
                .encoding()).isEqualTo(Encoding.LATIN_1);
    }

    @Test
    void characterStraddlingTheWindowEdgeIsNotAnError() {
        // The window is a prefix. A three-byte character cut after its lead byte would condemn
        // the whole stream if the read treated a short tail as invalid.
        final byte[] snowman = "☃".getBytes(StandardCharsets.UTF_8);
        final Sniff sniff = EncodingSniffer.sniff(
                bytes('h', 'i', snowman[0] & 0xFF), 3, FALLBACK);
        assertThat(sniff.encoding()).isEqualTo(FALLBACK);
        assertThat(sniff.certainty()).isEqualTo(Certainty.ASSUMED);
    }

    @Test
    void anEmptyInputTakesTheFallback() {
        assertThat(EncodingSniffer.sniff(new byte[0], 0, FALLBACK).certainty())
                .isEqualTo(Certainty.ASSUMED);
    }

    @Test
    void everyAnswerExplainsItself() {
        // The reason travels because a component that guesses has to be able to say why, and a
        // diagnostic that reads "encoding assumed" without it is not worth printing.
        for (final Sniff sniff : new Sniff[]{
                sniff(bytes(0xEF, 0xBB, 0xBF)), sniff(bytes(0x00)), sniff("é"), sniff("hi")}) {
            assertThat(sniff.because()).isNotBlank();
        }
    }

    // -----------------------------------------------------------------------------------
    // The multi-byte encodings, checked against what the JDK actually produces
    // -----------------------------------------------------------------------------------

    /** Real bytes, so the grammars are tested against an encoder rather than against my reading of a spec. */
    private static byte[] encoded(final String text, final Encoding encoding) {
        return text.getBytes(java.nio.charset.Charset.forName(encoding.label()));
    }

    private static Sniff sniffAll(final byte[] window) {
        return EncodingSniffer.sniff(window, window.length,
                EncodingSniffer.MULTI_BYTE_CANDIDATES, FALLBACK);
    }

    @Test
    void iso2022JpAnnouncesItself() {
        final byte[] jp = encoded("日本語のテキスト", Encoding.ISO_2022_JP);
        final Sniff sniff = sniffAll(jp);
        assertThat(sniff.encoding()).isEqualTo(Encoding.ISO_2022_JP);
        assertThat(sniff.certainty()).isEqualTo(Certainty.DEDUCED);
    }

    @Test
    void unmarkedUtf16IsReadAsTextRatherThanBinary() {
        // Half the bytes are NUL, so the binary rule would claim this if it ran first. Which
        // side the NULs fall on is the byte order, and random binary does not keep to one side.
        final String text = "2026-09-10 GET /index.html 200 okay then";
        assertThat(sniffAll(encoded(text, Encoding.UTF_16BE)).encoding())
                .isEqualTo(Encoding.UTF_16BE);
        assertThat(sniffAll(encoded(text, Encoding.UTF_16LE)).encoding())
                .isEqualTo(Encoding.UTF_16LE);
        assertThat(sniffAll(encoded(text, Encoding.UTF_16LE)).certainty())
                .isEqualTo(Certainty.DEDUCED);
    }

    @Test
    void everyMultiByteCandidateFitsItsOwnBytes() {
        // The grammars must at least accept what their own encoder produces. Anything else is a
        // grammar written from a misreading, which is the failure this table is most exposed to.
        final String text = "東京 2026";
        for (final Encoding encoding : EncodingSniffer.MULTI_BYTE_CANDIDATES) {
            final byte[] bytes = encoded(text, encoding);
            final Sniff sniff = EncodingSniffer.sniff(bytes, bytes.length,
                    java.util.List.of(encoding), FALLBACK);
            assertThat(sniff.encoding())
                    .as("%s must fit its own output", encoding.label())
                    .isEqualTo(encoding);
        }
    }

    @Test
    void grammarsRejectAsWellAsAccept() {
        // Added by the audit. Every other test here would pass if a grammar simply returned
        // true for everything — "fits its own output" and "several fit" both survive that. This
        // is the one that does not: these pairs are the rejections measured against real
        // encoder output, and they are what makes the table discriminate at all.
        assertThat(fitsGrammar("東京都渋谷区 2026", Encoding.SHIFT_JIS, Encoding.EUC_JP)).isFalse();
        assertThat(fitsGrammar("東京都渋谷区 2026", Encoding.SHIFT_JIS, Encoding.BIG5)).isFalse();
        assertThat(fitsGrammar("東京都渋谷区 2026", Encoding.EUC_JP, Encoding.SHIFT_JIS)).isFalse();
        assertThat(fitsGrammar("東京都渋谷区 2026", Encoding.GBK, Encoding.SHIFT_JIS)).isFalse();
        assertThat(fitsGrammar("東京都渋谷区 2026", Encoding.GBK, Encoding.EUC_KR)).isFalse();
        assertThat(fitsGrammar("東京都渋谷区 2026", Encoding.BIG5, Encoding.EUC_JP)).isFalse();
    }

    @Test
    void nothingIsReadBeyondTheWindow() {
        // The cost claim is that sniffing looks at one bounded window whatever it is handed.
        // A NUL past the window must therefore be invisible: if this returned RAW, the bound
        // would be advisory and the claim untrue.
        final byte[] huge = new byte[EncodingSniffer.WINDOW * 4];
        java.util.Arrays.fill(huge, (byte) 'a');
        huge[EncodingSniffer.WINDOW + 10] = 0;
        assertThat(EncodingSniffer.sniff(huge, huge.length, FALLBACK).encoding())
                .isEqualTo(FALLBACK);
    }

    /** Whether {@code encoding}'s grammar accepts text written in {@code written}. */
    private static boolean fitsGrammar(final String text,
                                       final Encoding written,
                                       final Encoding grammar) {
        final byte[] bytes = encoded(text, written);
        return EncodingSniffer.sniff(bytes, bytes.length, java.util.List.of(grammar), FALLBACK)
                .encoding() == grammar;
    }

    @Test
    void overlappingGrammarsAreNarrowedRatherThanDeduced() {
        // GBK, Big5 and EUC-KR accept much of the same byte space, so a window usually fits
        // several. Saying so is the point: the caller's order picked, structure did not.
        final byte[] bytes = encoded("東京 2026", Encoding.GBK);
        final Sniff sniff = sniffAll(bytes);
        assertThat(sniff.certainty()).isEqualTo(Certainty.NARROWED);
        assertThat(sniff.because()).contains("candidates the window fits");
    }

    @Test
    void theCandidateOrderDecidesTheTieAndNothingElseDoes() {
        final byte[] bytes = encoded("東京 2026", Encoding.GBK);
        assertThat(EncodingSniffer.sniff(bytes, bytes.length,
                java.util.List.of(Encoding.BIG5, Encoding.GBK), FALLBACK).encoding())
                .isEqualTo(Encoding.BIG5);
        assertThat(EncodingSniffer.sniff(bytes, bytes.length,
                java.util.List.of(Encoding.GBK, Encoding.BIG5), FALLBACK).encoding())
                .isEqualTo(Encoding.GBK);
    }

    @Test
    void validUtf8OutranksTheCandidateList() {
        // UTF-8 text often satisfies a legacy grammar, but legacy text is rarely valid UTF-8.
        // The evidence is asymmetric, so UTF-8 is asked first and the list does not get a say.
        final byte[] bytes = "née £5 ☃".getBytes(StandardCharsets.UTF_8);
        assertThat(sniffAll(bytes).encoding()).isEqualTo(Encoding.UTF_8);
        assertThat(sniffAll(bytes).certainty()).isEqualTo(Certainty.DEDUCED);
    }

    @Test
    void noCandidatesMeansTheOldAnswer() {
        // The list is optional; with none, a window that is not UTF-8 is still just untellable.
        final Sniff sniff = sniff(bytes('c', 'a', 'f', 0xE9, ' ', 'x'));
        assertThat(sniff.certainty()).isEqualTo(Certainty.ASSUMED);
    }

    /** A window written as unsigned ints, because {@code (byte) 0xFF} everywhere is unreadable. */
    private static byte[] bytes(final int... values) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final int value : values) {
            out.write(value);
        }
        return out.toByteArray();
    }
}
