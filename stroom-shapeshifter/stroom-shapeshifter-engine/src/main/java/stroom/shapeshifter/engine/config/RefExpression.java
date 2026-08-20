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

package stroom.shapeshifter.engine.config;

import java.util.List;

/**
 * A value built from captures and literal text — the configuration's expression language.
 *
 * <p>An expression is a sequence of parts, concatenated. A part is either literal text or a
 * reference to a capture: a group of the match being processed, or a group of some named
 * variable captured earlier and possibly further up the tree.
 *
 * <p>In modern configurations these are built structurally, by an editor, and never parsed from
 * text. The {@code $0}, {@code $var$1} syntax survives only in DS3 XML, and is parsed during
 * import — see the {@code ds3} package.
 *
 * @param parts the parts, concatenated in order
 */
public record RefExpression(List<RefPart> parts) {

    public RefExpression {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /** An expression that is nothing but literal text. */
    public static RefExpression text(final String text) {
        return new RefExpression(List.of(new RefPart.Text(text)));
    }

    /** An expression that is one group of the current match. */
    public static RefExpression group(final int group) {
        return new RefExpression(List.of(new RefPart.Capture(null, group, null)));
    }

    /** True if this expression is a single literal. */
    public boolean isText() {
        return parts.size() == 1 && parts.getFirst() instanceof RefPart.Text;
    }

    /** One part of a {@link RefExpression}. */
    public sealed interface RefPart {

        /**
         * A reference to a captured value.
         *
         * @param varId      the named variable to read from, or null for the current match
         * @param group      the capture group; 0 is the whole match
         * @param matchIndex which of a multi-valued variable's entries to read, or null for the
         *                   most recent
         */
        record Capture(String varId, int group, MatchIndex matchIndex) implements RefPart {

        }

        /** Literal text. */
        record Text(String value) implements RefPart {

        }
    }

    /**
     * Which entry of a multi-valued variable a reference means.
     *
     * <p>A variable captured inside a repeating match accumulates one value per match, so a
     * reference to it has to say which. Absolute ({@code [3]}), relative to the current match
     * ({@code [+1]}), the last populated entry, or an index read from another variable at
     * runtime — which is how the engine's own {@code __match_count} threads a parent's position
     * into a child's lookup.
     *
     * @param index    the index, ignored when {@code varRef} is set
     * @param isOffset whether {@code index} is relative to the current match rather than absolute
     * @param isLast   whether this means the last populated entry, ignoring {@code index}
     * @param varRef   a variable whose value is the index, or null
     */
    public record MatchIndex(int index, boolean isOffset, boolean isLast, String varRef) {

    }
}
