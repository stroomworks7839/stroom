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

import stroom.shapeshifter.regex.Flag;
import stroom.shapeshifter.regex.comb.Matcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A regex as the composition it is (design 38 §3a): the parser's intermediate form mapped onto
 * the composition vocabulary, node for node, so that a regex an author brings in becomes the
 * tree the UI edits — and compiles to the <b>identical plan</b> the regex itself does, which
 * is the property the round trip is held to.
 *
 * <p>What the vocabulary names, the explode names: a literal is a {@code tag}, a class is a
 * character run, a concatenation a sequence, an alternation a choice, a quantifier a repeat, a
 * capturing group a label, a lookahead a peek or a not. The flags are absorbed rather than
 * carried: a case-insensitive literal is already a two-member class when it leaves the parser,
 * and a dot under {@code s} is already every character, so the tree needs no flags to mean
 * what the regex meant. What the vocabulary cannot name stays a {@code regex} leaf that says
 * what it is: an anchor, a word boundary, a backreference, a raw byte. Two constructs have no
 * leaf of their own because a leaf needs source text and the intermediate form has none — an
 * atomic group and a lookbehind — and a regex containing either explodes as one leaf, whole,
 * until the printer §3a describes exists. So an explode always succeeds, and is exactly as
 * structured as the vocabulary allows.
 *
 * <p>Meaning is preserved and text is not: {@code [a-z]{1,}} comes back as {@code [a-z]+}'s
 * run, {@code (?i)abc} as three two-member classes, an unnamed group as a label named by its
 * number. The identical-plan pin is what makes that an acceptable trade.
 */
public final class Explode {

    /** A construct with no mapping and no source text to keep as a leaf. */
    private static final class Unmappable extends RuntimeException {

        Unmappable() {
            super(null, null, false, false);
        }
    }

    private Explode() {
    }

    /** The composition a regex is, or the regex itself as one leaf if it holds what cannot be mapped. */
    public static Matcher explode(final String pattern, final Set<Flag> flags) {
        final Parser.Result parsed = Parser.parse(pattern, flags);
        try {
            return node(parsed.root());
        } catch (final Unmappable ignored) {
            return new Matcher.Regex(pattern, flags);
        }
    }

    private static Matcher node(final Hir hir) {
        return switch (hir) {
            case final Hir.Empty ignored -> new Matcher.Sequence(List.of());
            case final Hir.Bytes bytes -> literal(bytes);
            case final Hir.CharClass charClass -> characters(charClass, 1, 1);
            case final Hir.Concat concat -> sequence(concat.items());
            case final Hir.Alt alt -> new Matcher.Choice(alt.branches().stream().map(Explode::node).toList());
            case final Hir.Repeat repeat -> repeat.body() instanceof final Hir.CharClass charClass && repeat.greedy()
                    ? characters(charClass, repeat.min(), repeat.max())
                    : new Matcher.Repeat(node(repeat.body()), repeat.min(),
                            repeat.isUnbounded() ? Matcher.Repeat.UNBOUNDED : repeat.max(), repeat.greedy());
            // A capturing group is a label; an unnamed one is named by its number, which is how
            // its author reached it. A non-capturing group is only grouping, and the tree has
            // structure without it.
            case final Hir.Group group -> group.capturing()
                    ? new Matcher.Labelled(node(group.body()),
                            group.name() != null ? group.name() : "_" + group.index())
                    : node(group.body());
            case final Hir.Look look -> {
                if (look.behind()) {
                    throw new Unmappable();
                }
                yield look.negated() ? new Matcher.Not(node(look.body())) : new Matcher.Peek(node(look.body()));
            }
            case final Hir.Assertion assertion -> leaf(spell(assertion.kind()));
            // The flags a backreference compares under are its own, so they are spelled on it;
            // u only decides how a fold compares, so it is spelled only with i — and never under
            // RAW, which refuses it, where there is no folding to ask for.
            case final Hir.Backref backref -> leaf(backref.caseInsensitive()
                    ? "(?i" + (backref.unicode() ? "u" : "-u") + ":\\" + backref.index() + ")"
                    : "(?-i:\\" + backref.index() + ")");
            case final Hir.Atomic ignored -> throw new Unmappable();
        };
    }

