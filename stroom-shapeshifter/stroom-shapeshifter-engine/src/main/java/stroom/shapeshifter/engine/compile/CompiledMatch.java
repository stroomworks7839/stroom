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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.config.MatchStep;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;

import java.util.List;

/**
 * A match expression with everything it needs already worked out.
 *
 * <p>The authored {@link stroom.shapeshifter.engine.config.MatchExpression} says what to match;
 * this says how, with patterns compiled and delimiters already encoded. The match loop should
 * not be deciding anything a compiler could have decided once.
 */
public sealed interface CompiledMatch {

    /**
     * A compiled pattern, holding its own matcher.
     *
     * <p>The matcher is a field, not a lookup — the graph owns its state (D35). Holding one per
     * node is safe against re-entrant dispatch because a match's groups are copied out before
     * any body runs; it is also what makes the graph one-execution-at-a-time, which is the
     * graph's contract.
     *
     * <p>Anchoring is decided here, at compile time. A pattern that can only match at the start
     * of its region — it opens with {@code ^} or {@code \A}, has no alternation to smuggle in
     * an unanchored branch, and no {@code (?m)} to turn {@code ^} into a line anchor — is
     * dispatched {@code ANCHORED}: one attempt at the cursor, instead of a search of the whole
     * remaining region to prove what that one attempt would have proved. The baseline measured
     * that difference at 3.6× on {@code win_sec_xml} (10-engine-compilation.md §5). The
     * detection is conservative: a pattern it cannot prove anchored costs a search, never a
     * wrong answer.
     */
    final class Regex implements CompiledMatch {

        private final BytePattern pattern;
        private final int advance;
        private final ByteMatcher matcher;

        public Regex(final BytePattern pattern, final int advance) {
            this.pattern = pattern;
            this.advance = advance;
            this.matcher = pattern.matcher();
        }

        /** The compiled pattern. */
        public BytePattern pattern() {
            return pattern;
        }

        /** Which group's end the cursor lands on, or 0 for the end of the whole match. */
        public int advance() {
            return advance;
        }

        /** This node's matcher. */
        public ByteMatcher matcher() {
            return matcher;
        }
    }

    /** A delimiter and its friends, encoded to bytes once. */
    record Delimiter(byte[] delimiter,
                     byte[] escape,
                     byte[] containerStart,
                     byte[] containerEnd) implements CompiledMatch {

    }

    /**
     * A sequence of steps, with pattern references already resolved.
     *
     * <p>The steps are the authored ones: unlike a pattern, there is nothing to compile them
     * into. What compilation does is resolve {@code PatternRef}s against the project's library
     * and intern the patterns the regex steps use, so the interpreter has no lookups to do
     * beyond the one it cannot avoid.
     */
    record Progressive(List<MatchStep> steps) implements CompiledMatch {

    }

    /** Consume everything given. */
    record All() implements CompiledMatch {

    }

    /** The document itself. Never enters the match loop; the executor handles it. */
    record Source() implements CompiledMatch {

    }

    /** Invocable only by name. Never matches. */
    record Named() implements CompiledMatch {

    }
}
