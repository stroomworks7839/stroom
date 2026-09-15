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

package stroom.shapeshifter.engine.value;

import stroom.shapeshifter.engine.output.ByteSink;
import stroom.shapeshifter.engine.text.Encoding;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A slice is a byte value over a range of an array it does not own (design 37 §5, phase 3b).
 * Nothing in the engine makes one yet; these pin the one rule the class must keep before a
 * match is allowed to produce them — <b>text equality and hash across all three byte
 * variants</b> — and that the consumers that matter read the range rather than a copy.
 */
class ByteSliceTest {

    private static final byte[] RECORD = "key=value;next".getBytes(StandardCharsets.UTF_8);

    /** {@code value} as a slice of the record, positions 4 to 9. */
    private static TypedValue.Bytes slice() {
        return (TypedValue.Bytes) TypedValue.slice(RECORD, 4, 9, Encoding.UTF_8);
    }

    @Test
    void sliceIsItsRangeAndNothingElse() {
        final TypedValue.Bytes value = slice();
        assertThat(value.asString()).isEqualTo("value");
        assertThat(value.value()).isEqualTo("value".getBytes(StandardCharsets.UTF_8));
        assertThat(value.asUtf8()).isEqualTo("value".getBytes(StandardCharsets.UTF_8));
        assertThat(value.isEmpty()).isFalse();
        assertThat(TypedValue.slice(RECORD, 4, 4, Encoding.UTF_8).isEmpty()).isTrue();
        assertThat(value.utf8Array()).as("the range, not a copy").isSameAs(RECORD);
        assertThat(value.utf8Offset()).isEqualTo(4);
        assertThat(value.utf8Length()).isEqualTo(5);
    }

    /** The pin the class was written for: a slice as a map key finds an entry put as a copy. */
    @Test
    void textEqualityAndHashAgreeAcrossAllThreeByteVariants() {
        final TypedValue whole = TypedValue.of("value");
        final TypedValue encoded = TypedValue.of("value".getBytes(StandardCharsets.ISO_8859_1), Encoding.LATIN_1);
        final TypedValue sliced = slice();

        assertThat(sliced).isEqualTo(whole);
        assertThat(whole).isEqualTo(sliced);
        assertThat(sliced).isEqualTo(encoded);
        assertThat(encoded).isEqualTo(sliced);
        assertThat(sliced.hashCode()).isEqualTo(whole.hashCode());
        assertThat(sliced.hashCode()).isEqualTo(encoded.hashCode());

        final TypedValue.Map map = new TypedValue.Map();
        map.put(whole, TypedValue.of("found"));
        assertThat(map.get(sliced)).as("a freshly matched slice looks up a key put as a copy")
                .isEqualTo(TypedValue.of("found"));

        assertThat(sliced).isNotEqualTo(TypedValue.slice(RECORD, 0, 3, Encoding.UTF_8));
        assertThat(sliced).isNotEqualTo(new TypedValue.Integer(5));
    }

    /** A value is what a nested match runs over: its source slices, at positions in its UTF-8 form. */
    @Test
    void valueAsSourceSlicesItself() {
        final TypedValue whole = TypedValue.of("key=value");
        final TypedValue viaSource = ((TypedValue.Bytes) whole).source().slice(4, 9, Encoding.UTF_8);
        assertThat(viaSource).isInstanceOf(TypedValue.ByteSlice.class);
        assertThat(viaSource).isEqualTo(slice());
        assertThat(((TypedValue.Bytes) viaSource).utf8Array()).isSameAs(((TypedValue.Bytes) whole).utf8Array());

        // A slice's source is the parent array, so a match over a slice slices the array too:
        // positions are absolute in it, and a slice never nests.
        final TypedValue.Bytes inner = (TypedValue.Bytes) slice().source().slice(5, 7, Encoding.UTF_8);
        assertThat(inner.asString()).isEqualTo("al");
        assertThat(inner.utf8Array()).isSameAs(RECORD);

        // The window answers the same question with a copy.
        final TypedValue copied = new ByteSource.Copying(RECORD).slice(4, 9, Encoding.UTF_8);
        assertThat(copied).isInstanceOf(TypedValue.Utf8Bytes.class);
        assertThat(copied).isEqualTo(slice());
        assertThat(((TypedValue.Bytes) copied).utf8Array()).isNotSameAs(RECORD);
    }

    /** A slice in another encoding decodes its range, and its UTF-8 form is made once. */
    @Test
    void encodedSliceDecodesItsRange() {
        final byte[] latin = "café au lait".getBytes(StandardCharsets.ISO_8859_1);
        final TypedValue.Bytes cafe = (TypedValue.Bytes) TypedValue.slice(latin, 0, 4, Encoding.LATIN_1);
        assertThat(cafe.asString()).isEqualTo("café");
        assertThat(cafe.asUtf8()).isEqualTo("café".getBytes(StandardCharsets.UTF_8));
        assertThat(cafe.asUtf8()).as("memoised").isSameAs(cafe.asUtf8());
        assertThat(cafe.value()).as("as read, in its own encoding").isEqualTo(new byte[]{'c', 'a', 'f', (byte) 0xE9});
        assertThat(cafe).isEqualTo(TypedValue.of("café"));
        assertThat(cafe.hashCode()).isEqualTo(TypedValue.of("café").hashCode());
        assertThat(cafe.bytes(Encoding.LATIN_1)).isEqualTo(new byte[]{'c', 'a', 'f', (byte) 0xE9});
    }

    /** Comparison reads the range: a slice sorts where its text does. */
    @Test
    void comparisonReadsTheRange() {
        assertThat(Comparisons.compare(slice(), TypedValue.of("value"))).isZero();
        assertThat(Comparisons.compare(slice(), TypedValue.of("valuf"))).isNegative();
        assertThat(Comparisons.compare(TypedValue.of("key"), TypedValue.slice(RECORD, 0, 3, Encoding.UTF_8))).isZero();
    }

    /** A value writes itself: a slice to a UTF-8 sink is its range, uncopied; to any other sink it transcodes. */
    @Test
    void sliceWritesItsRangeToAUtf8SinkAndTranscodesToAnyOther() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        slice().writeTo(new ByteSink(out, Encoding.UTF_8), Encoding.UTF_8);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("value");

        final byte[] latin = "café au lait".getBytes(StandardCharsets.ISO_8859_1);
        final TypedValue cafe = TypedValue.slice(latin, 0, 4, Encoding.LATIN_1);
        final ByteArrayOutputStream utf8Out = new ByteArrayOutputStream();
        cafe.writeTo(new ByteSink(utf8Out, Encoding.UTF_8), Encoding.UTF_8);
        assertThat(utf8Out.toByteArray()).isEqualTo("café".getBytes(StandardCharsets.UTF_8));
        final ByteArrayOutputStream latinOut = new ByteArrayOutputStream();
        cafe.writeTo(new ByteSink(latinOut, Encoding.LATIN_1), Encoding.LATIN_1);
        assertThat(latinOut.toByteArray()).isEqualTo(new byte[]{'c', 'a', 'f', (byte) 0xE9});
    }
}
