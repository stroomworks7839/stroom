/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.client.model;

import stroom.shapeshifter.config.MatchExpression;

import java.util.List;

/// A template's match as the strip's Match line holds it, open (design 47 G): its kind, and that
/// kind's fields as they are spelt there. A regex is its pattern. A delimiter is its character
/// and the container that makes it literal, spelt as [ControlEscapes] spells them, so the
/// newline a delimiter starts as is a text box's `\n`. Every other kind of match has no
/// one-line spelling, and is the workbench's.
///
/// The line holds both kinds' fields at once, so a kind chosen and then given up comes back as
/// it was left. Only the chosen kind's are taken.
///
/// @param kind           The kind chosen: [MatchKind#REGEX] or [MatchKind#DELIMITER].
/// @param pattern        The regex's pattern.
/// @param delimiter      The delimiter, escaped.
/// @param containerStart What opens a container, escaped, or empty for none.
/// @param containerEnd   What closes it, escaped, or empty for none.
public record MatchLine(MatchKind kind,
                        String pattern,
                        String delimiter,
                        String containerStart,
                        String containerEnd) {

    /// The kinds the line offers, in the order its kind box lists them.
    public static final List<MatchKind> KINDS = List.of(MatchKind.REGEX, MatchKind.DELIMITER);

    /// Why a delimiter with no separator is refused, wherever it is typed.
    public static final String NO_DELIMITER = "A delimiter match needs its delimiter";

    /// A match as the line opens on it: its own kind's fields filled, the other's as that kind
    /// starts.
    ///
    /// @param match The template's match.
    /// @return The line, or null for a match with no one-line spelling.
    public static MatchLine of(final MatchExpression match) {
        if (match instanceof MatchExpression.Regex regex) {
            final MatchExpression.Delimiter blank = (MatchExpression.Delimiter) Templates.blank(MatchKind.DELIMITER);
            return new MatchLine(MatchKind.REGEX, regex.pattern(), ControlEscapes.escape(blank.delimiter()),
                    ControlEscapes.escape(blank.containerStart()), ControlEscapes.escape(blank.containerEnd()));
        }
        if (match instanceof MatchExpression.Delimiter delimiter) {
            return new MatchLine(MatchKind.DELIMITER, "", ControlEscapes.escape(delimiter.delimiter()),
                    ControlEscapes.escape(delimiter.containerStart()),
                    ControlEscapes.escape(delimiter.containerEnd()));
        }
        return null;
    }

    /// Whether a match has a spelling on the line: whether it is one of [#KINDS].
    ///
    /// @param match The template's match.
    /// @return Whether the line can open on it.
    public static boolean spells(final MatchExpression match) {
        return KINDS.contains(MatchKind.of(match));
    }

    /// A delimiter match from its fields as they are spelt, wherever they are typed: on the line,
    /// or in the workbench's delimiter form. A field left empty is none.
    ///
    /// @param separator      The separator, escaped.
    /// @param escape         The escape, escaped, or empty for none.
    /// @param containerStart What opens a container, escaped, or empty for none.
    /// @param containerEnd   What closes it, escaped, or empty for none.
    /// @return The match, or null where there is no separator, which means nothing.
    public static MatchExpression.Delimiter delimiter(final String separator,
                                                      final String escape,
                                                      final String containerStart,
                                                      final String containerEnd) {
        final String unescaped = ControlEscapes.unescape(separator);
        if (unescaped.isEmpty()) {
            return null;
        }
        return new MatchExpression.Delimiter(unescaped,
                blankToNull(ControlEscapes.unescape(escape)),
                blankToNull(ControlEscapes.unescape(containerStart)),
                blankToNull(ControlEscapes.unescape(containerEnd)));
    }

    /// The match the line makes of what was there. A regex keeps the flags and advance it had, and
    /// a delimiter the escape it had: those are the workbench's to change.
    ///
    /// @param was The template's match before.
    /// @return The match, or null where the line holds no delimiter, which means nothing.
    public MatchExpression toMatch(final MatchExpression was) {
        if (kind == MatchKind.REGEX) {
            return was instanceof MatchExpression.Regex regex
                    ? new MatchExpression.Regex(pattern, regex.flags(), regex.advance())
                    : new MatchExpression.Regex(pattern, null, 0);
        }
        return delimiter(delimiter,
                was instanceof MatchExpression.Delimiter old
                        ? ControlEscapes.escape(old.escape())
                        : "",
                containerStart,
                containerEnd);
    }

    /// Whether taking the line throws away something the author wrote: it is another kind, and
    /// the match it replaces is not that kind as it starts. Replacing an empty regex, or a
    /// delimiter on a newline, loses nothing, and is not asked about.
    ///
    /// @param was The template's match before.
    /// @return Whether to ask first.
    public boolean replacesWork(final MatchExpression was) {
        final MatchKind before = MatchKind.of(was);
        return before != kind && !was.equals(Templates.blank(before));
    }

    /// @param pattern The regex's pattern.
    /// @return The line with that pattern.
    public MatchLine withPattern(final String pattern) {
        return new MatchLine(kind, pattern, delimiter, containerStart, containerEnd);
    }

    /// @param kind The kind, or null where none is chosen.
    /// @return The line with that kind, or as it is where the kind is null.
    public MatchLine withKind(final MatchKind kind) {
        return kind == null
                ? this
                : new MatchLine(kind, pattern, delimiter, containerStart, containerEnd);
    }

    private static String blankToNull(final String text) {
        return text.isEmpty()
                ? null
                : text;
    }
}
