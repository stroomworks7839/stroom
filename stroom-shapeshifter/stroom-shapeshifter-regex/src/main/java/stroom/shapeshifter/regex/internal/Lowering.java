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

import stroom.shapeshifter.regex.Flag;
import stroom.shapeshifter.regex.PatternCompileException;
import stroom.shapeshifter.regex.PatternCompileException.Reason;
import stroom.shapeshifter.regex.comb.Matcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowers a composed {@link Matcher} into {@link Hir} — the same representation a regex parses
 * into, so both go through one compiler and one pair of execution tiers.
 * <p>
 * This is where the composition layer stops being a separate thing. Once lowered there is no
 * combinator tree left to walk, no per-element dispatch, and nothing for the runtime to know
 * about: a composition and an equivalent regex are indistinguishable by the time they reach the
 * plan compiler.
 * <p>
 * Capture indices are assigned during a single traversal in document order, so a label and a
 * capture group written in a regex number consistently with each other — which is what lets an
 * embedded regex's own groups sit alongside the surrounding composition's labels.
 */
public final class Lowering {

    public record Result(Hir root, int groupCount, List<String> groupNames,
                         List<String> warnings) {

    }

    private final Map<String, Matcher> library;
    private final Set<Flag> flags;
    private final Deque<String> resolving = new ArrayDeque<>();
    private final List<String> groupNames = new ArrayList<>();
    private int groupCount;

    private Lowering(final Map<String, Matcher> library, final Set<Flag> flags) {
        this.library = library;
        this.flags = flags;
    }

    public static Result lower(final Matcher matcher,
                               final Map<String, Matcher> library,
                               final Set<Flag> flags) {
        final Lowering lowering = new Lowering(library, flags);
        lowering.groupNames.add(null); // group 0 is the whole match
        final Hir root = lowering.lowerNode(matcher);
        return new Result(root, lowering.groupCount, lowering.groupNames, lowering.warnings);
    }

    private Hir lowerNode(final Matcher matcher) {
        return switch (matcher) {
            case Matcher.Tag tag -> literal(tag.text());

            case Matcher.Characters characters -> new Hir.Repeat(
                    Hir.CharClass.of(
                            Parser.parseClassExpression(characters.classExpression()),
                            characters.classExpression(), ByteForm.UTF8),
                    characters.min(),
                    characters.max(),
                    true);

            case Matcher.Until until -> lowerUntil(until);

            case Matcher.Regex regex -> lowerRegex(regex);

            case Matcher.Sequence sequence -> new Hir.Concat(
                    sequence.items().stream().map(this::lowerNode).toList());

            case Matcher.Choice choice -> new Hir.Alt(
                    choice.alternatives().stream().map(this::lowerNode).toList());

            case Matcher.Repeat repeat -> new Hir.Repeat(
                    lowerNode(repeat.body()), repeat.min(), repeat.max(), repeat.greedy());

            case Matcher.Labelled labelled -> {
                // Allocated before the body is lowered, so an enclosing label always numbers
                // lower than the labels nested inside it — the same rule as opening parentheses.
                final int index = ++groupCount;
                groupNames.add(labelled.label());
                yield new Hir.Group(lowerNode(labelled.body()), index, labelled.label());
            }

            case Matcher.Ref ref -> lowerRef(ref);
        };
    }

    /** {@code takeUntil} is a run of everything except the terminator, optionally consuming it. */
    private Hir lowerUntil(final Matcher.Until until) {
        final CodePointSet excluded = CodePointSet.single(until.codePoint()).negate();
        final String label = "[^" + describe(until.codePoint()) + "]";
        final Hir run = new Hir.Repeat(
                Hir.CharClass.of(excluded, label, ByteForm.UTF8), 0, Hir.Repeat.UNBOUNDED, true);
        if (!until.inclusive()) {
            return run;
        }
        return new Hir.Concat(List.of(run,
                Hir.CharClass.of(CodePointSet.single(until.codePoint()),
                        describe(until.codePoint()), ByteForm.UTF8)));
    }

    /**
     * An embedded regex is parsed with its group numbering continuing from the composition's, so
     * its captures interleave correctly with surrounding labels rather than colliding with them.
     * The labels seen so far are passed in by name too, which is what lets the regex's own
     * {@code \k<name>} references resolve to absolute group numbers, and duplicate names across
     * the two layers be refused rather than silently shadowed.
     */
    private final List<String> warnings = new ArrayList<>();

    private Hir lowerRegex(final Matcher.Regex regex) {
        final Set<Flag> effective = regex.flags().isEmpty()
                ? flags
                : regex.flags();
        final Parser.Result parsed = Parser.parse(regex.pattern(), effective, groupNames);
        warnings.addAll(parsed.warnings());
        groupCount = parsed.groupCount();
        // The parser's list is this one plus the groups the regex created, indexed by number.
        groupNames.clear();
        groupNames.addAll(parsed.groupNames());
        return parsed.root();
    }

    private Hir lowerRef(final Matcher.Ref ref) {
        final Matcher target = library.get(ref.name());
        if (target == null) {
            throw new PatternCompileException(Reason.SYNTAX, ref.name(), -1,
                    "no matcher named '" + ref.name() + "' is defined");
        }
        if (resolving.contains(ref.name())) {
            // Inlining is what keeps the engine free of recursion, so a cycle has to be an error
            // rather than something the runtime discovers. The chain reads outermost-first —
            // a -> b -> a — which is the order a reader follows the definitions in.
            throw new PatternCompileException(Reason.UNSUPPORTED, ref.name(), -1,
                    "matcher '" + ref.name() + "' refers to itself via "
                    + String.join(" -> ", resolving) + " -> " + ref.name());
        }
        resolving.addLast(ref.name());
        try {
            return lowerNode(target);
        } finally {
            resolving.removeLast();
        }
    }

    /**
     * A literal becomes one byte sequence, which compiles to a single compare — except a single
     * byte, which becomes a singleton class instead. That is the shape {@link Parser#literal}
     * gives one-byte literals, and {@link Normalise} compares classes and byte sequences by
     * different rules when folding and factoring, so matching the parser's shape is what keeps a
     * composed {@code tag("a")} and the regex {@code a} compiling to the identical plan.
     */
    private static Hir literal(final String text) {
        if (text.isEmpty()) {
            return new Hir.Empty();
        }
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return bytes.length == 1
                ? Hir.CharClass.of(CodePointSet.single(bytes[0] & 0xFF), text, ByteForm.UTF8)
                : new Hir.Bytes(bytes, text);
    }

    private static String describe(final int codePoint) {
        return codePoint >= 0x20 && codePoint < 0x7F
                ? String.valueOf((char) codePoint)
                : String.format("\\x%02X", codePoint);
    }
}
