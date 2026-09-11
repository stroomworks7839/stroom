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

package stroom.shapeshifter.engine.graph;

import stroom.shapeshifter.engine.value.Numbers;
import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A regex {@code replace}, compiled: its pattern, its matcher and its replacement already
 * worked out (design 29 §3.5, D51).
 *
 * <p>Design 10's change 1 gave a compiled match its matcher as a field and measured what that
 * was worth; the body side never got it, and a replace allocated one per call — 209 of them per
 * record on {@code apache_httpd}. The replacement was worse: its {@code $1}, {@code ${name}} and
 * {@code $$} syntax was re-parsed character by character on every <i>match</i>, and every group
 * reference re-resolved from its name. None of that depends on the input.
 *
 * <p>So the parse happens once and leaves a list of pieces, and a group's name becomes its index
 * while the pattern is in hand. What is left per match is appending text and asking the matcher
 * for groups.
 *
 * <p>The matcher is a field, so a replacer belongs to one compiled instruction and runs under
 * the graph's one-run-at-a-time contract, exactly as {@link CompiledMatch.Regex} does.
 *
 * <p>It lives with the graph, not with the values, because that is what it is: the compiled form
 * of a {@code replace} instruction, held by {@link CompiledOp.Replace} and built by the compiler.
 * It was in {@code value} until 2026-09-10, where the package said something about it that was
 * not true.
 *
 * <p>It does reach back for one thing — {@link Numbers#index} reads a replacement's {@code $1} as
 * a number, and doing that correctly is more than {@code Integer.parseInt} (that class explains
 * why). {@code graph} depends on {@code value} anyway, for the typed values compiled nodes carry;
 * what moving this cost was making {@code Numbers} public, which is the honest price and is
 * recorded rather than hidden.
 */
public final class Replacer {

    /**
     * One piece of a parsed replacement: literal text, or a group to expand.
     *
     * @param text  the literal, or null when this is a group reference
     * @param group the group's index, or −1 for a reference that names no group in this
     *              pattern — which expands to nothing, as an absent group does
     */
    private record Piece(String text, int group) {

    }

    private final ByteMatcher matcher;
    private final Piece[] pieces;

    /** Compile a replacement against the pattern whose groups it names. */
    public Replacer(final BytePattern pattern, final String replacement) {
        this.matcher = pattern.matcher();
        this.pieces = parse(replacement, pattern);
    }

    /** The pattern this replacer runs, which is the one the compiler resolved for it. */
    public BytePattern pattern() {
        return matcher.pattern();
    }

    /**
     * Replace every match of the pattern, expanding group references in the replacement.
     *
     * <p>The expansion syntax is the regex module's dialect — Rust regex's replacement syntax,
     * not {@code java.util.regex}'s. {@code $1} and {@code ${1}} are groups, {@code $name} and
     * {@code ${name}} are named groups, and {@code $$} is a literal dollar. A reference to a
     * group that did not participate expands to nothing rather than failing, which matters when
     * the pattern has optional parts.
     *
     * <p>An empty match advances by one character rather than looping, which is the difference
     * between replacing every position and never finishing.
     */
    public String replace(final String input) {
        final byte[] data = input.getBytes(StandardCharsets.UTF_8);
        final StringBuilder result = new StringBuilder(input.length());

        int cursor = 0;
        while (cursor <= data.length) {
            if (!matcher.match(data, cursor, data.length, Anchoring.UNANCHORED)) {
                break;
            }
            final int start = matcher.start();
            final int end = matcher.end();
            result.append(new String(data, cursor, start - cursor, StandardCharsets.UTF_8));
            expand(result);

            if (end == start) {
                // Zero-width match: emit the character it sat before, or stop at the end.
                if (end >= data.length) {
                    cursor = end;
                    break;
                }
                final int next = nextCharacter(data, end);
                result.append(new String(data, end, next - end, StandardCharsets.UTF_8));
                cursor = next;
            } else {
                cursor = end;
            }
        }
        if (cursor < data.length) {
            result.append(new String(data, cursor, data.length - cursor, StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    /** Append this match's expansion of the replacement. */
    private void expand(final StringBuilder out) {
        for (final Piece piece : pieces) {
            if (piece.text() != null) {
                out.append(piece.text());
            } else if (piece.group() >= 0 && matcher.matchedGroup(piece.group())) {
                out.append(matcher.groupString(piece.group()));
            }
        }
    }

    /**
     * Split a replacement into literals and group references.
     *
     * <p>Every way of writing a {@code $} that is not a reference — a trailing one, an unclosed
     * brace, one before a character that cannot start a name — is a literal dollar, and stays
     * one here rather than being decided again per match.
     */
    private static Piece[] parse(final String replacement, final BytePattern pattern) {
        final List<Piece> pieces = new ArrayList<>();
        final StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < replacement.length()) {
            final char c = replacement.charAt(i);
            if (c != '$') {
                literal.append(c);
                i++;
                continue;
            }
            if (i + 1 >= replacement.length()) {
                literal.append('$');
                break;
            }
            if (replacement.charAt(i + 1) == '$') {
                literal.append('$');
                i += 2;
                continue;
            }

            final String name;
            if (replacement.charAt(i + 1) == '{') {
                final int close = replacement.indexOf('}', i + 2);
                if (close < 0) {
                    literal.append('$');
                    i++;
                    continue;
                }
                name = replacement.substring(i + 2, close);
                i = close + 1;
            } else {
                int end = i + 1;
                while (end < replacement.length() && isNameCharacter(replacement.charAt(end))) {
                    end++;
                }
                if (end == i + 1) {
                    literal.append('$');
                    i++;
                    continue;
                }
                name = replacement.substring(i + 1, end);
                i = end;
            }
            if (!literal.isEmpty()) {
                pieces.add(new Piece(literal.toString(), -1));
                literal.setLength(0);
            }
            pieces.add(new Piece(null, group(pattern, name)));
        }
        if (!literal.isEmpty()) {
            pieces.add(new Piece(literal.toString(), -1));
        }
        return pieces.toArray(new Piece[0]);
    }

    /**
     * The group a reference names, or −1 for one this pattern does not have.
     *
     * <p>A numeric reference names its group directly; anything else is a named group. Resolved
     * here rather than per match (E26 asked only that it not throw per match; holding the
     * pattern means it need not be asked at all).
     */
    private static int group(final BytePattern pattern, final String name) {
        int index = Numbers.index(name);
        if (index < 0) {
            index = pattern.groupIndex(name);
        }
        return index < 0 || index > pattern.groupCount() ? -1 : index;
    }

    private static boolean isNameCharacter(final char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** The next character's start, so a zero-width match advances by a character not a byte. */
    private static int nextCharacter(final byte[] data, final int from) {
        int next = from + 1;
        while (next < data.length && (data[next] & 0xC0) == 0x80) {
            next++;
        }
        return next;
    }
}
