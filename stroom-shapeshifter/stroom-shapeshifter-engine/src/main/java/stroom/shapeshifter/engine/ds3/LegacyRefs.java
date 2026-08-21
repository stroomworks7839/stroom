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

package stroom.shapeshifter.engine.ds3;

import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;

import java.util.ArrayList;
import java.util.List;

/**
 * DS3's {@code $}-syntax for referring to captured values.
 *
 * <p>Only import uses this. Modern configurations build expressions structurally — an editor
 * knows which group it means and says so — so nothing else in the engine parses reference text,
 * and nothing new should.
 *
 * <p>The syntax, such as it is:
 * <ul>
 *   <li>{@code $1} — capture group 1 of the current match; {@code $} alone is group 0</li>
 *   <li>{@code $heading$1} — group 1 of the variable {@code heading}</li>
 *   <li>{@code $1[+1]} — with a match index, absolute or relative</li>
 *   <li>{@code @name.2[+1]} — the same thing said the other way round</li>
 *   <li>{@code 'text'} — a literal, with {@code ''} for a quote</li>
 *   <li>{@code $1+'/'+$2} — parts, concatenated</li>
 * </ul>
 *
 * <p>Anything not starting with {@code $}, {@code '} or {@code @} is a plain literal, which is
 * why {@code <data name="user">} needs no quoting.
 */
public final class LegacyRefs {

    private LegacyRefs() {
    }

    /** Parse a DS3 reference. */
    public static RefExpression parse(final String reference) {
        if (reference == null || reference.isEmpty()) {
            return new RefExpression(List.of());
        }
        final char first = reference.charAt(0);
        if (first != '$' && first != '\'' && first != '@') {
            return new RefExpression(List.of(new RefPart.Text(reference)));
        }

        final List<RefPart> parts = new ArrayList<>();
        boolean inQuote = false;
        boolean inArray = false;
        int start = 0;

        for (int i = 0; i < reference.length(); i++) {
            final char c = reference.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '[') {
                    inArray = true;
                } else if (inArray) {
                    if (c == ']') {
                        inArray = false;
                    }
                } else if (c == '+') {
                    parts.add(section(reference, reference.substring(start, i)));
                    start = i + 1;
                }
            }
        }
        if (start < reference.length()) {
            parts.add(section(reference, reference.substring(start)));
        }
        return new RefExpression(parts);
    }

    private static RefPart section(final String reference, final String section) {
        if (section.isEmpty()) {
            return new RefPart.Text("");
        }
        return switch (section.charAt(0)) {
            case '$' -> dollar(reference, section);
            case '@' -> at(reference, section);
            default -> new RefPart.Text(stripQuotes(section));
        };
    }

    /** {@code $group}, {@code $var$group}, either with an optional {@code [index]}. */
    private static RefPart dollar(final String reference, final String section) {
        boolean inArray = false;
        int pos = 1;
        String varId = null;
        int group = -1;
        MatchIndex matchIndex = null;

        for (int i = 1; i < section.length(); i++) {
            final char c = section.charAt(i);
            if (c == '[') {
                if (pos < i) {
                    group = number(section.substring(pos, i), reference);
                }
                inArray = true;
                pos = i + 1;
            } else if (inArray) {
                if (c == ']') {
                    inArray = false;
                    matchIndex = matchIndex(reference, section.substring(pos, i));
                    pos = i + 1;
                }
            } else if (c == '$') {
                if (pos == 1) {
                    varId = section.substring(pos, i);
                    if (varId.isEmpty()) {
                        throw new ConfigException("Reference has an empty variable name: " + reference);
                    }
                }
                pos = i + 1;
            }
        }

        if (inArray) {
            throw new ConfigException("Reference has an unclosed '[': " + reference);
        }
        if (matchIndex != null && pos < section.length()) {
            throw new ConfigException("Reference has trailing text after ']': " + reference);
        }
        if (group == -1) {
            group = pos < section.length() ? number(section.substring(pos), reference) : 0;
        }
        return new RefPart.Capture(varId, group, matchIndex);
    }

    /** {@code @name}, {@code @name.group}, {@code @name.group[index]}. */
    private static RefPart at(final String reference, final String section) {
        int dot = -1;
        int bracket = -1;
        for (int i = 1; i < section.length(); i++) {
            final char c = section.charAt(i);
            if (c == '.' && dot < 0 && bracket < 0) {
                dot = i;
            } else if (c == '[' && bracket < 0) {
                bracket = i;
            }
        }

        final int nameEnd = dot >= 0 ? dot : (bracket >= 0 ? bracket : section.length());
        final String name = section.substring(1, nameEnd);
        if (name.isEmpty()) {
            throw new ConfigException("Reference has no variable name: " + reference);
        }

        final int group = dot < 0
                ? 0
                : number(section.substring(dot + 1, bracket >= 0 ? bracket : section.length()), reference);

        MatchIndex matchIndex = null;
        if (bracket >= 0) {
            final int close = section.lastIndexOf(']');
            // close < bracket also covers close < 0: a ']' before the '[' is no closer either.
            if (close < bracket) {
                throw new ConfigException("Reference has an unclosed '[': " + reference);
            }
            matchIndex = matchIndex(reference, section.substring(bracket + 1, close));
        }
        return new RefPart.Capture(name, group, matchIndex);
    }

    /** {@code [3]} is the third; {@code [+1]} and {@code [-1]} are relative to this match. */
    private static MatchIndex matchIndex(final String reference, final String text) {
        if (text.isEmpty()) {
            throw new ConfigException("Reference has an empty match index: " + reference);
        }
        final boolean relative = text.charAt(0) == '+' || text.charAt(0) == '-';
        final String digits = text.charAt(0) == '+' ? text.substring(1) : text;
        return new MatchIndex(number(digits, reference), relative, false, null);
    }

    private static int number(final String text, final String reference) {
        try {
            return Integer.parseInt(text);
        } catch (final NumberFormatException e) {
            throw new ConfigException("Reference has a malformed number '" + text + "': " + reference, e);
        }
    }

    /** Take the quotes off a literal, turning a doubled quote back into one. */
    private static String stripQuotes(final String text) {
        if (text.isEmpty() || text.charAt(0) != '\'') {
            return text;
        }
        final StringBuilder result = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    result.append('\'');
                    i += 2;
                } else {
                    i++;
                }
            } else {
                result.append(text.charAt(i));
                i++;
            }
        }
        return result.toString();
    }
}
