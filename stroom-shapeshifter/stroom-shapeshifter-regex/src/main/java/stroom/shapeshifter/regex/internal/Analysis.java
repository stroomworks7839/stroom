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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * First/follow analysis and the one-pass predicate.
 * <p>
 * A pattern is one-pass — and so compilable to a straight-line scan plan rather than an
 * automaton — if, recursively:
 * <ol>
 *   <li>every alternation has pairwise disjoint first-sets, with at most one nullable branch;</li>
 *   <li>every repetition offering a choice ({@code min != max}) has a non-nullable body whose
 *       first-set is disjoint from whatever can follow it;</li>
 *   <li>no construct is decided by <em>preference</em> rather than by the next byte. A branch
 *       that can match empty says "stop here", which competes with any later branch that would
 *       consume — and if the rest of the pattern can accept, stopping always succeeds, so the
 *       choice cannot be settled by lookahead at all.</li>
 * </ol>
 * That third rule is easy to miss and its absence is not a slow path but a wrong answer:
 * {@code (a|ab)} against {@code "ab"} must yield {@code "a"}, because the first branch is
 * preferred, whereas a byte-dispatching scan would see {@code b} and consume it.
 * Under those conditions the next input byte always determines the only possible continuation,
 * so no backtracking is needed and no automaton has to be built.
 * <p>
 * The violations this produces are useful in their own right: they say precisely which
 * construct is ambiguous and on which bytes, which makes them a usable authoring diagnostic
 * and not only a compiler decision.
 */
public final class Analysis {

    /** A reason a pattern is not one-pass, phrased for an author rather than a compiler. */
    public record Violation(String kind, String detail) {

        @Override
        public String toString() {
            return kind + ": " + detail;
        }
    }

    private Analysis() {
    }

    public static List<Violation> onePassViolations(final Hir root) {
        final List<Violation> violations = new ArrayList<>();
        // A match need not consume all input, so at the top level the pattern can always end.
        check(root, new BitSet(256), true, false, violations);
        return violations;
    }

    public static boolean isOnePass(final Hir root) {
        return onePassViolations(root).isEmpty();
    }

    /**
     * Constructs that compile and run, but whose behaviour is not worth relying on.
     * <p>
     * The one case found so far is a capture group repeated with a body that can match empty —
     * {@code (a*)*}, {@code (a?)+}, {@code (x|)+}. Engines disagree about what such a group
     * captures, because it depends on whether a final empty iteration is run and whether its
     * captures are recorded. The construct is also redundant: {@code (a*)*} accepts exactly what
     * {@code (a*)} accepts. Warning is more useful than silently picking an interpretation.
     */
    public static List<String> warnings(final Hir root) {
        final List<String> warnings = new ArrayList<>();
        collectWarnings(root, warnings);
        return warnings;
    }

    private static void collectWarnings(final Hir node, final List<String> out) {
        switch (node) {
            case Hir.Repeat repeat -> {
                if (repeat.max() > 1 && nullable(repeat.body()) && capturesWithin(repeat.body())) {
                    out.add("a capture group is repeated but can match nothing; what it captures "
                            + "is engine-specific, and the repetition is redundant");
                }
                collectWarnings(repeat.body(), out);
            }
            case Hir.Group group -> collectWarnings(group.body(), out);
            case Hir.Concat concat -> concat.items().forEach(item -> collectWarnings(item, out));
            case Hir.Alt alt -> alt.branches().forEach(branch -> collectWarnings(branch, out));
            default -> {
            }
        }
    }

    private static boolean capturesWithin(final Hir node) {
        return switch (node) {
            case Hir.Group group -> group.capturing() || capturesWithin(group.body());
            case Hir.Concat concat -> concat.items().stream().anyMatch(Analysis::capturesWithin);
            case Hir.Alt alt -> alt.branches().stream().anyMatch(Analysis::capturesWithin);
            case Hir.Repeat repeat -> capturesWithin(repeat.body());
            default -> false;
        };
    }

