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

import stroom.shapeshifter.regex.Flag;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Factory methods for composing matchers.
 * <p>
 * The vocabulary follows nom's, which is proven and familiar. What differs is the execution
 * model — see {@link Matcher} — and that a regex is a first-class element rather than something
 * to be avoided. Pure combinators get verbose at the character level, where a class or a
 * quantifier says it better; having both means each covers the other's weakness.
 *
 * <pre>{@code
 * Matcher quotedField = Matchers.sequence(
 *         Matchers.tag("\""),
 *         Matchers.takeUntil('"').label("content"),
 *         Matchers.tag("\""));
 *
 * Matcher field = Matchers.choice(quotedField, Matchers.takeUntil(',').label("plain"));
 * BytePattern pattern = new MatcherLibrary().compile(field);
 * }</pre>
 */
public final class Matchers {

    private Matchers() {
    }

    // -----------------------------------------------------------------------------------
    // Atoms
    // -----------------------------------------------------------------------------------

    /** An exact literal. */
    public static Matcher tag(final String text) {
        return new Matcher.Tag(text);
    }

    /**
     * One or more characters from a class, written as a regex class expression such as
     * {@code [a-z_]}, {@code [^,]} or {@code \d}.
     */
    public static Matcher takeWhile(final String classExpression) {
        return new Matcher.Characters(classExpression, 1, Matcher.Repeat.UNBOUNDED);
    }

    /** Zero or more characters from a class. */
    public static Matcher takeWhileOrNone(final String classExpression) {
        return new Matcher.Characters(classExpression, 0, Matcher.Repeat.UNBOUNDED);
    }

    /** Everything up to, but not including, the next occurrence of {@code terminator}. */
    public static Matcher takeUntil(final char terminator) {
        return new Matcher.Until(terminator, false);
    }

    /** Everything up to and including the next occurrence of {@code terminator}. */
    public static Matcher takeThrough(final char terminator) {
        return new Matcher.Until(terminator, true);
    }

    /** Exactly {@code count} characters, whatever they are. */
    public static Matcher takeN(final int count) {
        return new Matcher.Characters("[\\s\\S]", count, count);
    }

    /** A single character, whatever it is. */
    public static Matcher anyChar() {
        return takeN(1);
    }

    /** A whole regex as one element; its capture groups are preserved. */
    public static Matcher regex(final String pattern) {
        return new Matcher.Regex(pattern, EnumSet.noneOf(Flag.class));
    }

    public static Matcher regex(final String pattern, final Flag... flags) {
        return new Matcher.Regex(pattern, flags.length == 0
                ? EnumSet.noneOf(Flag.class)
                : EnumSet.of(flags[0], flags));
    }

    /** A reference to a matcher defined in a {@link MatcherLibrary}. */
    public static Matcher ref(final String name) {
        return new Matcher.Ref(name);
    }

    // -----------------------------------------------------------------------------------
    // Combinators
    // -----------------------------------------------------------------------------------

    /** All elements, in order. */
    public static Matcher sequence(final Matcher... items) {
        return new Matcher.Sequence(List.of(items));
    }

    /** Ordered alternatives — the first that matches wins. */
    public static Matcher choice(final Matcher... alternatives) {
        return new Matcher.Choice(List.of(alternatives));
    }

    /** Match once or not at all, preferring to match. */
    public static Matcher optional(final Matcher body) {
        return new Matcher.Repeat(body, 0, 1, true);
    }

    public static Matcher repeat(final Matcher body, final int min, final int max) {
        return new Matcher.Repeat(body, min, max, true);
    }

    /** One or more repetitions. */
    public static Matcher oneOrMore(final Matcher body) {
        return new Matcher.Repeat(body, 1, Matcher.Repeat.UNBOUNDED, true);
    }

    /** Zero or more repetitions. */
    public static Matcher zeroOrMore(final Matcher body) {
        return new Matcher.Repeat(body, 0, Matcher.Repeat.UNBOUNDED, true);
    }

    /** {@code open body close} — sugar for a sequence, which reads better at a call site. */
    public static Matcher delimited(final Matcher open, final Matcher body, final Matcher close) {
        return sequence(open, body, close);
    }

    /** A non-empty list: {@code element (separator element)*}. */
    public static Matcher separated(final Matcher element, final Matcher separator) {
        return sequence(element, zeroOrMore(sequence(separator, element)));
    }

    // -----------------------------------------------------------------------------------
    // A small library of the patterns that keep being rewritten by hand
    // -----------------------------------------------------------------------------------

    /** Registers the built-in patterns into a library under conventional names. */
    public static MatcherLibrary standardLibrary() {
        final MatcherLibrary library = new MatcherLibrary();
        library.define("digits", takeWhile("[0-9]"));
        library.define("word", takeWhile("[A-Za-z]"));
        library.define("identifier", regex("[A-Za-z_][A-Za-z0-9_]*"));
        library.define("whitespace", takeWhile("[ \\t]"));
        library.define("quotedString", delimited(tag("\""), takeUntil('"'), tag("\"")));
        library.define("ipv4", separated(ref("digits"), tag(".")));
        library.define("isoDate", regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"));
        library.define("isoDateTime", sequence(
                ref("isoDate"), tag("T"), regex("[0-9]{2}:[0-9]{2}:[0-9]{2}")));
        library.define("csvField", choice(ref("quotedString"), takeUntil(',')));
        return library;
    }
}
