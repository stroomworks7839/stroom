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


import stroom.shapeshifter.engine.config.Codec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The function library, tested directly.
 *
 * <p>The corpus barely touches it — its ten {@code replace} instructions are all literal, so
 * regex replacement with group expansion has <b>no</b> fixture coverage at all, and neither does
 * {@code substring} past the ASCII range. Those are exactly the places a port goes quietly wrong,
 * so they are tested here rather than left to a fixture that might arrive one day.
 */
class TransformsTest {

    /** Wrap plain text as the typed inputs the library now takes (design/17 §3.2). */
    private static List<TypedValue> vals(final String... values) {
        return List.of(values).stream().map(v -> TypedValue.of(v)).toList();
    }

    /** A result's text, for asserting — null stays null. */
    private static String text(final TypedValue value) {
        return value == null ? null : value.asString();
    }

    @Test
    void translateSubstitutesEachPairInOrder() {
        assertThat(text(Transforms.translate(vals("a-b-c"), List.of("-"), List.of("_"))))
                .isEqualTo("a_b_c");
        assertThat(text(Transforms.translate(vals("abc"), List.of("a", "b"), List.of("1", "2"))))
                .isEqualTo("12c");
        // A search with no replacement deletes.
        assertThat(text(Transforms.translate(vals("a-b"), List.of("-"), List.of())))
                .isEqualTo("ab");
    }

    @Test
    void stringJoinSkipsWhatIsNotThere() {
        assertThat(text(Transforms.stringJoin(vals("a", "b"), ", "))).isEqualTo("a, b");
        // The point of skipping: a missing middle must not leave "a, , c".
        assertThat(text(Transforms.stringJoin(vals("a", "", "c"), ", "))).isEqualTo("a, c");
        assertThat(text(Transforms.stringJoin(vals("", ""), ", "))).isNull();
        assertThat(text(Transforms.stringJoin(vals(), ", "))).isNull();
    }

    @Test
    void normalizeSpaceCollapsesRunsAndEnds() {
        assertThat(text(Transforms.normalizeSpace(vals("  a \t\n b  ")))).isEqualTo("a b");
        assertThat(text(Transforms.normalizeSpace(vals("single")))).isEqualTo("single");
    }

    @Test
    void substringCountsCharactersNotBytes() {
        assertThat(text(Transforms.substring(vals("abcdef"), 1, 3))).isEqualTo("bcd");
        assertThat(text(Transforms.substring(vals("abcdef"), 3, null))).isEqualTo("def");
        // Past the end is the end, not an exception.
        assertThat(text(Transforms.substring(vals("abc"), 1, 99))).isEqualTo("bc");
        assertThat(text(Transforms.substring(vals("abc"), 99, 1))).isEmpty();
        // Multi-byte characters count as one each, and are never cut in half.
        assertThat(text(Transforms.substring(vals("héllo wörld"), 0, 5))).isEqualTo("héllo");
        assertThat(text(Transforms.substring(vals("😀😀😀"), 1, 1))).isEqualTo("😀");
    }

    @Test
    void tokenizeKeepsEmptyPieces() {
        assertThat(text(Transforms.tokenize(vals("a,b,c"), ","))).isEqualTo("a\nb\nc");
        assertThat(text(Transforms.tokenize(vals("a,,c"), ","))).isEqualTo("a\n\nc");
        // The delimiter is a literal, not a pattern.
        assertThat(text(Transforms.tokenize(vals("a.b"), "."))).isEqualTo("a\nb");
    }

    @Test
    void numberKeepsWholeNumbersWhole() {
        assertThat(text(Transforms.number(vals(" 42 ")))).isEqualTo("42");
        assertThat(text(Transforms.number(vals("42.5")))).isEqualTo("42.5");
        assertThat(text(Transforms.number(vals("not a number")))).isNull();
    }

    // -----------------------------------------------------------------------------------
    // Arithmetic (design/17 §§5, 11) — each function's edges, per the phase plan
    // -----------------------------------------------------------------------------------

    @Test
    void addFoldsExactlyOverWholeNumbers() {
        assertThat(text(Transforms.add(vals("2", "3", "4")))).isEqualTo("9");
        // Exact long arithmetic where doubles would round: 2^53 + 1 survives.
        assertThat(text(Transforms.add(vals("9007199254740992", "1"))))
                .isEqualTo("9007199254740993");
    }

