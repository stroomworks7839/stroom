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

package stroom.shapeshifter.regex;

/**
 * The engines a pattern can run on, in order of how much machinery they need — which is also the
 * order of what they cost. {@link BytePattern#tier()} is an engine's ordinal.
 * <p>
 * The compiler picks the cheapest engine that can give the right answer, and says which through
 * {@link BytePattern#explain()}. That is a deliberate part of the design rather than an internal
 * detail: a pattern falling to a more expensive engine is usually an authoring mistake, and an
 * author who can see it is an author who can fix it.
 */
public enum Engine {

    /**
     * A straight-line scan plan with no automaton, for a pattern whose next byte always determines
     * its next step. Around 1.5 to 9.5 nanoseconds per input byte.
     */
    SCAN_PLAN("scan plan"),

    /**
     * Depth-first matching with backtracking, bounded by a bitset of every (instruction, position)
     * already tried so that it cannot take exponential time. Pays none of the simulation's
     * per-position cost and writes captures where they happen, so it is several times faster on
     * the short records this engine is meant for — at the price of memory proportional to program
     * size × input length, which is why it is chosen per search rather than per pattern.
     */
    BACKTRACK("bounded backtracking"),

    /**
     * Breadth-first simulation of a Thompson NFA — a Pike VM — advancing every live thread in
     * lockstep. Linear time whatever the input, so catastrophic backtracking is unrepresentable,
     * but it pays a fixed cost at every input position: around 21 to 27 nanoseconds per byte
     * regardless of the pattern.
     */
    SIMULATE("NFA simulation"),

    /**
     * Unbounded backtracking, for the constructs that are not regular: backreferences,
     * lookaround, atomic groups and possessive quantifiers, and {@code \G}. No other engine can
     * run them — a backreference's future depends on capture state, which is exactly what a
     * simulation's state cannot carry and what invalidates {@link #BACKTRACK}'s visited-set
     * bound — so this tier trades the linear-time guarantee for the capability, and contains
     * the loss with a step budget: a pathological pattern-input pair raises
     * {@link MatchLimitException} rather than hanging. Chosen only when the pattern itself asks
     * for it, by containing one of these constructs; writing {@code \1} is the opt-in.
     */
    FANCY("unbounded backtracking");

    private final String description;

    Engine(final String description) {
        this.description = description;
    }

    /** How this engine is named in {@link BytePattern#explain()} and in reports. */
    public String description() {
        return description;
    }
}
