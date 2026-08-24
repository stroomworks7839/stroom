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

import stroom.shapeshifter.regex.comb.Matcher;
import stroom.shapeshifter.regex.internal.Analysis;
import stroom.shapeshifter.regex.internal.Hir;
import stroom.shapeshifter.regex.internal.Lowering;
import stroom.shapeshifter.regex.internal.Nfa;
import stroom.shapeshifter.regex.internal.NfaCompiler;
import stroom.shapeshifter.regex.internal.NodeTree;
import stroom.shapeshifter.regex.internal.Normalise;
import stroom.shapeshifter.regex.internal.Parser;
import stroom.shapeshifter.regex.internal.Plan;
import stroom.shapeshifter.regex.internal.Reverse;
import stroom.shapeshifter.regex.internal.PlanCompiler;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A pattern compiled for byte-level matching.
 * <p>
 * Immutable and safe to share; obtain a {@link ByteMatcher} per thread to match with.
 *
 * <h2>What this slice supports</h2>
 * <ul>
 *   <li>UTF-8 and ASCII input. Other encodings, and the {@code transcode} stage, are not built
 *       yet.</li>
 *   <li>The RE2 subset, plus the constructs beyond it — backreferences, lookaround, atomic
 *       groups and possessive quantifiers, {@code \Q...\E} and {@code \G} — which run on the
 *       tree-walking backtracker ({@link Engine#TREE}, with {@link Engine#FANCY} as its
 *       structural fallback) and give up the linear-time guarantee for a step budget
 *       ({@link MatchLimitException}).</li>
 *   <li>Every execution tier. A <em>one-pass</em> pattern compiles to a straight-line scan
 *       plan; anything else runs the tree engine first, with the Pike VM preserving the
 *       linear-time promise beneath it and the flat backtrackers as pinnable witnesses. The
 *       choice is the compiler's and the results are identical wherever two engines can both
 *       run a pattern — {@link #explain()} reports which was chosen, and {@link #analyse}
 *       explains why a pattern was ambiguous, which is often an authoring mistake worth
 *       seeing.</li>
 *   <li>Complete and streaming input alike: matching a window that can still grow answers
 *       {@code NEED_MORE_INPUT} whenever more bytes could change the result, and the caller
 *       extends the window and asks again.</li>
 * </ul>
 *
 * <h2>Characters, not bytes</h2>
 * Character classes are defined over code points and compiled into the byte sequences that
 * encode them, so {@code .}, {@code [^,]} and {@code [a-zé]} each match a whole character
 * however many bytes it occupies, and a group span can never split one. Where it is provably
 * equivalent — an ASCII-only class, or an unbounded repeat of a class containing every
 * non-ASCII code point — matching still runs at byte level, so the common patterns keep the
 * fast path.
 */
public final class BytePattern {

    private final String pattern;
    private final Set<Flag> flags;

    /** The parser's trailing-anchor conclusion, computed once from the normalised parse and
     * published through {@link #trailingAnchor()} — the mirror of the leading fact. */
    private final TrailingAnchor trailingAnchor;

    /** The maximum bytes a match can span ({@code Analysis.byteLength}'s upper bound, with
     * its unbounded sentinel), computed once from the normalised parse beside the trailing
     * anchor. Together they license the tail-window jump: an END_INPUT match must end at the
     * region end and spans at most this, so no candidate start exists before
     * {@code regionTo - maxLength}. */
    private final int maxLength;

    /** Whether the pattern contains {@code \G}, whose reference point is the search start —
     * the one anchor the tail-window jump would move; the jump is refused for it. */
    private final boolean anchorsToSearchStart;

    /** The reverse start-finding program (§6 Phase 4) — non-null only for an unbounded
     * END_INPUT-anchored, cleanly-reversible pattern that is not input-anchored at the
     * front; see {@link stroom.shapeshifter.regex.internal.Reverse}. Compiled once, here,
     * so every matcher shares it. */
    private final Nfa reverse;
    private final Plan plan;
    private final Nfa nfa;
    private final List<Analysis.Violation> ambiguities;
    private final List<String> warnings;
    private final List<String> groupNames;

    /**
     * The engine every search must use, or null to leave the compiled machines their normal
     * order — the tree engine first with its fallback beneath it. Only set by
     * {@link #compileForcing}, and only for testing and diagnostics.
     */
    private final Engine forced;

    /**
     * The node-tree compilation, built for every fancy and every ambiguous pattern — since
     * D31/D32 the tree engine runs first on both — and when {@link Engine#TREE} was forced.
     * Null for a one-pass pattern, and when another engine was forced.
     */
    private final NodeTree.Compiled tree;

    /** The one constructor: the audit retired the telescoping overloads so no call site
     * hides behind implied defaults — every factory now passes the full argument list, with
     * its absent artifacts as visible nulls rather than an overload's silent ones. */
    private BytePattern(final String pattern,
                        final Set<Flag> flags,
                        final TrailingAnchor trailingAnchor,
                        final int maxLength,
                        final boolean anchorsToSearchStart,
                        final Plan plan,
                        final Nfa nfa,
                        final List<Analysis.Violation> ambiguities,
                        final List<String> warnings,
                        final List<String> groupNames,
                        final Nfa reverse,
                        final Engine forced,
                        final NodeTree.Compiled tree) {
        this.reverse = reverse;
        this.tree = tree;
        this.forced = forced;
        this.pattern = pattern;
        this.flags = flags;
        this.trailingAnchor = trailingAnchor;
        this.maxLength = maxLength;
        this.anchorsToSearchStart = anchorsToSearchStart;
        this.plan = plan;
        this.nfa = nfa;
        this.ambiguities = ambiguities;
        this.warnings = warnings;
        this.groupNames = groupNames;
    }

    public static BytePattern compile(final String pattern) {
        return compile(pattern, EnumSet.noneOf(Flag.class));
    }

    public static BytePattern compile(final String pattern, final Flag... flags) {
        return compile(pattern, flags.length == 0
                ? EnumSet.noneOf(Flag.class)
                : EnumSet.of(flags[0], flags));
    }

    public static BytePattern compile(final String pattern, final Set<Flag> flags) {
        final Parser.Result parsed = Parser.parse(pattern, flags);
        return compile(parsed.root(), parsed.groupCount(), parsed.groupNames(), pattern, flags);
    }

    /**
     * Compiles a composed matcher.
     * <p>
     * The composition is lowered into the same representation a regex parses into, so from here
     * on the two are indistinguishable — same analysis, same tier selection, same plan. Use
     * {@link stroom.shapeshifter.regex.comb.MatcherLibrary} rather than calling this directly.
     */
    public static BytePattern compile(final Matcher matcher,
                                      final Map<String, Matcher> library,
                                      final Set<Flag> flags) {
        final Lowering.Result lowered = Lowering.lower(matcher, library, flags);
        return compile(lowered.root(), lowered.groupCount(), lowered.groupNames(),
                describe(matcher), flags);
    }

    private static BytePattern compile(final Hir parsed,
                                       final int groupCount,
                                       final List<String> groupNames,
                                       final String description,
                                       final Set<Flag> flags) {
        // Factoring shared prefixes out of alternations makes patterns like (GET|POST|PUT)
        // decidable one byte at a time, so they reach tier 0 instead of the NFA.
        final Hir root = Normalise.normalise(parsed);
        final boolean multiline = flags.contains(Flag.MULTILINE);
        final Set<Flag> copy = copyFlags(flags);
        final TrailingAnchor trailingAnchor = trailing(root);
        final int maxLength = Analysis.byteLength(root)[1];

        // A pattern using a construct outside the regular subset — a backreference, lookaround,
        // an atomic group, \G — can only run on the unbounded backtracker, so neither the
        // one-pass analysis nor the tier choice below applies to it. The construct itself is the
        // author's opt-in; explain() names the engine.
        if (Analysis.fancy(root)) {
            final Nfa nfa = NfaCompiler.compileFancy(root, groupCount, multiline, description);
            // The tree engine is the primary for fancy patterns (D31); the flat engine stays
            // as the structural fallback when recursion depth gives out.
            return new BytePattern(description, copy, trailingAnchor, maxLength,
                    Analysis.anchorsToSearchStart(root), null, nfa,
                    List.of(), Analysis.warnings(root), groupNames, null, null,
                    NodeTree.compile(
                            root, groupCount, description));
        }

        // A one-pass pattern can be decided by looking at one upcoming byte, so it compiles to a
        // scan plan with no automaton. Anything else needs the NFA simulation.
        final List<String> warnings = Analysis.warnings(root);
        final List<Analysis.Violation> violations = Analysis.onePassViolations(root);
        if (violations.isEmpty()) {
            final Plan plan = PlanCompiler.compile(root, groupCount, multiline, description);
            // \G forces the fancy path above (Analysis.fancy owns that classification),
            // so the search-start fact is false here by construction — the walk is skipped.
            return new BytePattern(description, copy, trailingAnchor, maxLength,
                    false, plan, null,
                    violations, warnings, groupNames,
                    reverseProgram(root, trailingAnchor, maxLength,
                            plan.leadingAnchor() == Hir.Kind.START_INPUT, false,
                            multiline, description),
                    null, null);
        }
        final Nfa nfa = NfaCompiler.compile(root, groupCount, multiline, description);
        // Ambiguous patterns carry the tree too: it takes the searches the bounded
        // backtracker's budget refuses, with the simulation as the linear-time fallback (D31).
        return new BytePattern(description, copy, trailingAnchor, maxLength,
                false, null, nfa,
                violations, warnings, groupNames,
                reverseProgram(root, trailingAnchor, maxLength,
                        nfa.startAnchor() == Nfa.ANCHOR_INPUT, false,
                        multiline, description),
                null, NodeTree.compile(
                        root, groupCount, description));
    }

    /** A short rendering of a composition, for {@link #explain()} and error messages. */
    private static String describe(final Matcher matcher) {
        return switch (matcher) {
            case Matcher.Tag tag -> "tag(" + tag.text() + ")";
            case Matcher.Characters characters -> "takeWhile(" + characters.classExpression() + ")";
            case Matcher.Until until -> "takeUntil(" + Character.toString(until.codePoint()) + ")";
            case Matcher.Regex regex -> "regex(" + regex.pattern() + ")";
            case Matcher.Ref ref -> ref.name();
            case Matcher.Labelled labelled -> describe(labelled.body()) + " as " + labelled.label();
            case Matcher.Sequence sequence -> sequence.items().stream()
                    .map(BytePattern::describe)
                    .collect(Collectors.joining(", ", "sequence(", ")"));
            case Matcher.Choice choice -> choice.alternatives().stream()
                    .map(BytePattern::describe)
                    .collect(Collectors.joining(", ", "choice(", ")"));
            case Matcher.Repeat repeat -> "repeat(" + describe(repeat.body()) + ")";
        };
    }

    /**
     * Compiles for a named engine, whether or not the compiler would have chosen it.
     * <p>
     * For testing and diagnostics, and the mechanism the correctness argument rests on: every
     * engine must produce identical results, so running one pattern through all of them and
     * comparing is how that is established, rather than inferring it from each agreeing with
     * {@code java.util.regex} separately. It is also how their costs are compared on equal terms.
     *
     * @throws IllegalArgumentException if the engine cannot run the pattern:
     *                                  {@link Engine#SCAN_PLAN} refuses a pattern that is not
     *                                  one-pass, and {@link Engine#SIMULATE} and
     *                                  {@link Engine#BACKTRACK} refuse a fancy pattern, whose
     *                                  constructs only the backtracking engines can run.
     */
    public static BytePattern compileForcing(final Engine engine,
                                             final String pattern,
                                             final Set<Flag> flags) {
        if (engine == Engine.SCAN_PLAN) {
            final BytePattern compiled = compile(pattern, flags);
            if (compiled.engine() != Engine.SCAN_PLAN) {
                throw new IllegalArgumentException(
                        "pattern is not one-pass, so it cannot run as a scan plan: " + pattern);
            }
            return compiled;
        }
        if (engine == Engine.TREE) {
            final Parser.Result parsed = Parser.parse(pattern, flags);
            final Hir root = Normalise.normalise(parsed.root());
            final NodeTree.Compiled tree =
                    NodeTree.compile(
                            root, parsed.groupCount(), pattern);
            final TrailingAnchor trailingAnchor = trailing(root);
            final int maxLength = Analysis.byteLength(root)[1];
            final boolean movesWithSearchStart = Analysis.anchorsToSearchStart(root);
            return new BytePattern(pattern, copyFlags(flags), trailingAnchor, maxLength,
                    movesWithSearchStart, null, null,
                    List.of(), Analysis.warnings(root), parsed.groupNames(),
                    reverseProgram(root, trailingAnchor, maxLength,
                            tree.startAnchor() == Nfa.ANCHOR_INPUT, movesWithSearchStart,
                            flags.contains(Flag.MULTILINE), pattern),
                    engine, tree);
        }
        final BytePattern compiled = compileNfa(pattern, flags);
        if (engine != Engine.FANCY && compiled.nfa.fancy()) {
            throw new IllegalArgumentException(
                    "the pattern needs the unbounded backtracker, which cannot be overridden: "
                    + pattern);
        }
        return new BytePattern(compiled.pattern, compiled.flags, compiled.trailingAnchor,
                compiled.maxLength, compiled.anchorsToSearchStart, null, compiled.nfa,
                compiled.ambiguities, compiled.warnings, compiled.groupNames,
                compiled.reverse, engine, null);
    }

    /** The engine every search must use, or null to run the machines in their normal order. */
    Engine forced() {
        return forced;
    }

    /**
     * The node-tree compilation — present for every fancy and ambiguous pattern, and when
     * {@link Engine#TREE} was forced — or null for a one-pass pattern or another forced engine.
     */
    NodeTree.Compiled tree() {
        return tree;
    }

    private static BytePattern compileNfa(final String pattern, final Set<Flag> flags) {
        final Parser.Result parsed = Parser.parse(pattern, flags);
        final Hir root = Normalise.normalise(parsed.root());
        final boolean multiline = flags.contains(Flag.MULTILINE);
        final Nfa nfa = NfaCompiler.compile(root, parsed.groupCount(), multiline, pattern);
        final TrailingAnchor trailingAnchor = trailing(root);
        final int maxLength = Analysis.byteLength(root)[1];
        // Only a fancy pattern can carry \G, so only a fancy one pays for the walk.
        final boolean movesWithSearchStart = nfa.fancy() && Analysis.anchorsToSearchStart(root);
        return new BytePattern(pattern,
                copyFlags(flags),
                trailingAnchor,
                maxLength,
                movesWithSearchStart,
                null,
                nfa,
                Analysis.onePassViolations(root),
                Analysis.warnings(root),
                parsed.groupNames(),
                nfa.fancy()
                        ? null
                        : reverseProgram(root, trailingAnchor, maxLength,
                                nfa.startAnchor() == Nfa.ANCHOR_INPUT, false,
                                multiline, pattern),
                null,
                null);
    }

    /** A defensive {@link EnumSet} copy, tolerating the empty immutable sets callers pass. */
    private static Set<Flag> copyFlags(final Set<Flag> flags) {
        return flags.isEmpty()
                ? EnumSet.noneOf(Flag.class)
                : EnumSet.copyOf(flags);
    }

    /**
     * Reports why a pattern is ambiguous, without compiling it.
     * <p>
     * This is the compiler's tier-selection analysis exposed as an authoring diagnostic: the
     * violations name the construct and the bytes on which it is ambiguous, which frequently
     * reveals a mistake rather than a deliberate choice — an unescaped {@code .} being the
     * common one.
     *
     * @return the violations, empty if the pattern is one-pass.
     */
    public static List<Analysis.Violation> analyse(final String pattern, final Set<Flag> flags) {
        return Analysis.onePassViolations(Normalise.normalise(Parser.parse(pattern, flags).root()));
    }

    public ByteMatcher matcher() {
        return new ByteMatcher(this);
    }

    /**
     * The leading anchor the parser found — the same fact the search loops use to skip
     * non-viable positions and stop early ({@code design/06-performance-plan.md} §1).
     *
     * <p>For an {@link LeadingAnchor#INPUT} pattern the anchored and unanchored questions
     * always agree, so a caller making many calls may ask the cheaper
     * {@link Anchoring#ANCHORED} one. Every compiled artifact carries the same conclusion,
     * because all of them read it from the parse.
     */
    public LeadingAnchor leadingAnchor() {
        final int anchor;
        if (nfa != null) {
            anchor = nfa.startAnchor();
        } else if (tree != null) {
            anchor = tree.startAnchor();
        } else if (plan != null) {
            final Hir.Kind kind = plan.leadingAnchor();
            return kind == null
                    ? LeadingAnchor.NONE
                    : kind == Hir.Kind.START_LINE ? LeadingAnchor.LINE : LeadingAnchor.INPUT;
        } else {
            return LeadingAnchor.NONE;
        }
        return anchor == Nfa.ANCHOR_INPUT
                ? LeadingAnchor.INPUT
                : anchor == Nfa.ANCHOR_LINE ? LeadingAnchor.LINE : LeadingAnchor.NONE;
    }

    /**
     * The trailing anchor the parser found — the mirror of {@link #leadingAnchor()}, computed
     * once from the normalised parse, so every compiled artifact carries the same conclusion.
     *
     * <p>Every match of a {@link TrailingAnchor#INPUT} pattern ends exactly at the region
     * end. A dispatching caller matching such a pattern against a buffer it knows to be a
     * partial view can therefore refuse an edge-touching match outright — it matched the
     * buffer's end, not the input's — and the search shortcuts of the end-anchor programme
     * ({@code design/06-performance-plan.md} §6) are licensed by the same fact.
     */
    public TrailingAnchor trailingAnchor() {
        return trailingAnchor;
    }

    /**
     * The maximum bytes a match can span, or {@link Integer#MAX_VALUE} as the "no finite
     * bound" sentinel — the other half of the tail-window licence ({@link #trailingAnchor()}):
     * a pattern qualifies for the end-anchored jump only when both facts hold, and a caller
     * (or fixture) can check the qualification instead of assuming it. The same static-length
     * reasoning the JDK applies to lookbehind, published rather than re-derived.
     */
    public int maxLength() {
        return maxLength;
    }

    /** Whether the pattern anchors to the search start ({@code \G}); see the field note. */
    boolean anchorsToSearchStart() {
        return anchorsToSearchStart;
    }

    /** The reverse start-finding program, or null; see the field note. */
    Nfa reverseNfa() {
        return reverse;
    }

    /** Phase 4's qualification, in one place: unbounded END_INPUT tail, not input-anchored
     * at the front (a single forward attempt already serves those), no {@code \G} (the
     * finder has no search-start coordinate, and its assertion evaluator throws on it), and
     * cleanly reversible ({@code Reverse.program} returns null otherwise). */
    private static Nfa reverseProgram(final Hir root,
                                      final TrailingAnchor trailing,
                                      final int maxLength,
                                      final boolean inputAnchoredAtStart,
                                      final boolean anchorsToSearchStart,
                                      final boolean multiline,
                                      final String description) {
        return trailing == TrailingAnchor.INPUT
               && maxLength == Analysis.UNBOUNDED_LENGTH
               && !inputAnchoredAtStart
               && !anchorsToSearchStart
                ? Reverse.program(root, multiline, description)
                : null;
    }

    private static TrailingAnchor trailing(final Hir root) {
        final Hir.Kind kind = Analysis.trailingAnchor(root);
        return kind == null
                ? TrailingAnchor.NONE
                : kind == Hir.Kind.END_LINE ? TrailingAnchor.LINE : TrailingAnchor.INPUT;
    }

    public String pattern() {
        return pattern;
    }

    public Set<Flag> flags() {
        return copyFlags(flags);
    }

    public int groupCount() {
        if (plan == null && nfa == null) {
            return tree.groupCount();
        }
        return plan != null
                ? plan.groupCount()
                : nfa.groupCount();
    }

    /**
     * The engine that guarantees this pattern an answer: the scan plan for a one-pass pattern,
     * the tree engine for a fancy one (with {@link Engine#FANCY} as its structural fallback),
     * and the simulation for an ambiguous one — which keeps the linear-time promise even though
     * the tree engine runs first (D31/D32). Not necessarily the machine that answers a given
     * search; {@link #explain()} names the compiled tier.
     */
    public Engine engine() {
        if (plan != null) {
            return Engine.SCAN_PLAN;
        }
        if (nfa == null) {
            return Engine.TREE; // pinned by compileForcing: the tree alone
        }
        // Fancy patterns run the tree first with the flat backtracker as structural fallback;
        // ambiguous ones report the simulation, which remains both the guarantee and the
        // fallback whatever runs first (D31).
        return nfa.fancy()
                ? Engine.TREE
                : Engine.SIMULATE;
    }

    /** The execution tier, which is {@link #engine()}'s ordinal: higher means more machinery. */
    public int tier() {
        return engine().ordinal();
    }

    /**
     * Why this pattern could not use the scan plan, empty if it could.
     * <p>
     * Worth surfacing to authors: an ambiguity is frequently a mistake rather than an intent —
     * an unescaped {@code .} being the common one.
     */
    public List<Analysis.Violation> ambiguities() {
        return List.copyOf(ambiguities);
    }

    /**
     * Constructs that compiled but are not worth relying on — see
     * {@link Analysis#warnings(stroom.shapeshifter.regex.internal.Hir)}. Collected rather than
     * logged, so a host can surface them where an author will see them.
     */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** The group number for a named group, or -1. */
    public int groupIndex(final String name) {
        return groupNames.indexOf(name);
    }

    /**
     * Each group's name, indexed by group number. Entry 0 (the whole match) and unnamed
     * groups are null; a pattern with no groups yields a single-entry list. Unmodifiable.
     */
    public List<String> groupNames() {
        return Collections.unmodifiableList(groupNames);
    }

    /**
     * A listing of the compiled plan. Tests assert on this so that a change silently dropping a
     * specialised op — a scan-until becoming a general class scan, say — fails the build rather
     * than quietly costing throughput.
     */
    public String explain() {
        final StringBuilder sb = new StringBuilder()
                .append("pattern: ").append(pattern).append('\n')
                .append("flags:   ").append(flags).append('\n');
        if (reverse != null) {
            // Named ahead of the tier lines so tree-carrying patterns report it too — the
            // audit caught the first placement sitting below the tree early-return.
            sb.append("accel:   reverse start-finder (")
                    .append(reverse.size()).append(" instructions)\n");
        }
        if (nfa == null && tree != null) {
            sb.append("tier:    ").append(tier()).append(" (").append(engine().description())
                    .append(", ").append(tree.nodeCount()).append(" nodes)\n");
            return sb.toString();
        }
        if (plan != null) {
            sb.append("tier:    ").append(tier()).append(" (").append(engine().description())
                    .append(", ").append(plan.size()).append(" ops)\n")
                    .append(plan.explain());
        } else {
            sb.append("tier:    ").append(tier()).append(" (").append(engine().description())
                    .append(", ").append(nfa.size()).append(" instructions)\n");
            ambiguities.forEach(v -> sb.append("         ambiguous — ").append(v).append('\n'));
            sb.append(nfa.explain());
        }
        return sb.toString();
    }

    Plan plan() {
        return plan;
    }

    Nfa nfa() {
        return nfa;
    }
}
