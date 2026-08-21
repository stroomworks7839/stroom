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
 * The engines a pattern can run on, in order of how much machinery they need.
 * {@link BytePattern#tier()} is an engine's ordinal. Machinery is not cost: {@link #TREE},
 * the biggest machine, measured faster than every flat engine on the corpus (D32).
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
     * size × input length. It was chosen per search from D26 until D32, when the tree engine
     * measured faster on every corpus pattern; since then it runs only when pinned, as the
     * differential suite's witness from a second algorithm family.
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
     * Unbounded backtracking over the flat program, for the constructs that are not regular:
     * backreferences, lookaround, atomic groups and possessive quantifiers, and {@code \G}.
     * A backreference's future depends on capture state, which is exactly what a simulation's
     * state cannot carry and what invalidates {@link #BACKTRACK}'s visited-set bound — so this
     * tier trades the linear-time guarantee for the capability, and contains the loss with a
     * step budget: a pathological pattern-input pair raises {@link MatchLimitException} rather
     * than hanging. Since D31 the fancy tier's <em>primary</em> engine is {@link #TREE}; this
     * one is its structural fallback — an explicit stack cannot run out of call-stack depth —
     * and remains pinnable for the differential suite.
     */
    FANCY("unbounded backtracking"),

    /**
     * The JDK's architecture, transplanted: the pattern compiles to a tree of node objects,
     * each construct with its own {@code match()}, recursion serving as the undo log — over
     * this dialect and byte input. Built to measure the question 05-engine-benchmarks.md
     * §10.2 records, it measured at or ahead of the JDK across the board (§10.3–10.4), and
     * since D31 the compiler chooses it: the primary engine for every fancy pattern (with
     * {@link #FANCY} as structural fallback), and the first try for ambiguous searches too
     * large for {@link #BACKTRACK}'s budget (with {@link #SIMULATE} as the fallback that
     * keeps the linear-time promise). Its one structural limit is recursion depth on long
     * records with stateful loops, contained by a loop-depth guard that hands the search to
     * the fallback instead of meeting {@code StackOverflowError}.
     */
    TREE("node-tree backtracking");

    private final String description;

    Engine(final String description) {
        this.description = description;
    }

    /** How this engine is named in {@link BytePattern#explain()} and in reports. */
    public String description() {
        return description;
    }
}
