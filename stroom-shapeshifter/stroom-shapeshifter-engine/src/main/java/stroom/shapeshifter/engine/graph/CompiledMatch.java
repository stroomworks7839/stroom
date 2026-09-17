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

import stroom.shapeshifter.config.BinaryCast;
import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.LeadingAnchor;

/**
 * A match expression with everything it needs already worked out.
 *
 * <p>The authored {@link stroom.shapeshifter.config.MatchExpression} says what to match;
 * this says how, with patterns compiled and delimiters already encoded. The match loop should
 * not be deciding anything a compiler could have decided once.
 */
public sealed interface CompiledMatch {

    /**
     * A pattern tree lowered to one plan (design 38): the regex, and per group the binary cast
     * a labelled node carried — null where a group is plain bytes. The match is the regex arm's;
     * the casts are applied to the groups it binds.
     */
    final class Pattern implements CompiledMatch {

        private final Regex regex;
        private final BinaryCast[] casts;
        private final boolean anyCast;

        public Pattern(final BytePattern pattern, final BinaryCast[] casts) {
            this.regex = new Regex(pattern, 0);
            this.casts = casts;
            boolean any = false;
            for (final BinaryCast cast : casts) {
                any |= cast != null;
            }
            this.anyCast = any;
        }

        public Regex regex() {
            return regex;
        }

        /** The cast of a group, or null. */
        public BinaryCast cast(final int group) {
            return casts[group];
        }

        /** Whether any group carries a cast — the common case is none, and then nothing is walked. */
        public boolean anyCast() {
            return anyCast;
        }
    }

    /**
     * A match sequence (design 38 §3b): patterns and the three framing verbs, run in order by
     * the level with no backtracking across parts. Groups number across the parts in order — a
     * pattern's after its own group 0 dropped, a take's or a read's as one — after the whole sequence's
     * group 0.
     */
    record Parts(CompiledPart[] parts, int groupCount) implements CompiledMatch {

    }

    /** One part of a match sequence. */
    sealed interface CompiledPart {

        /** A pattern part; its groups 1.. land at {@code groupOffset + 1}.. in the sequence's groups. */
        record Pattern(CompiledMatch.Pattern pattern, int groupOffset) implements CompiledPart {

        }

        /**
         * A pattern part that is one literal — a bare {@code tag}, labelled or not — compiled
         * to the bytes it must find at the cursor and nothing else: no pattern, no matcher, a
         * byte compare (design 41 §6). The protobuf fixture's fields each begin with a one-byte
         * tag, and each cost a regex call to read it; a template that does not apply cost one
         * to find out. {@code group} is the label's group, or 0 for none.
         */
        record Literal(byte[] bytes, int group) implements CompiledPart {

        }

        /** Consume a length of bytes as the sequence's group {@code group}. */
        record Take(CompiledLength length, int group) implements CompiledPart {

        }

        record Seek(CompiledLength length, boolean absolute) implements CompiledPart {

        }

        /** Read a value at the cursor as the sequence's group {@code group}, by the cast's own width (design 39). */
        record Read(BinaryCast cast, int group) implements CompiledPart {

        }
    }

    /** Where a take's or a seek's amount comes from at run time. */
    sealed interface CompiledLength {

        record Literal(int count) implements CompiledLength {

        }

        /** A sequence group matched by an earlier part, read as an integer. */
        record Group(int group) implements CompiledLength {

        }

        record Var(VarName name) implements CompiledLength {

        }
    }

    /**
     * A compiled pattern, holding its own matcher.
     *
     * <p>The matcher is a field, not a lookup — the graph owns its state (D35). Holding one per
     * node is safe against re-entrant dispatch because a match's groups are copied out before
     * any body runs; it is also what makes the graph one-execution-at-a-time, which is the
     * graph's contract.
     *
     * <p>Anchoring is decided here, at compile time, from the parser's published
     * {@link LeadingAnchor} fact — that contract owns what counts as input-anchored. An
     * input-anchored pattern is dispatched {@code ANCHORED}: one attempt at the cursor, instead
     * of a search of the whole remaining region to prove what that one attempt would have
     * proved. The baseline measured that difference at 3.6× on {@code win_sec_xml}
     * (10-engine-compilation.md §5). The detection is conservative: a pattern it cannot prove
     * anchored costs a search, never a wrong answer.
     */
    final class Regex implements CompiledMatch {

        private final BytePattern pattern;
        private final int advance;
        private final Anchoring anchoring;
        private final ByteMatcher matcher;

        public Regex(final BytePattern pattern, final int advance) {
            this.pattern = pattern;
            this.advance = advance;
            // The library publishes its parser's conclusion, and for an input-anchored
            // pattern the anchored and unanchored questions provably agree — so this node
            // asks the cheaper one. No pattern text is inspected on this side of the seam:
            // the fact has one source, and it is the parser's.
            this.anchoring = pattern.leadingAnchor() == LeadingAnchor.INPUT
                    ? Anchoring.ANCHORED
                    : Anchoring.UNANCHORED;
            this.matcher = pattern.matcher();
        }

        /**
         * The compiled pattern.
         */
        public BytePattern pattern() {
            return pattern;
        }

        /**
         * Which group's end the cursor lands on, or 0 for the end of the whole match.
         */
        public int advance() {
            return advance;
        }

        /**
         * How this node asks its question — the library's published fact, not a sniff.
         */
        public Anchoring anchoring() {
            return anchoring;
        }

        /**
         * This node's matcher.
         */
        public ByteMatcher matcher() {
            return matcher;
        }
    }

    /**
     * A delimiter and its friends, encoded to bytes once; escape and the container pair are null when undeclared.
     */
    record Delimiter(byte[] delimiter,
                     byte[] escape,
                     byte[] containerStart,
                     byte[] containerEnd) implements CompiledMatch {

    }

    /**
     * Consume everything given.
     */
    record All() implements CompiledMatch {

    }

    /**
     * The document itself. Never enters the match loop; the run handles it.
     */
    record Source() implements CompiledMatch {

    }

    /**
     * Invocable only by name. Never matches.
     */
    record Named() implements CompiledMatch {

    }
}