    /**
     * @param follow  bytes that can follow this node.
     * @param canEnd  whether the match can legitimately finish immediately after this node, which
     *                is what makes a preferred-empty choice undecidable by lookahead.
     */
    private static void check(final Hir node,
                              final BitSet follow,
                              final boolean canEnd,
                              final boolean boundaryFollows,
                              final List<Violation> out) {
        switch (node) {
            case Hir.Empty ignored -> {
            }
            case Hir.Bytes ignored -> {
            }
            case Hir.CharClass ignored -> {
            }
            case Hir.Assertion ignored -> {
            }
            case Hir.Backref ignored ->
                    out.add(new Violation("backreference", "what it matches depends on what was "
                            + "captured, which no forward-only scan can know"));
            case Hir.Look ignored ->
                    out.add(new Violation("lookaround", "requires a nested match"));
            case Hir.Atomic atomic -> {
                out.add(new Violation("atomic group", "backtracking control, and a scan plan "
                        + "does not backtrack"));
                check(atomic.body(), follow, canEnd, boundaryFollows, out);
            }
            case Hir.Group group -> check(group.body(), follow, canEnd, boundaryFollows, out);

            case Hir.Concat concat -> {
                final List<Hir> items = concat.items();
                for (int i = 0; i < items.size(); i++) {
                    check(items.get(i),
                            followWithin(items, i + 1, follow),
                            allNullable(items, i + 1) && canEnd,
                            boundaryWithin(items, i + 1)
                            || (allNullable(items, i + 1) && boundaryFollows),
                            out);
                }
            }

            case Hir.Alt alt -> {
                final List<Hir> branches = alt.branches();
                int nullableCount = 0;
                for (int i = 0; i < branches.size(); i++) {
                    if (nullable(branches.get(i))) {
                        nullableCount++;
                    }
                    for (int j = i + 1; j < branches.size(); j++) {
                        final BitSet overlap = first(branches.get(i));
                        overlap.and(first(branches.get(j)));
                        if (!overlap.isEmpty()) {
                            out.add(new Violation("alternation",
                                    "branches " + (i + 1) + " and " + (j + 1)
                                    + " can both start with " + render(overlap)));
                        }
                    }
                }
                if (nullableCount > 1) {
                    out.add(new Violation("alternation",
                            nullableCount + " branches can match empty"));
                }

                // A branch that can match empty is always available, so choosing any other
                // branch is a commitment that may have to be undone: if that branch is selected
                // by its first byte and then fails further in, the empty branch was the right
                // answer and a single forward pass cannot go back for it. Order does not rescue
                // this — it only decides which branch wins when both succeed.
                if (nullableCount > 0 && branches.size() > 1) {
                    for (int i = 0; i < branches.size(); i++) {
                        if (nullable(branches.get(i)) || first(branches.get(i)).isEmpty()) {
                            continue;
                        }
                        out.add(new Violation("alternation",
                                "branch " + (i + 1) + " consumes input while another branch can "
                                + "match empty, so committing to it cannot be undone if it fails "
                                + "later"));
                        break;
                    }
                }
                branches.forEach(branch -> check(branch, follow, canEnd, boundaryFollows, out));
            }

            case Hir.Repeat repeat -> {
                final BitSet bodyFirst = first(repeat.body());
                if (repeat.min() != repeat.max()) {
                    final BitSet overlap = (BitSet) bodyFirst.clone();
                    overlap.and(follow);
                    if (!overlap.isEmpty()) {
                        out.add(new Violation("repetition",
                                "the repeated part and what follows it can both start with "
                                + render(overlap)));
                    }
                }
                if (nullable(repeat.body()) && repeat.max() > 1) {
                    out.add(new Violation("repetition", "the repeated part can match empty"));
                }
                if (repeat.min() != repeat.max() && canEnd && !singleElement(repeat.body())) {
                    // Stopping is always available here, so entering another iteration is a
                    // commitment. A body of more than one element can accept its first byte and
                    // then fail, at which point stopping was the right answer — and a forward-only
                    // scan cannot go back for it. A one-element body cannot fail that way.
                    out.add(new Violation("repetition",
                            "the repeated part is more than one element and the match could end "
                            + "here, so entering another repetition cannot be undone if it fails"));
                }
                if (repeat.min() != repeat.max() && boundaryFollows) {
                    // Where a repetition ends is settled by where the boundary falls, and the
                    // boundary can hold at several of the positions the repetition could stop
                    // at — including ones short of where a greedy scan would run to. Unlike an
                    // end-of-input or end-of-line anchor, which can only hold at the one place a
                    // scan would stop anyway, this needs the ability to give some input back.
                    out.add(new Violation("repetition",
                            "a repetition followed by a word boundary ends where the boundary "
                            + "falls, which a forward-only scan cannot find"));
                }
                if (!repeat.greedy() && repeat.min() != repeat.max() && canEnd && !bodyFirst.isEmpty()) {
                    // A lazy repetition prefers to stop, and if the pattern can end here then
                    // stopping always succeeds — so a scan that consumes greedily would be wrong.
                    out.add(new Violation("repetition",
                            "a lazy repetition that could stop at the end of the match is decided "
                            + "by preference, not by the next byte"));
                }
                final BitSet bodyFollow = (BitSet) follow.clone();
                bodyFollow.or(bodyFirst);
                check(repeat.body(), bodyFollow, canEnd, boundaryFollows, out);
            }
        }
    }

