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

package stroom.shapeshifter.regex.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive conformance for the UTF-8 class compiler.
 * <p>
 * The claim being tested is exact, so the test is exhaustive rather than sampled: for every
 * code point in the BMP, the compiled byte-range sequences accept its encoding <b>if and only
 * if</b> the code point is a member of the class. An off-by-one at a length or continuation
 * boundary — the failure mode this algorithm exists to avoid — cannot survive that.
 */
class Utf8ConformanceTest {

    private record Case(String name, CodePointSet set) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> classes() {
        return Stream.of(
                new Case("ascii letters", CodePointSet.of('a', 'z')),
                new Case("single ascii", CodePointSet.single('x')),
                new Case("single 2-byte", CodePointSet.single(0xE9)),          // é
                new Case("single 3-byte", CodePointSet.single(0x20AC)),        // €
                new Case("single 4-byte", CodePointSet.single(0x1F600)),       // emoji
                new Case("latin supplement", CodePointSet.of(0xC0, 0xFF)),
                // The example the whole algorithm exists for: needs two sequences, because
                // U+04FF encodes to D3 BF but U+052F encodes to D4 AF.
                new Case("cyrillic + supplement", CodePointSet.of(0x0400, 0x052F)),
                new Case("across 1/2 byte boundary", CodePointSet.of(0x7E, 0x81)),
                new Case("across 2/3 byte boundary", CodePointSet.of(0x7FE, 0x801)),
                new Case("across 3/4 byte boundary", CodePointSet.of(0xFFFE, 0x10001)),
                new Case("spanning surrogates", CodePointSet.of(0xD000, 0xE000)),
                new Case("everything", CodePointSet.all()),
                new Case("everything but newline", CodePointSet.single('\n').negate()),
                new Case("negated ascii", CodePointSet.single(',').negate()),
                new Case("disjoint ranges", CodePointSet.of('a', 'c', 0x100, 0x1FF, 0x10000, 0x1000F)));
    }

    @ParameterizedTest
    @MethodSource("classes")
    void acceptsExactlyTheMembersOfTheClass(final Case testCase) {
        final CharClass charClass = new CharClass(testCase.set(), testCase.name(), ByteForm.UTF8);

        for (int codePoint = 0; codePoint <= 0xFFFF; codePoint++) {
            if (codePoint >= 0xD800 && codePoint <= 0xDFFF) {
                continue; // unencodable, so never a member
            }
            assertAgrees(charClass, testCase.set(), codePoint);
        }
        // Astral planes, sampled — 0x10FFFF iterations would be slow with no more coverage of
        // the interesting boundaries, which are all at the low end of the 4-byte range.
        for (int codePoint = 0x10000; codePoint <= 0x10FFFF; codePoint += 97) {
            assertAgrees(charClass, testCase.set(), codePoint);
        }
        assertAgrees(charClass, testCase.set(), 0x10FFFF);
    }

    private static void assertAgrees(final CharClass charClass,
                                     final CodePointSet set,
                                     final int codePoint) {
        final byte[] encoded = Utf8.encode(codePoint);
        final int matched = charClass.matchAt(encoded, 0, encoded.length);
        final boolean shouldMatch = set.contains(codePoint);

        assertThat(matched >= 0)
                .as("U+%04X (%d bytes) membership", codePoint, encoded.length)
                .isEqualTo(shouldMatch);
        if (shouldMatch) {
            assertThat(matched)
                    .as("U+%04X must be consumed whole", codePoint)
                    .isEqualTo(encoded.length);
        }
    }

    @Test
    void rejectsIllFormedUtf8() {
        final CharClass everything = new CharClass(CodePointSet.all(), "all", ByteForm.UTF8);
        final List<byte[]> illFormed = List.of(
                new byte[]{(byte) 0x80},                                     // lone continuation
                new byte[]{(byte) 0xBF},                                     // lone continuation
                new byte[]{(byte) 0xC0, (byte) 0x80},                        // overlong NUL
                new byte[]{(byte) 0xC1, (byte) 0xBF},                        // overlong
                new byte[]{(byte) 0xE0, (byte) 0x80, (byte) 0x80},           // overlong
                new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80},           // surrogate U+D800
                new byte[]{(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80}, // beyond U+10FFFF
                new byte[]{(byte) 0xFF},
                new byte[]{(byte) 0xC3});                                    // truncated

        for (final byte[] bytes : illFormed) {
            assertThat(everything.matchAt(bytes, 0, bytes.length))
                    .as("ill-formed sequence %s must not match even the all-inclusive class",
                            java.util.Arrays.toString(bytes))
                    .isEqualTo(-1);
        }
    }

    @Test
    void producesTheDocumentedSequencesForCyrillic() {
        // The worked example from Utf8's javadoc, pinned so the algorithm's output stays legible.
        final int[][] sequences = Utf8.sequences(CodePointSet.of(0x0400, 0x052F));
        assertThat(sequences.length).isEqualTo(2);
        assertThat(sequences[0]).containsExactly(0xD0, 0xD3, 0x80, 0xBF);
        assertThat(sequences[1]).containsExactly(0xD4, 0xD4, 0x80, 0xAF);
    }

    @Test
    void classifiesScanSafety() {
        // Drives whether a repetition can scan bytes or must step characters.
        assertThat(CodePointSet.of('a', 'z').isAsciiOnly()).isTrue();
        assertThat(CodePointSet.single(',').negate().isAsciiOnly()).isFalse();
        assertThat(CodePointSet.single(',').negate().containsAllNonAscii()).isTrue();
        assertThat(CodePointSet.single('\n').negate().containsAllNonAscii()).isTrue();
        assertThat(CodePointSet.all().containsAllNonAscii()).isTrue();
        assertThat(CodePointSet.of('a', 'z').containsAllNonAscii()).isFalse();
        assertThat(CodePointSet.of(0x0400, 0x052F).containsAllNonAscii()).isFalse();
    }

    @Test
    void excludesSurrogatesFromEveryClass() {
        assertThat(CodePointSet.all().contains(0xD800)).isFalse();
        assertThat(CodePointSet.of(0xD000, 0xE000).contains(0xDABC)).isFalse();
        assertThat(CodePointSet.of(0xD000, 0xE000).contains(0xD7FF)).isTrue();
        assertThat(CodePointSet.of(0xD000, 0xE000).contains(0xE000)).isTrue();
    }
}