    private static String spell(final Hir.Kind kind) {
        return switch (kind) {
            case START_INPUT -> "^";
            case END_INPUT -> "$";
            case START_LINE -> "(?m:^)";
            case END_LINE -> "(?m:$)";
            case WORD_BOUNDARY -> "\\b";
            case NOT_WORD_BOUNDARY -> "\\B";
            case WORD_BOUNDARY_ASCII -> "(?-u:\\b)";
            case NOT_WORD_BOUNDARY_ASCII -> "(?-u:\\B)";
            case PREVIOUS_MATCH_END -> "\\G";
        };
    }

    private static Matcher leaf(final String regex) {
        return new Matcher.Regex(regex, Set.of());
    }

    /**
     * A literal is a tag when its bytes are the text they came from; a raw byte escape is not
     * text, and stays the escape it was written as.
     */
    private static Matcher literal(final Hir.Bytes bytes) {
        final String text = new String(bytes.value(), StandardCharsets.UTF_8);
        return Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes.value()) && text.equals(bytes.label())
                ? new Matcher.Tag(text)
                : leaf(bytes.label());
    }

    /**
     * A class is a run of one; a single-character class that is that character is a tag, which
     * is what the parser made of a one-byte literal. The class expression is the one the author
     * wrote when it still means the same set — it does not under {@code i} or {@code s}, or for
     * a shorthand whose meaning the flags changed — and otherwise the set itself, written out.
     */
    private static Matcher characters(final Hir.CharClass charClass, final int min, final int max) {
        final CodePointSet set = charClass.set();
        final int single = set.singleCodePoint();
        if (min == 1 && max == 1 && single >= 0 && charClass.label().equals(new String(Character.toChars(single)))) {
            return new Matcher.Tag(charClass.label());
        }
        final String expression = sameSet(charClass.label(), set) ? charClass.label() : render(set);
        return new Matcher.Characters(expression, min, max == Hir.Repeat.UNBOUNDED ? Matcher.Repeat.UNBOUNDED : max);
    }

    private static boolean sameSet(final String expression, final CodePointSet set) {
        try {
            return Arrays.equals(Parser.parseClassExpression(expression).ranges(), set.ranges());
        } catch (final RuntimeException notAClass) {
            return false;
        }
    }

    /** A set written out as ranges, in the escape every class accepts. */
    private static String render(final CodePointSet set) {
        if (Arrays.equals(set.ranges(), CodePointSet.all().ranges())) {
            return "[\\s\\S]";
        }
        final StringBuilder sb = new StringBuilder("[");
        final int[] ranges = set.ranges();
        for (int i = 0; i < ranges.length; i += 2) {
            sb.append(member(ranges[i]));
            if (ranges[i + 1] != ranges[i]) {
                sb.append('-').append(member(ranges[i + 1]));
            }
        }
        return sb.append(']').toString();
    }

    /**
     * Printable ASCII as itself, except what a class reads as syntax: {@code [:} opens a POSIX
     * class and {@code &&} is refused, so both are escaped wherever they fall.
     */
    private static String member(final int codePoint) {
        return codePoint > 0x20 && codePoint < 0x7F && "\\[]^-:&".indexOf(codePoint) < 0
                ? String.valueOf((char) codePoint)
                : String.format("\\x{%X}", codePoint);
    }

    /** Adjacent one-character tags are one tag, as the normaliser folds them into one literal. */
    private static Matcher sequence(final List<Hir> items) {
        final List<Matcher> out = new ArrayList<>(items.size());
        for (final Hir item : items) {
            final Matcher next = node(item);
            if (next instanceof final Matcher.Tag tag && !out.isEmpty()
                && out.getLast() instanceof final Matcher.Tag previous) {
                out.set(out.size() - 1, new Matcher.Tag(previous.text() + tag.text()));
            } else {
                out.add(next);
            }
        }
        return out.size() == 1 ? out.getFirst() : new Matcher.Sequence(out);
    }
}