    /**
     * True if the node consumes exactly one element, so it cannot accept a first byte and then
     * fail. Only such a body is safe to enter without the ability to change one's mind.
     */
    private static boolean singleElement(final Hir node) {
        return switch (node) {
            case Hir.CharClass ignored -> true;
            case Hir.Bytes bytes -> bytes.value().length == 1;
            case Hir.Group group -> singleElement(group.body());
            default -> false;
        };
    }

    /**
     * True if a word-boundary assertion can be reached from {@code from} without consuming
     * anything — which is what makes the preceding construct's end position a matter of where
     * the boundary falls rather than of the next byte.
     */
    private static boolean boundaryWithin(final List<Hir> items, final int from) {
        for (int i = from; i < items.size(); i++) {
            if (isBoundary(items.get(i))) {
                return true;
            }
            if (!nullable(items.get(i))) {
                return false;
            }
        }
        return false;
    }

    private static boolean isBoundary(final Hir node) {
        return switch (node) {
            case Hir.Assertion assertion -> switch (assertion.kind()) {
                case WORD_BOUNDARY, NOT_WORD_BOUNDARY, WORD_BOUNDARY_ASCII, NOT_WORD_BOUNDARY_ASCII ->
                        true;
                default -> false;
            };
            case Hir.Group group -> isBoundary(group.body());
            case Hir.Concat concat -> boundaryWithin(concat.items(), 0);
            case Hir.Alt alt -> alt.branches().stream().anyMatch(Analysis::isBoundary);
            default -> false;
        };
    }

