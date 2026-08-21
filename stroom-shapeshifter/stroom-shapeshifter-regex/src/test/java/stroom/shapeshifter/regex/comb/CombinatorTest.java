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

package stroom.shapeshifter.regex.comb;

import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.Engine;
import stroom.shapeshifter.regex.PatternCompileException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CombinatorTest {

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------------------
    // The central claim: composing costs nothing
    // -----------------------------------------------------------------------------------

    /**
     * A composition and the regex that means the same thing must compile to the <b>identical
     * plan</b> — not merely to something that behaves the same. That is what makes the choice
     * between them purely a readability decision, and it is the whole justification for lowering
     * compositions instead of interpreting them.
     */
    @Test
    void compositionCompilesToTheSamePlanAsEquivalentRegex() {
        final BytePattern composed = new MatcherLibrary().compile(
                Matchers.sequence(
                        Matchers.takeWhile("[a-z]").label("key"),
                        Matchers.tag("="),
                        Matchers.takeWhile("[0-9]").label("value")));
        final BytePattern written = BytePattern.compile("([a-z]+)=([0-9]+)");

        assertThat(planOf(composed)).isEqualTo(planOf(written));
        assertThat(composed.tier()).isEqualTo(written.tier());
    }

    @Test
    void composedChoiceCompilesToTheSamePlanAsEquivalentRegex() {
        final BytePattern composed = new MatcherLibrary().compile(
                Matchers.choice(Matchers.tag("ERROR"), Matchers.tag("WARN"), Matchers.tag("INFO")));
        final BytePattern written = BytePattern.compile("ERROR|WARN|INFO");

        assertThat(planOf(composed)).isEqualTo(planOf(written));
    }

    /** Everything after lowering is shared, so a composition reaches tier 0 on the same terms. */
    @Test
    void compositionsUseTheSameTierSelection() {
        final MatcherLibrary library = new MatcherLibrary();

        // Disjoint alternatives — decidable from one byte.
        assertThat(library.compile(Matchers.choice(Matchers.tag("ERROR"), Matchers.tag("WARN"))).tier())
                .isZero();

        // Overlapping alternatives — needs the simulation, exactly as the regex would.
        assertThat(library.compile(Matchers.choice(Matchers.tag("a"), Matchers.tag("ab"))).tier())
                .isEqualTo(Engine.SIMULATE.ordinal());
    }

    /** Strips the header so only the compiled instructions are compared. */
    private static String planOf(final BytePattern pattern) {
        final String explained = pattern.explain();
        return explained.substring(explained.indexOf("tier:"));
    }

    // -----------------------------------------------------------------------------------
    // Matching
    // -----------------------------------------------------------------------------------

    @Test
    void matchesASequenceWithLabels() {
        final BytePattern pattern = new MatcherLibrary().compile(
                Matchers.sequence(
                        Matchers.takeWhile("[a-z]").label("key"),
                        Matchers.tag("="),
                        Matchers.takeUntil(',').label("value")));

        final ByteMatcher matcher = pattern.matcher();
        assertThat(matcher.find(bytes("colour=red,size=big"))).isTrue();
        assertThat(matcher.group("key").toString()).isEqualTo("colour");
        assertThat(matcher.group("value").toString()).isEqualTo("red");
    }

    @Test
    void matchesADelimitedField() {
        final BytePattern pattern = new MatcherLibrary().compile(
                Matchers.delimited(
                        Matchers.tag("\""),
                        Matchers.takeUntil('"').label("content"),
                        Matchers.tag("\"")));

        final ByteMatcher matcher = pattern.matcher();
        assertThat(matcher.find(bytes("say \"hello there\" now"))).isTrue();
        assertThat(matcher.group("content").toString()).isEqualTo("hello there");
    }

    @Test
    void matchesASeparatedList() {
        final BytePattern pattern = new MatcherLibrary().compile(
                Matchers.separated(Matchers.takeWhile("[0-9]"), Matchers.tag(".")).label("address"));

        final ByteMatcher matcher = pattern.matcher();
        assertThat(matcher.find(bytes("host 192.168.1.1 up"))).isTrue();
        assertThat(matcher.group("address").toString()).isEqualTo("192.168.1.1");
    }

    @Test
    void embedsARegexAsAnElement() {
        // A regex is a first-class element, and its own groups interleave with the labels
        // around it rather than colliding with them.
        final BytePattern pattern = new MatcherLibrary().compile(
                Matchers.sequence(
                        Matchers.tag("["),
                        Matchers.regex("(\\d{4})-(\\d{2})").label("date"),
                        Matchers.tag("]")));

        final ByteMatcher matcher = pattern.matcher();
        assertThat(matcher.find(bytes("at [2026-08] today"))).isTrue();
        assertThat(matcher.group("date").toString()).isEqualTo("2026-08");
        assertThat(matcher.groupString(2)).isEqualTo("2026");
        assertThat(matcher.groupString(3)).isEqualTo("08");
    }

    @Test
    void matchesUtf8ThroughComposition() {
        final BytePattern pattern = new MatcherLibrary().compile(
                Matchers.sequence(Matchers.takeWhile("[a-zé]").label("word"), Matchers.tag("=")));

        final ByteMatcher matcher = pattern.matcher();
        assertThat(matcher.find(bytes("café=1"))).isTrue();
        assertThat(matcher.group("word").toString()).isEqualTo("café");
    }

    // -----------------------------------------------------------------------------------
    // Named matchers
    // -----------------------------------------------------------------------------------

    @Test
    void resolvesReferencesFromTheLibrary() {
        final MatcherLibrary library = new MatcherLibrary()
                .define("digits", Matchers.takeWhile("[0-9]"))
                .define("octet", Matchers.ref("digits"))
                .define("ipv4", Matchers.separated(Matchers.ref("octet"), Matchers.tag(".")));

        final ByteMatcher matcher = library.compile("ipv4").matcher();
        assertThat(matcher.find(bytes("from 10.0.0.255 ok"))).isTrue();
        assertThat(matcher.groupString(0)).isEqualTo("10.0.0.255");
    }

    @Test
    void shipsAStandardLibrary() {
        final MatcherLibrary library = Matchers.standardLibrary();
        assertThat(library.names()).contains("digits", "quotedString", "ipv4", "isoDate", "csvField");

        final ByteMatcher matcher = library.compile("isoDateTime").matcher();
        assertThat(matcher.find(bytes("at 2026-08-17T14:30:00 today"))).isTrue();
        assertThat(matcher.groupString(0)).isEqualTo("2026-08-17T14:30:00");
    }

    /** The standard ipv4 is four 1-3 digit octets — not any dotted run of digits. */
    @Test
    void standardIpv4RequiresFourOctets() {
        final ByteMatcher matcher = Matchers.standardLibrary().compile("ipv4").matcher();

        assertThat(matcher.find(bytes("from 10.0.0.255 ok"))).isTrue();
        assertThat(matcher.groupString(0)).isEqualTo("10.0.0.255");

        assertThat(matcher.find(bytes("v1.2 loaded"))).isFalse();
    }

    /**
     * An embedded regex's own {@code \k<name>} resolves against absolute group numbers, seeded
     * with the composition's labels. Unseeded, the first embedded name landed at index 0 — which
     * reads as group 0 and was refused — and any later name resolved to the wrong group.
     */
    @Test
    void anEmbeddedRegexResolvesItsOwnNamedBackreference() {
        final BytePattern pattern = new MatcherLibrary().compile(Matchers.sequence(
                Matchers.takeWhile("[a-z]").label("key"),
                Matchers.tag(" "),
                Matchers.regex("(?<digits>[0-9]+)-\\k<digits>")));

        final ByteMatcher matcher = pattern.matcher();
        assertThat(matcher.find(bytes("abc 42-42"))).isTrue();
        assertThat(matcher.group("key").toString()).isEqualTo("abc");
        assertThat(matcher.group("digits").toString()).isEqualTo("42");

        assertThat(matcher.find(bytes("abc 42-43"))).isFalse();
    }

    /** A second embedded name resolves to its own group, not a neighbour's. */
    @Test
    void aLaterEmbeddedNameResolvesToItsOwnGroup() {
        final BytePattern pattern = new MatcherLibrary().compile(Matchers.sequence(
                Matchers.takeWhile("[a-z]").label("key"),
                Matchers.tag(" "),
                Matchers.regex("(?<left>[0-9]+),(?<right>[0-9]+)=\\k<right>")));

        final ByteMatcher matcher = pattern.matcher();
        assertThat(matcher.find(bytes("abc 11,22=22"))).isTrue();
        assertThat(matcher.group("left").toString()).isEqualTo("11");
        assertThat(matcher.group("right").toString()).isEqualTo("22");

        // Before seeding, \k<right> resolved one group too low — to left — and this matched.
        assertThat(matcher.find(bytes("abc 11,22=11"))).isFalse();
    }

    /** An embedded name colliding with a surrounding label is a reused name, and refused. */
    @Test
    void anEmbeddedNameCollidingWithALabelIsRefused() {
        assertThatThrownBy(() -> new MatcherLibrary().compile(Matchers.sequence(
                Matchers.takeWhile("[a-z]").label("key"),
                Matchers.regex("(?<key>[0-9]+)"))))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("key");
    }

    /** A terminator outside the basic multilingual plane is one character, not a char pair. */
    @Test
    void takeUntilAcceptsASupplementaryTerminator() {
        final BytePattern pattern = new MatcherLibrary().compile(
                Matchers.takeUntil(0x1F600).label("prefix"));
        assertThat(pattern.pattern()).isEqualTo("takeUntil(😀) as prefix");

        final ByteMatcher matcher = pattern.matcher();
        assertThat(matcher.find(bytes("abc😀def"))).isTrue();
        assertThat(matcher.group("prefix").toString()).isEqualTo("abc");
    }

    @Test
    void reportsAnUndefinedReference() {
        assertThatThrownBy(() -> new MatcherLibrary().compile(Matchers.ref("missing")))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("no matcher named 'missing'");
    }

    @Test
    void reportsAReferenceCycle() {
        // Inlining is what keeps the engine recursion-free, so a cycle has to be a compile error
        // rather than something the runtime discovers.
        final MatcherLibrary library = new MatcherLibrary()
                .define("a", Matchers.sequence(Matchers.tag("x"), Matchers.ref("b")))
                .define("b", Matchers.sequence(Matchers.tag("y"), Matchers.ref("a")));

        assertThatThrownBy(() -> library.compile("a"))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("refers to itself");
    }

    @Test
    void reportsDirectSelfReference() {
        final MatcherLibrary library = new MatcherLibrary()
                .define("loop", Matchers.sequence(Matchers.tag("x"), Matchers.ref("loop")));

        assertThatThrownBy(() -> library.compile("loop"))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("refers to itself");
    }

    /** A name used twice in one composition is inlining, not a cycle. */
    @Test
    void allowsTheSameReferenceTwice() {
        final MatcherLibrary library = new MatcherLibrary()
                .define("word", Matchers.takeWhile("[a-z]"));

        final ByteMatcher matcher = library
                .compile(Matchers.sequence(
                        Matchers.ref("word").label("first"),
                        Matchers.tag("-"),
                        Matchers.ref("word").label("second")))
                .matcher();
        assertThat(matcher.find(bytes("left-right"))).isTrue();
        assertThat(matcher.group("first").toString()).isEqualTo("left");
        assertThat(matcher.group("second").toString()).isEqualTo("right");
    }

    // -----------------------------------------------------------------------------------
    // The CSV field example from the design, end to end
    // -----------------------------------------------------------------------------------

    @Test
    void parsesQuotedAndUnquotedCsvFields() {
        final MatcherLibrary library = Matchers.standardLibrary();
        final ByteMatcher matcher = library.compile("csvField").matcher();

        assertThat(matcher.find(bytes("\"quoted, with comma\",next"))).isTrue();
        assertThat(matcher.groupString(0)).isEqualTo("\"quoted, with comma\"");

        assertThat(matcher.find(bytes("plain,next"))).isTrue();
        assertThat(matcher.groupString(0)).isEqualTo("plain");
    }
}
