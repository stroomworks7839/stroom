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

import stroom.shapeshifter.engine.match.CompiledStep;
import stroom.shapeshifter.engine.match.Decoding;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.LeadingAnchor;

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

        /** The compiled pattern. */
        public BytePattern pattern() {
            return pattern;
        }

        /** Which group's end the cursor lands on, or 0 for the end of the whole match. */
        public int advance() {
            return advance;
        }

        /** How this node asks its question — the library's published fact, not a sniff. */
        public Anchoring anchoring() {
            return anchoring;
        }

        /** This node's matcher. */
        public ByteMatcher matcher() {
            return matcher;
        }
    }

    /** A delimiter and its friends, encoded to bytes once; escape and the container pair are null when undeclared. */
    record Delimiter(byte[] delimiter,
                     byte[] escape,
                     byte[] containerStart,
                     byte[] containerEnd) implements CompiledMatch {

    }

    /**
     * A sequence of compiled steps and the reading they run under.
     *
     * <p>Compilation resolves {@code PatternRef}s against the project's library, then turns each
     * authored step into a {@link CompiledStep} that already holds whatever it would otherwise
     * work out per attempt: its encoded literal, its pattern and matcher, its byte table
     * (design 29 §3.3). The interpreter has no lookups left to do.
     *
     * @param steps    the compiled steps, references inlined
     * @param decoding the encoding they were compiled for, and how bytes read under it
     */
    record Compilation(List<CompiledStep> steps, Decoding decoding) {

        public Compilation {
            steps = List.copyOf(steps);
        }
    }

    /**
     * A progressive match: its steps compiled, in the one or two readings a run can give them.
     *
     * <p>Baking the encoding into the steps means asking which encoding, and for a template that
     * declares none the answer can still move once: a byte-order mark at the head of the input
     * re-declares the source. Only a UTF-8 mark can, because the other three name transcode
     * families and refuse the run outright, so there are exactly two possible answers and both
     * are compiled here. Which one a run uses is settled by its first three bytes and then never
     * changes.
     *
     * @param source the reading the source's declared encoding gives
     * @param marked the reading a UTF-8 mark would give, or null when it could not differ —
     *               the template declared its own encoding, or the source is UTF-8 already
     */
    record Progressive(Compilation source, Compilation marked) implements CompiledMatch {

        /** The steps to run under a run's effective encoding. */
        public Compilation forEncoding(final Encoding effective) {
            return marked != null && effective == marked.decoding().encoding() ? marked : source;
        }
    }

    /** Consume everything given. */
    record All() implements CompiledMatch {

    }

    /** The document itself. Never enters the match loop; the run handles it. */
    record Source() implements CompiledMatch {

    }

    /** Invocable only by name. Never matches. */
    record Named() implements CompiledMatch {

    }
}