    @Test
    void addOverflowPromotesToRealRatherThanWrapping() {
        final TypedValue result = Transforms.add(vals(String.valueOf(Long.MAX_VALUE), "1"));
        assertThat(result).isInstanceOf(TypedValue.Double.class);
        assertThat(result.asNumber()).isEqualTo((double) Long.MAX_VALUE + 1);
    }

    @Test
    void anyNonNumericInputMakesTheResultAbsent() {
        assertThat(Transforms.add(vals("2", "n/a"))).isNull();
        assertThat(Transforms.multiply(vals("n/a", "3"))).isNull();
    }

    @Test
    void subtractAndMultiply() {
        assertThat(text(Transforms.subtract(vals("10", "4")))).isEqualTo("6");
        assertThat(text(Transforms.multiply(vals("2.5", "4")))).isEqualTo("10");
        final TypedValue product = Transforms.multiply(vals("2.5", "4"));
        assertThat(product).isInstanceOf(TypedValue.Double.class);
    }

    @Test
    void divideIsWholeWhenExactAndAbsentOnZero() {
        assertThat(Transforms.divide(vals("10", "2"))).isInstanceOf(TypedValue.Integer.class);
        assertThat(text(Transforms.divide(vals("10", "2")))).isEqualTo("5");
        assertThat(text(Transforms.divide(vals("10", "4")))).isEqualTo("2.5");
        assertThat(Transforms.divide(vals("10", "0"))).isNull();
        assertThat(Transforms.divide(vals("10.0", "0.0"))).isNull();
    }

    @Test
    void divideOfMinValueByMinusOnePromotesRatherThanWrapping() {
        // Java's long / wraps this one silently: MIN / -1 == MIN, a negative "answer" for a
        // positive quotient. Found by the phase 2 audit; the promotion rule covers it (§11).
        final TypedValue result = Transforms.divide(vals(String.valueOf(Long.MIN_VALUE), "-1"));
        assertThat(result).isInstanceOf(TypedValue.Double.class);
        assertThat(result.asNumber()).isEqualTo(-(double) Long.MIN_VALUE);
        // Its modulus twin has a long answer and keeps it.
        assertThat(text(Transforms.mod(vals(String.valueOf(Long.MIN_VALUE), "-1")))).isEqualTo("0");
    }

    @Test
    void modSignFollowsTheDividend() {
        assertThat(text(Transforms.mod(vals("7", "3")))).isEqualTo("1");
        // XPath's mod and Java's %: -7 mod 3 is -1, not 2.
        assertThat(text(Transforms.mod(vals("-7", "3")))).isEqualTo("-1");
        assertThat(Transforms.mod(vals("7", "0"))).isNull();
    }

    @Test
    void roundIsHalfUpOnTheNegativeTie() {
        assertThat(text(Transforms.round(vals("2.5")))).isEqualTo("3");
        // XPath's round, not Math.round-on-negatives: -2.5 rounds toward positive infinity.
        assertThat(text(Transforms.round(vals("-2.5")))).isEqualTo("-2");
        assertThat(text(Transforms.round(vals("7")))).isEqualTo("7");
    }

    @Test
    void floorCeilingAbs() {
        assertThat(text(Transforms.floor(vals("2.7")))).isEqualTo("2");
        assertThat(text(Transforms.ceiling(vals("2.1")))).isEqualTo("3");
        assertThat(text(Transforms.abs(vals("-9")))).isEqualTo("9");
        // The one long with no positive twin promotes rather than staying negative.
        assertThat(Transforms.abs(vals(String.valueOf(Long.MIN_VALUE))))
                .isInstanceOf(TypedValue.Double.class);
    }

    @Test
    void numberIsNowTheTypedCast() {
        // Phase 2: the point decides the kind, and the result is a number, not a rendering.
        assertThat(Transforms.number(vals("42"))).isInstanceOf(TypedValue.Integer.class);
        assertThat(Transforms.number(vals("42.5"))).isInstanceOf(TypedValue.Double.class);
        // The visible rendering change from the ported form: a whole Real drops its .0.
        assertThat(text(Transforms.number(vals("5.0")))).isEqualTo("5");
    }

    // -----------------------------------------------------------------------------------
    // The string additions (design/17 §6)
    // -----------------------------------------------------------------------------------

    @Test
    void stringLengthCountsCodePointsNotBytes() {
        assertThat(text(Transforms.stringLength(vals("abc")))).isEqualTo("3");
        // Two characters, three bytes and four bytes: still 2.
        assertThat(text(Transforms.stringLength(vals("é😀")))).isEqualTo("2");
        assertThat(Transforms.stringLength(vals("abc"))).isInstanceOf(TypedValue.Integer.class);
    }

