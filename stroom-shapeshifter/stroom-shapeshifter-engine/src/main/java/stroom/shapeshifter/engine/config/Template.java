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
import java.util.Set;
import java.util.UUID;

/**
 * One processing template: how to match, what to capture, and what to write.
 *
 * <p>Dispatch happens in three stages, cheapest first. The <b>mode</b> decides whether the
 * template is a candidate at all for a given {@code apply-templates}. The <b>guard</b> is a
 * condition over things already known — scope variables, parameters, depth — evaluated before
 * any matching is attempted; it cannot see captures, because they do not exist yet. Only then is
 * the <b>match</b> tried against the content, and a match always consumes bytes.
 *
 * <p>Conditional logic over what was captured belongs in the body, as {@code If} or
 * {@code Choose}, not in the guard.
 *
 * @param id           a stable identifier, referenced by capture bindings and instrumentation
 * @param name         a human-readable name, used in messages
 * @param mode         the dispatch partition, or null to be a candidate in every dispatch
 * @param consume      this template's matches exist to advance the cursor, not to count
 *                     (D36): an eater. Its wins move no match number, bind no captures —
 *                     declaring any is a compile-time error — and trip no store clearing
 * @param guard        a pre-filter over scope, or null
 * @param param        parameters this template expects from its callers
 * @param match        how this template matches content
 * @param matchLimits  how many times it may match, and which matches produce output
 * @param captures     bindings from match groups or steps to named scope variables
 * @param body         the output instructions, executed once per match
 * @param encoding     an encoding override for this template, or null to inherit
 * @param ignoreErrors suppress the report a match draws when it starts past the cursor and
 *                     consumes the skipped prefix. That is all it gates: unmatched-content
 *                     reporting belongs to the container that dispatched the level, through
 *                     {@link OutputNode.ApplyDirective#ignoreErrors()}
 */
public record Template(UUID id,
                       String name,
                       String mode,
                       boolean consume,
                       Condition guard,
                       List<ParamDecl> param,
                       MatchExpression match,
                       MatchLimits matchLimits,
                       List<CaptureBinding> captures,
                       List<OutputNode> body,
                       String encoding,
                       boolean ignoreErrors) {

    public Template {
        param = param == null ? List.of() : List.copyOf(param);
        captures = captures == null ? List.of() : List.copyOf(captures);
        body = body == null ? List.of() : List.copyOf(body);
        matchLimits = matchLimits == null ? MatchLimits.unlimited() : matchLimits;
    }

    /**
     * A parameter a template expects.
     *
     * @param name         the parameter name
     * @param defaultValue the value used when a caller passes none, or null to require one
     */
    public record ParamDecl(String name, String defaultValue) {

    }

    /**
     * How many times a template may match, and which of those matches produce output.
     *
     * @param minMatch  the fewest matches that count as success; 0 means no minimum
     * @param maxMatch  the most matches to attempt; -1 means unlimited
     * @param onlyMatch if set, only these 1-based match indices produce output
     */
    public record MatchLimits(int minMatch, int maxMatch, Set<Integer> onlyMatch) {

        /** The value of {@link #maxMatch()} that means "keep going". */
        public static final int UNLIMITED = -1;

        public MatchLimits {
            onlyMatch = onlyMatch == null ? null : Set.copyOf(onlyMatch);
            if (minMatch < 0) {
                throw new ConfigException("A minMatch cannot be negative: " + minMatch);
            }
            if (maxMatch < UNLIMITED) {
                throw new ConfigException("A maxMatch must be a count, or -1 for unlimited: " + maxMatch);
            }
            if (onlyMatch != null) {
                for (final Integer index : onlyMatch) {
                    if (index < 1) {
                        throw new ConfigException("An onlyMatch index is 1-based: " + index);
                    }
                }
            }
        }

        /** No minimum, no maximum, every match producing output. */
        public static MatchLimits unlimited() {
            return new MatchLimits(0, UNLIMITED, null);
        }
    }

    /**
     * Flags on a regex match.
     *
     * <p>Only two, because the rest of the dialect's flags are spelled inline. Multiline in
     * particular is {@code (?m)} in the pattern, as
     * {@link stroom.shapeshifter.regex.BytePattern}'s dialect spells it.
     *
     * @param caseInsensitive fold case when matching
     * @param dotAll          let {@code .} match a newline
     */
    public record RegexFlags(boolean caseInsensitive, boolean dotAll) {

        /** Neither flag set. */
        public static RegexFlags none() {
            return new RegexFlags(false, false);
        }
    }
}
