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

import java.util.BitSet;
import java.util.List;

/**
 * The normalised, byte-level intermediate representation a pattern compiles to.
 * <p>
 * In the full design this is two stages — an encoding-independent HIR of code points, lowered
 * to a byte IR by the encoding compiler. This slice supports UTF-8 and ASCII only, where a
 * character class <em>is</em> a byte set and a literal <em>is</em> a byte sequence, so the two
 * stages coincide and are collapsed. The separation returns with the single-byte encoding
 * families, which need a class to become an alternation of byte-range sequences.
 */
public sealed interface Hir {

    /** Matches the empty string. */
    record Empty() implements Hir {

    }

    /** An exact byte sequence — a literal, already UTF-8 encoded. */
    record Bytes(byte[] value, String label) implements Hir {

    }

    /**
     * A set of acceptable characters at one position.
     * <p>
     * Defined over code points, with {@code leadBytes} — the bytes a member's encoding can
     * begin with — precomputed for the first/follow analysis, which works at byte level.
     */
    record CharClass(CodePointSet set, BitSet leadBytes, String label) implements Hir {

        static CharClass of(final CodePointSet set, final String label) {
            return new CharClass(set, Utf8.leadBytes(set), label);
        }
    }

    record Concat(List<Hir> items) implements Hir {

    }

    /** Ordered alternation — earlier branches are preferred. */
    record Alt(List<Hir> branches) implements Hir {

    }

    record Repeat(Hir body, int min, int max, boolean greedy) implements Hir {

        public static final int UNBOUNDED = Integer.MAX_VALUE;

        public boolean isUnbounded() {
            return max == UNBOUNDED;
        }
    }

    /** A group. {@code index} is the capture number, or -1 for a non-capturing group. */
    record Group(Hir body, int index, String name) implements Hir {

        public boolean capturing() {
            return index >= 0;
        }
    }

    record Assertion(Kind kind) implements Hir {

    }

    /**
     * A backreference — match again whatever group {@code index} captured.
     * <p>
     * Not regular: whether it matches depends on capture state, which is why any pattern
     * containing one runs on the unbounded backtracker and no other engine
     * ({@link stroom.shapeshifter.regex.Engine#FANCY}). Case sensitivity is settled here, at the
     * site of the reference, because {@code (?i)} is lexical: {@code (a)(?i:\1)} compares
     * folded where {@code (a)\1} compares exactly.
     */
    record Backref(int index, boolean caseInsensitive, boolean unicode) implements Hir {

    }

    /**
     * Lookaround — {@code (?=)}, {@code (?!)}, {@code (?<=)}, {@code (?<!)}. Zero-width; the
     * body runs as a nested match at the current position (or ending at it, when
     * {@code behind}).
     */
    record Look(Hir body, boolean behind, boolean negated) implements Hir {

    }

    /**
     * An atomic group {@code (?>...)}, which also spells possessive quantifiers: {@code a*+} is
     * {@code (?>a*)}. The body matches as it normally would, and then its choice points are
     * discarded — what it consumed is never given back.
     */
    record Atomic(Hir body) implements Hir {

    }

    enum Kind {
        /** {@code \A} — start of the match window. */
        START_INPUT,
        /** {@code \z} — end of the match window. */
        END_INPUT,
        /** {@code ^} under the multiline flag — start of input, or just after a newline. */
        START_LINE,
        /** {@code $} under the multiline flag — end of input, or before any newline. */
        END_LINE,
        /** {@code \b} over Unicode word characters. */
        WORD_BOUNDARY,
        /** {@code \B} over Unicode word characters. */
        NOT_WORD_BOUNDARY,
        /** {@code \b} under {@code (?-u)}, over ASCII word characters. */
        WORD_BOUNDARY_ASCII,
        /** {@code \B} under {@code (?-u)}. */
        NOT_WORD_BOUNDARY_ASCII,
        /**
         * {@code \G} — the end of the previous match, which is where this search started, since
         * {@link stroom.shapeshifter.regex.ByteMatcher} resumes each find there. Depends on the
         * search rather than only on the data, so only the unbounded backtracker evaluates it;
         * a pattern containing it is fancy.
         */
        PREVIOUS_MATCH_END;

        /**
         * Cached because {@link #values()} clones its array on every call, and assertions are
         * evaluated once per input position in both engines — which measured as the single
         * largest source of garbage in the whole matcher.
         */
        public static final Kind[] VALUES = values();
    }
}