    @Test
    void substringBeforeIsAbsentNotEmptyOnAMissingMarker() {
        assertThat(text(Transforms.substringBefore(vals("a|b"), "|"))).isEqualTo("a");
        assertThat(text(Transforms.substringAfter(vals("a|b"), "|"))).isEqualTo("b");
        // Absent, not "" — so exists can tell "no marker" from "nothing before it".
        assertThat(Transforms.substringBefore(vals("ab"), "|")).isNull();
        assertThat(Transforms.substringAfter(vals("ab"), "|")).isNull();
    }

    @Test
    void predicateValuesBindAsBooleans() {
        assertThat(Transforms.startsWith(vals("alpha"), "al")).isEqualTo(new TypedValue.Bool(true));
        assertThat(Transforms.endsWith(vals("alpha"), "ha")).isEqualTo(new TypedValue.Bool(true));
        assertThat(Transforms.contains(vals("alpha"), "ph")).isEqualTo(new TypedValue.Bool(true));
        assertThat(text(Transforms.contains(vals("alpha"), "xx"))).isEqualTo("false");
    }

    @Test
    void formatNumberOrdinaryPicturesAndOneEdge() {
        final var format = new java.text.DecimalFormat("#,##0.00",
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT));
        assertThat(text(Transforms.formatNumber(vals("1234.5"), format))).isEqualTo("1,234.50");
        assertThat(text(Transforms.formatNumber(vals("-3"), format))).isEqualTo("-3.00");
        assertThat(Transforms.formatNumber(vals("n/a"), format)).isNull();
        // The documented edge: DecimalFormat renders infinity as ∞ where XSLT says Infinity.
        assertThat(text(Transforms.formatNumber(
                List.of(new TypedValue.Double(Double.POSITIVE_INFINITY)), format)))
                .isEqualTo("∞");
    }


    // -----------------------------------------------------------------------------------
    // The text codecs (design 41): what an XML or JSON value needs decoded, in one op
    // -----------------------------------------------------------------------------------

    private static String decode(final String input, final Codec codec) {
        final TypedValue decoded = Transforms.decode(List.of(TypedValue.of(input)), codec);
        return decoded == null ? null : decoded.asString();
    }

    @Test
    void xmlEntitiesDecodeThePredefinedFiveAndNumericReferences() {
        assertThat(decode("a &lt; b &gt; c &amp; d &quot;e&quot; &apos;f&apos;", Codec.XML_ENTITIES))
                .isEqualTo("a < b > c & d \"e\" 'f'");
        assertThat(decode("caf&#233; &#xE9; &#x1F600; &#65;", Codec.XML_ENTITIES)).isEqualTo("café é 😀 A");
        assertThat(decode("&amp;amp;", Codec.XML_ENTITIES)).as("decoded once, not twice").isEqualTo("&amp;");
    }

    @Test
    void xmlEntitiesLeaveWhatIsNotAReferenceAsWritten() {
        assertThat(decode("a & b &foo; &#; &#xZZ; &lt", Codec.XML_ENTITIES)).isEqualTo("a & b &foo; &#; &#xZZ; &lt");
        final TypedValue plain = TypedValue.of("nothing to decode");
        assertThat(Transforms.decode(List.of(plain), Codec.XML_ENTITIES)).as("returned as itself").isSameAs(plain);
    }

    @Test
    void jsonStringDecodesTheEscapesAndJoinsSurrogatePairs() {
        assertThat(decode("a\\\"b\\\\c\\/d\\ne\\tf\\u00e9g\\ud83d\\ude00h", Codec.JSON_STRING))
                .isEqualTo("a\"b\\c/d\ne\tfég😀h");
        assertThat(decode("\\\\n", Codec.JSON_STRING)).as("an escaped backslash before an n is not a newline")
                .isEqualTo("\\n");
        assertThat(decode("x\\q \\u12 \\", Codec.JSON_STRING)).as("what is not an escape stays")
                .isEqualTo("x\\q \\u12 \\");
    }

    @Test
    void textCodecsReadTheUtf8FormOfAValueReadUnderAnotherEncoding() {
        final TypedValue latin1 = TypedValue.of("café &amp; thé".getBytes(StandardCharsets.ISO_8859_1),
                stroom.shapeshifter.engine.text.Encoding.fromLabel("iso-8859-1"));
        assertThat(Transforms.decode(List.of(latin1), Codec.XML_ENTITIES).asString()).isEqualTo("café & thé");
    }
}
