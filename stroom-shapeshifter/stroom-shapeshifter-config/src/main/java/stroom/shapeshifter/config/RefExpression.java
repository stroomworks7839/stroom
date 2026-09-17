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

package stroom.shapeshifter.config;

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
        return parts.size() == 1 && parts.get(0) instanceof RefPart.Text;
    }

    /**
     * The name this is, when it is exactly a bare read of one — no index, no other part — else
     * null. A bare name is what a collection site means by its target (design 35 §5).
     */
    public String bareName() {
        return parts.size() == 1
               && parts.get(0) instanceof RefPart.Capture capture
               && capture.varId() != null && capture.matchIndex() == null
                ? capture.varId()
                : null;
    }

    /** One part of a {@link RefExpression}. */
    public sealed interface RefPart permits RefPart.Capture, RefPart.Text, RefPart.Accessor, RefPart.Counter {

        /**
         * A reference to a captured value.
         *
         * @param varId      the named variable to read from, or null for the current match
         * @param group      the capture group; 0 is the whole match
         * @param matchIndex which of a multi-valued variable's entries to read, or null for the
         *                   most recent
         */
        record Capture(String varId, int group, MatchIndex matchIndex, String label) implements RefPart {

            // Spelt out because GWT 2.13 loses the body of an implicit canonical constructor that
            // another constructor delegates to, and fails compiling the delegation (design 43 §2).
            public Capture {
            }

            /** A group by number, or a variable. */
            public Capture(final String varId, final int group, final MatchIndex matchIndex) {
                this(varId, group, matchIndex, null);
            }

            /** A labelled group of the current match, by name (design 38 §4). */
            public static Capture label(final String label) {
                return new Capture(null, 0, null, label);
            }
        }

        /** Literal text. */
        record Text(String value) implements RefPart {

        }

        /**
         * A function over a collection, in an expression (design 35 §5's accessors and folds,
         * named after XPath 3.1's {@code array:} and {@code map:} libraries): {@code get},
         * {@code size}, {@code contains}, {@code last}, {@code head}, {@code keys},
         * {@code values}, {@code sum}, {@code avg}, {@code min}, {@code max}. Nothing here
         * mutates; a mutation is a statement in a body.
         *
         * @param of     the collection, as a reference — a declared name, or another accessor,
         *               which is how nesting is read one level at a time
         * @param key    {@code get}'s position or key and {@code contains}'s value, else null
         * @param orElse {@code get}'s default when there is no such entry, or null
         * @param as     {@code min} and {@code max}'s ordering cast, or null for string form
         */
        record Accessor(Kind kind, RefExpression of, RefExpression key, RefExpression orElse, Cast as)
                implements RefPart {

            public Accessor {
                if (kind == null || of == null || of.parts().isEmpty()) {
                    throw new ConfigException("An accessor needs a kind and a collection to read");
                }
                if ((kind == Kind.GET || kind == Kind.CONTAINS) == (key == null)) {
                    throw new ConfigException(kind.spelling() + (key == null
                            ? " needs a key" : " takes no key"));
                }
                if (orElse != null && kind != Kind.GET) {
                    throw new ConfigException(kind.spelling() + " takes no default");
                }
                if (as != null && kind != Kind.MIN && kind != Kind.MAX) {
                    throw new ConfigException(kind.spelling() + " takes no as");
                }
            }

            /** The accessors and folds, each with the spelling a configuration uses. */
            public enum Kind {
                GET, SIZE, CONTAINS, LAST, HEAD, KEYS, VALUES, SUM, AVG, MIN, MAX;

                public String spelling() {
                    return name().toLowerCase(java.util.Locale.ROOT);
                }
            }
        }

        /**
         * One of the engine's functions — {@code matchCount()}, {@code index()} and the rest
         * (design 35 §6) — with an index rule for the one that answers a sequence.
         */
        record Counter(EngineVars counter, MatchIndex matchIndex) implements RefPart {

            public Counter {
                if (counter == null) {
                    throw new ConfigException("A function part needs one of: " + EngineVars.spellings());
                }
            }
        }
    }

    /**
     * Which entry of a multi-valued variable a reference means.
     *
     * <p>A variable captured inside a repeating match accumulates one value per match, so a
     * reference to it has to say which. Absolute ({@code [3]}), relative to the current match
     * ({@code [+1]}), the last populated entry, or an index read from another variable at
     * runtime — which is how the engine's own {@code matchCount()} threads a parent's position
     * into a child's lookup.
     *
     * @param index    the index, ignored when {@code varRef} is set
     * @param isOffset whether {@code index} is relative to the current match rather than absolute
     * @param isLast   whether this means the last populated entry, ignoring {@code index}
     * @param varRef   a variable whose value is the index, or null
     * @param counter  one of the engine's functions whose value is the index, or null; at most
     *                 one of {@code varRef} and {@code counter} is set
     */
    public record MatchIndex(int index, boolean isOffset, boolean isLast, String varRef,
                             EngineVars counter) {

        public MatchIndex {
            if (varRef != null && counter != null) {
                throw new ConfigException("An index rule reads its index from a variable or from a"
                        + " function, not both: '" + varRef + "' and " + counter.spelling());
            }
        }
    }
}
