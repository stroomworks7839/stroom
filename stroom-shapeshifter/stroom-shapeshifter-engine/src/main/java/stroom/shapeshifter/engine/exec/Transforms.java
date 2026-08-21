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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The function library: the string operations a configuration can apply to a value.
 *
 * <p>They are pure, which is why they live here rather than in the executor. Every one takes the
 * resolved inputs and returns the result, or null to mean "produced nothing" — and producing
 * nothing is different from producing an empty string, because the caller writes one and skips
 * the other.
 */
public final class Transforms {

    private Transforms() {
    }

    /** Substitute, one search string at a time, in order. XSLT's {@code translate()}. */
    public static String translate(final List<String> inputs, final List<String> from, final List<String> to) {
        if (inputs.isEmpty()) {
            return "";
        }
        String result = inputs.getFirst();
        for (int i = 0; i < from.size(); i++) {
            result = result.replace(from.get(i), i < to.size() ? to.get(i) : "");
        }
        return result;
    }

    /**
     * Join what is there, skipping what is not.
     *
     * <p>Skipping empties rather than joining them is what stops a missing middle field turning
     * {@code a, b, c} into {@code a, , c}.
     */
    public static String stringJoin(final List<String> inputs, final String separator) {
        final List<String> present = inputs.stream().filter(input -> !input.isEmpty()).toList();
        return present.isEmpty() ? null : String.join(separator == null ? "" : separator, present);
    }

    /** Replace a literal. */
    public static String replaceLiteral(final List<String> inputs, final String pattern, final String replacement) {
        return inputs.isEmpty() ? null : inputs.getFirst().replace(pattern, replacement);
    }

    /** Lower-case, in the root locale so that the result does not depend on where it ran. */
    public static String lowerCase(final List<String> inputs) {
        return inputs.isEmpty() ? null : inputs.getFirst().toLowerCase(Locale.ROOT);
    }

    /** Upper-case, in the root locale. */
    public static String upperCase(final List<String> inputs) {
        return inputs.isEmpty() ? null : inputs.getFirst().toUpperCase(Locale.ROOT);
    }

    /** Collapse each run of whitespace to one space, and drop it at the ends. */
    public static String normalizeSpace(final List<String> inputs) {
        if (inputs.isEmpty()) {
            return null;
        }
        return String.join(" ", inputs.getFirst().trim().split("\\s+"));
    }

    /** Strip whitespace from both ends. */
    public static String trim(final List<String> inputs) {
        return inputs.isEmpty() ? null : inputs.getFirst().trim();
    }

    /**
     * Take part of a value, counted in characters rather than bytes.
     *
     * <p>Characters, because a configuration saying "the first eight" means eight of what a
     * person would count, and a multi-byte character would otherwise be cut in half.
     */
    public static String substring(final List<String> inputs, final int start, final Integer length) {
        if (inputs.isEmpty()) {
            return null;
        }
        final int[] codePoints = inputs.getFirst().codePoints().toArray();
        final int begin = Math.min(Math.max(0, start), codePoints.length);
        final int end = length == null
                ? codePoints.length
                : Math.min(begin + Math.max(0, length), codePoints.length);
        return new String(codePoints, begin, end - begin);
    }

    /** Split on a delimiter, one piece per line. */
    public static String tokenize(final List<String> inputs, final String delimiter) {
        if (inputs.isEmpty()) {
            return null;
        }
        return String.join("\n", inputs.getFirst().split(Pattern.quote(delimiter), -1));
    }

    /**
     * Read as a number, and render it back.
     *
     * <p>Whether it is read as a whole number or a fractional one depends on whether it is
     * written with a point, so {@code 5} stays {@code 5} rather than becoming {@code 5.0}.
     */
    public static String number(final List<String> inputs) {
        if (inputs.isEmpty()) {
            return null;
        }
        final String trimmed = inputs.getFirst().trim();
        try {
            return trimmed.contains(".")
                    ? Double.toString(Double.parseDouble(trimmed))
                    : Long.toString(Long.parseLong(trimmed));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------------------
    // Regex replacement
    // -----------------------------------------------------------------------------------

    /**
     * Replace every match of a pattern, expanding group references in the replacement.
     *
     * <p>The expansion syntax is the one the dialect's own users will expect — Rust's, not
     * {@code java.util.regex}'s. {@code $1} and {@code ${1}} are groups, {@code $name} and
     * {@code ${name}} are named groups, and {@code $$} is a literal dollar. A reference to a
     * group that did not participate expands to nothing rather than failing, which matters when
     * the pattern has optional parts.
     *
     * <p>An empty match advances by one character rather than looping, which is the difference
     * between replacing every position and never finishing.
     */
    public static String replaceRegex(final BytePattern pattern, final String input, final String replacement) {
        final byte[] data = input.getBytes(StandardCharsets.UTF_8);
        final ByteMatcher matcher = pattern.matcher();
        final StringBuilder result = new StringBuilder(input.length());

        int cursor = 0;
        while (cursor <= data.length) {
            if (!matcher.match(data, cursor, data.length, Anchoring.UNANCHORED)) {
                break;
            }
            final int start = matcher.start();
            final int end = matcher.end();
            result.append(new String(data, cursor, start - cursor, StandardCharsets.UTF_8));
            expand(replacement, matcher, pattern, result);

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

    /** Expand a replacement's {@code $} references against a match. */
    private static void expand(final String replacement,
                               final ByteMatcher matcher,
                               final BytePattern pattern,
                               final StringBuilder out) {
        int i = 0;
        while (i < replacement.length()) {
            final char c = replacement.charAt(i);
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= replacement.length()) {
                out.append('$');
                break;
            }
            if (replacement.charAt(i + 1) == '$') {
                out.append('$');
                i += 2;
                continue;
            }

            final String name;
            if (replacement.charAt(i + 1) == '{') {
                final int close = replacement.indexOf('}', i + 2);
                if (close < 0) {
                    out.append('$');
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
                    out.append('$');
                    i++;
                    continue;
                }
                name = replacement.substring(i + 1, end);
                i = end;
            }
            out.append(group(matcher, pattern, name));
        }
    }

    private static String group(final ByteMatcher matcher, final BytePattern pattern, final String name) {
        int index = -1;
        try {
            index = Integer.parseInt(name);
        } catch (final NumberFormatException e) {
            final int named = pattern.groupIndex(name);
            if (named >= 0) {
                index = named;
            }
        }
        if (index < 0 || index > pattern.groupCount() || !matcher.matchedGroup(index)) {
            return "";
        }
        return matcher.groupString(index);
    }

    private static boolean isNameCharacter(final char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Where the character starting at {@code from} ends, in UTF-8. */
    private static int nextCharacter(final byte[] data, final int from) {
        int next = from + 1;
        while (next < data.length && (data[next] & 0xC0) == 0x80) {
            next++;
        }
        return next;
    }
}
