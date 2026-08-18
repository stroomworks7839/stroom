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
import stroom.shapeshifter.regex.internal.Normalise;
import stroom.shapeshifter.regex.internal.Parser;
import stroom.shapeshifter.regex.internal.Plan;
import stroom.shapeshifter.regex.internal.PlanCompiler;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A pattern compiled to a byte-level scan plan.
 * <p>
 * Immutable and safe to share; obtain a {@link ByteMatcher} per thread to match with.
 *
 * <h2>What this slice supports</h2>
 * <ul>
 *   <li>UTF-8 and ASCII input. Other encodings, and the {@code transcode} stage, are not built
 *       yet.</li>
 *   <li>The RE2 subset — no backreferences, lookaround, atomic groups or possessive
 *       quantifiers. Those are reported as {@link PatternCompileException.Reason#NOT_RE2}.</li>
 *   <li>Both execution tiers. A <em>one-pass</em> pattern compiles to a straight-line scan
 *       plan; an ambiguous one compiles to an NFA run by a Pike VM. The choice is the
 *       compiler's and the results are identical either way — {@link #explain()} reports which
 *       was used, and {@link #analyse} explains why a pattern was ambiguous, which is often an
 *       authoring mistake worth seeing.</li>
 *   <li>Complete inputs. Streaming, and the {@code NEED_MORE_INPUT} outcome, come later.</li>
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
    private final Plan plan;
    private final Nfa nfa;
    private final List<Analysis.Violation> ambiguities;
    private final List<String> warnings;
    private final List<String> groupNames;

    private BytePattern(final String pattern,
                        final Set<Flag> flags,
                        final Plan plan,
                        final Nfa nfa,
                        final List<Analysis.Violation> ambiguities,
                        final List<String> warnings,
                        final List<String> groupNames) {
        this.pattern = pattern;
        this.flags = flags;
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
        final Set<Flag> copy = EnumSet.copyOf(flags.isEmpty()
                ? EnumSet.noneOf(Flag.class)
                : flags);

        // A one-pass pattern can be decided by looking at one upcoming byte, so it compiles to a
        // scan plan with no automaton. Anything else needs the NFA simulation.
        final List<String> warnings = Analysis.warnings(root);
        final List<Analysis.Violation> violations = Analysis.onePassViolations(root);
        if (violations.isEmpty()) {
            final Plan plan = PlanCompiler.compile(root, groupCount, multiline, description);
            return new BytePattern(description, copy, plan, null, violations, warnings, groupNames);
        }
        final Nfa nfa = NfaCompiler.compile(root, groupCount, multiline, description);
        return new BytePattern(description, copy, null, nfa, violations, warnings, groupNames);
    }

    /** A short rendering of a composition, for {@link #explain()} and error messages. */
    private static String describe(final Matcher matcher) {
        return switch (matcher) {
            case Matcher.Tag tag -> "tag(" + tag.text() + ")";
            case Matcher.Characters characters -> "takeWhile(" + characters.classExpression() + ")";
            case Matcher.Until until -> "takeUntil(" + (char) until.codePoint() + ")";
            case Matcher.Regex regex -> "regex(" + regex.pattern() + ")";
            case Matcher.Ref ref -> ref.name();
            case Matcher.Labelled labelled -> describe(labelled.body()) + " as " + labelled.label();
            case Matcher.Sequence sequence -> sequence.items().stream()
                    .map(BytePattern::describe)
                    .collect(java.util.stream.Collectors.joining(", ", "sequence(", ")"));
            case Matcher.Choice choice -> choice.alternatives().stream()
                    .map(BytePattern::describe)
                    .collect(java.util.stream.Collectors.joining(", ", "choice(", ")"));
            case Matcher.Repeat repeat -> "repeat(" + describe(repeat.body()) + ")";
        };
    }

    /**
     * Compiles to the tier 1 NFA even when the pattern would qualify for a scan plan.
     * <p>
     * For testing and diagnostics. The two tiers must produce identical results, so being able to
     * run the same pattern through both is how that invariant is checked — and how the cost of
     * each is compared on equal terms.
     */
    public static BytePattern compileForcingNfa(final String pattern, final Set<Flag> flags) {
        final Parser.Result parsed = Parser.parse(pattern, flags);
        final Hir root = Normalise.normalise(parsed.root());
        final boolean multiline = flags.contains(Flag.MULTILINE);
        final Nfa nfa = NfaCompiler.compile(root, parsed.groupCount(), multiline, pattern);
        return new BytePattern(pattern,
                EnumSet.copyOf(flags.isEmpty()
                        ? EnumSet.noneOf(Flag.class)
                        : flags),
                null,
                nfa,
                Analysis.onePassViolations(root),
                Analysis.warnings(root),
                parsed.groupNames());
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

    public String pattern() {
        return pattern;
    }

    public Set<Flag> flags() {
        return EnumSet.copyOf(flags.isEmpty()
                ? EnumSet.noneOf(Flag.class)
                : flags);
    }

    public int groupCount() {
        return plan != null
                ? plan.groupCount()
                : nfa.groupCount();
    }

    /** The execution tier the compiler chose: 0 for a scan plan, 1 for the NFA simulation. */
    public int tier() {
        return plan != null
                ? 0
                : 1;
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
     * A listing of the compiled plan. Tests assert on this so that a change silently dropping a
     * specialised op — a scan-until becoming a general class scan, say — fails the build rather
     * than quietly costing throughput.
     */
    public String explain() {
        final StringBuilder sb = new StringBuilder()
                .append("pattern: ").append(pattern).append('\n')
                .append("flags:   ").append(flags).append('\n');
        if (plan != null) {
            sb.append("tier:    0 (scan plan, ").append(plan.size()).append(" ops)\n")
                    .append(plan.explain());
        } else {
            sb.append("tier:    1 (NFA, ").append(nfa.size()).append(" instructions)\n");
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