    /** True if every item from {@code from} onwards can match empty. */
    private static boolean allNullable(final List<Hir> items, final int from) {
        for (int i = from; i < items.size(); i++) {
            if (!nullable(items.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** What can follow the item at {@code from - 1} within a concatenation. */
    private static BitSet followWithin(final List<Hir> items, final int from, final BitSet outer) {
        final BitSet result = new BitSet(256);
        for (int i = from; i < items.size(); i++) {
            result.or(first(items.get(i)));
            if (!nullable(items.get(i))) {
                return result;
            }
        }
        result.or(outer);
        return result;
    }

    /** The set of bytes a match of this node can begin with. */
    public static BitSet first(final Hir node) {
        final BitSet result = new BitSet(256);
        switch (node) {
            case Hir.Empty ignored -> {
            }
            case Hir.Assertion ignored -> {
            }
            case Hir.Bytes bytes -> {
                if (bytes.value().length > 0) {
                    result.set(bytes.value()[0] & 0xFF);
                }
            }
            case Hir.CharClass charClass -> result.or(charClass.leadBytes());
            case Hir.Backref ignored -> result.set(0, 256); // could match anything it captured
            case Hir.Look ignored -> {
            }
            case Hir.Atomic atomic -> result.or(first(atomic.body()));
            case Hir.Group group -> result.or(first(group.body()));
            case Hir.Repeat repeat -> result.or(first(repeat.body()));
            case Hir.Alt alt -> alt.branches().forEach(branch -> result.or(first(branch)));
            case Hir.Concat concat -> {
                for (final Hir item : concat.items()) {
                    result.or(first(item));
                    if (!nullable(item)) {
                        break;
                    }
                }
            }
        }
        return result;
    }

    public static boolean nullable(final Hir node) {
        return switch (node) {
            case Hir.Empty ignored -> true;
            case Hir.Assertion ignored -> true;
            case Hir.Bytes bytes -> bytes.value().length == 0;
            case Hir.CharClass ignored -> false;
            case Hir.Backref ignored -> true; // the group may have captured the empty string
            case Hir.Look ignored -> true;
            case Hir.Atomic atomic -> nullable(atomic.body());
            case Hir.Group group -> nullable(group.body());
            case Hir.Repeat repeat -> repeat.min() == 0 || nullable(repeat.body());
            case Hir.Alt alt -> alt.branches().stream().anyMatch(Analysis::nullable);
            case Hir.Concat concat -> concat.items().stream().allMatch(Analysis::nullable);
        };
    }

    /**
     * True if the pattern contains a construct only the unbounded backtracker can run: a
     * backreference, lookaround, an atomic group, or {@code \G}. Decided on the HIR rather
     * than the compiled program so that {@code BytePattern} can pick the engine before deciding
     * how to compile.
     */
    public static boolean fancy(final Hir node) {
        return switch (node) {
            case Hir.Backref ignored -> true;
            case Hir.Look ignored -> true;
            case Hir.Atomic ignored -> true;
            case Hir.Assertion assertion -> assertion.kind() == Hir.Kind.PREVIOUS_MATCH_END;
            case Hir.Group group -> fancy(group.body());
            case Hir.Repeat repeat -> fancy(repeat.body());
            case Hir.Concat concat -> concat.items().stream().anyMatch(Analysis::fancy);
            case Hir.Alt alt -> alt.branches().stream().anyMatch(Analysis::fancy);
            default -> false;
        };
    }

    /**
     * The start-position constraint every match obeys: 0 for none, 1 when every match begins
     * at a line start, 2 when every match begins at the region start. The HIR twin of the
     * {@code Nfa} entry-closure analysis, for engines that execute the tree rather than the
     * program.
     */
    public static int startAnchor(final Hir node) {
        return switch (node) {
            case Hir.Assertion assertion -> switch (assertion.kind()) {
                case START_INPUT -> 2;
                case START_LINE -> 1;
                default -> 0;
            };
            case Hir.Group group -> startAnchor(group.body());
            case Hir.Atomic atomic -> startAnchor(atomic.body());
            case Hir.Repeat repeat -> repeat.min() >= 1
                    ? startAnchor(repeat.body())
                    : 0;
            case Hir.Alt alt -> {
                int weakest = 2;
                for (final Hir branch : alt.branches()) {
                    weakest = Math.min(weakest, startAnchor(branch));
                }
                yield weakest;
            }
            case Hir.Concat concat -> {
                for (final Hir item : concat.items()) {
                    final int anchor = startAnchor(item);
                    if (anchor > 0) {
                        yield anchor;
                    }
                    if (!nullable(item)) {
                        yield 0;
                    }
                }
                yield 0;
            }
            default -> 0;
        };
    }

    /** Sentinel for {@link #byteLength}: no finite upper bound. */
    public static final int UNBOUNDED_LENGTH = Integer.MAX_VALUE;

    /**
     * The minimum and maximum number of bytes a match of this node can span, with
     * {@link #UNBOUNDED_LENGTH} for "no maximum". What makes bounded lookbehind implementable:
     * the candidate start positions for a match ending at the cursor are exactly
     * {@code [cursor - max, cursor - min]}.
     * <p>
     * A backreference has no static length at all, which is why a lookbehind containing one is
     * refused at compile time — the same rule, for the same reason, as the JDK's "obvious
     * maximum length" restriction.
     */
    public static int[] byteLength(final Hir node) {
        return switch (node) {
            case Hir.Empty ignored -> new int[]{0, 0};
            case Hir.Assertion ignored -> new int[]{0, 0};
            case Hir.Look ignored -> new int[]{0, 0};
            case Hir.Bytes bytes -> new int[]{bytes.value().length, bytes.value().length};
            case Hir.CharClass charClass -> {
                int min = 4;
                int max = 1;
                for (final int[] sequence : Utf8.sequences(charClass.set())) {
                    min = Math.min(min, sequence.length / 2);
                    max = Math.max(max, sequence.length / 2);
                }
                yield new int[]{Math.min(min, max), max};
            }
            case Hir.Backref ignored -> new int[]{0, UNBOUNDED_LENGTH};
            case Hir.Group group -> byteLength(group.body());
            case Hir.Atomic atomic -> byteLength(atomic.body());
            case Hir.Concat concat -> {
                // Both bounds accumulate in long and saturate, because a legal repetition can
                // already exceed an int: a{1500000000}a{1500000000} is a valid pattern whose
                // minimum overflows to a negative, and a negative minimum poisons every
                // consumer from Nfa.minLength down into index arithmetic. Saturating the
                // minimum at MAX_VALUE stays a true lower bound — no input an array can hold
                // reaches it — and the maximum saturates to its own "no finite bound" sentinel.
                long min = 0;
                long max = 0;
                for (final Hir item : concat.items()) {
                    final int[] bounds = byteLength(item);
                    min = Math.min(min + bounds[0], Integer.MAX_VALUE);
                    max = max == UNBOUNDED_LENGTH || bounds[1] == UNBOUNDED_LENGTH
                            ? UNBOUNDED_LENGTH
                            : Math.min(max + bounds[1], UNBOUNDED_LENGTH);
                }
                yield new int[]{(int) min, (int) max};
            }
            case Hir.Alt alt -> {
                int min = UNBOUNDED_LENGTH;
                int max = 0;
                for (final Hir branch : alt.branches()) {
                    final int[] bounds = byteLength(branch);
                    min = Math.min(min, bounds[0]);
                    max = Math.max(max, bounds[1]);
                }
                yield new int[]{min, max};
            }
            case Hir.Repeat repeat -> {
                final int[] body = byteLength(repeat.body());
                // Saturating, like the Concat case: the product of two legal ints overflows.
                final int min = (int) Math.min((long) body[0] * repeat.min(), Integer.MAX_VALUE);
                if (body[1] == 0) {
                    yield new int[]{0, 0}; // only empty iterations, however many
                }
                if (repeat.isUnbounded() || body[1] == UNBOUNDED_LENGTH) {
                    yield new int[]{min, UNBOUNDED_LENGTH};
                }
                final long max = (long) body[1] * repeat.max();
                yield new int[]{min, max > Integer.MAX_VALUE
                        ? UNBOUNDED_LENGTH
                        : (int) max};
            }
        };
    }

    static String render(final BitSet set) {
        final int cardinality = set.cardinality();
        if (cardinality > 8) {
            return cardinality + " different byte values";
        }
        final StringBuilder sb = new StringBuilder("{");
        for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
            if (sb.length() > 1) {
                sb.append(' ');
            }
            sb.append(i >= 0x20 && i < 0x7F
                    ? "'" + (char) i + "'"
                    : String.format("0x%02X", i));
        }
        return sb.append('}').toString();
    }
}
