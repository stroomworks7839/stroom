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

import stroom.shapeshifter.regex.BytePattern;

import org.junit.jupiter.api.Test;

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
    // Regex replacement — no fixture reaches this
    // -----------------------------------------------------------------------------------

    @Test
    void replaceRegexExpandsGroupReferences() {
        final BytePattern pattern = BytePattern.compile("(\\w+)=(\\w+)");
        assertThat(Transforms.replaceRegex(pattern, "a=1 b=2", "$2:$1"))
                .isEqualTo("1:a 2:b");
        assertThat(Transforms.replaceRegex(pattern, "a=1", "${2}${1}"))
                .isEqualTo("1a");
    }

    @Test
    void replaceRegexUsesRustsExpansionRulesNotJavas() {
        final BytePattern pattern = BytePattern.compile("(\\d+)");
        // $$ is a literal dollar.
        assertThat(Transforms.replaceRegex(pattern, "cost 5", "$$$1"))
                .isEqualTo("cost $5");
        // A dollar followed by nothing nameable is a literal dollar, not an error — which is
        // where java.util.regex would throw.
        assertThat(Transforms.replaceRegex(pattern, "5", "$ x")).isEqualTo("$ x");
        // A named group, by name.
        assertThat(Transforms.replaceRegex(BytePattern.compile("(?<word>[a-z]+)"), "hi there", "<$word>"))
                .isEqualTo("<hi> <there>");
    }

    @Test
    void replaceRegexExpandsAnAbsentGroupToNothing() {
        final BytePattern pattern = BytePattern.compile("a(x)?b");
        assertThat(Transforms.replaceRegex(pattern, "ab axb", "[$1]"))
                .isEqualTo("[] [x]");
    }

    @Test
    void replaceRegexTerminatesOnAZeroWidthMatch() {
        // The pattern matches emptily everywhere. Without advancing past a zero-width match this
        // would never finish, which is the classic way a replace-all loop hangs.
        assertThat(Transforms.replaceRegex(BytePattern.compile("x*"), "abc", "-"))
                .isEqualTo("-a-b-c-");
    }

    @Test
    void replaceRegexAdvancesByWholeCharacters() {
        // A zero-width match before a multi-byte character must step over all of it, or the
        // output is cut through the middle of a character.
        assertThat(Transforms.replaceRegex(BytePattern.compile("x*"), "é😀", "-"))
                .isEqualTo("-é-😀-");
    }
}
