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

import stroom.shapeshifter.regex.Encoding;

import java.util.ArrayList;
import java.util.List;

/**
 * The reverse program for an unbounded END_INPUT-anchored pattern — the start-finding half
 * of the end-anchor programme's Phase 4 ({@code design/06-performance-plan.md} §6). The
 * pattern's HIR is reversed (concatenation order, literal byte order) and compiled through
 * the ordinary {@link NfaCompiler} with captures stripped: the program answers exactly one
 * question, <em>where does a match ending at the region end begin</em>, and the forward
 * machinery then answers everything else with an anchored attempt at that start.
 *
 * <p>Assertions keep their original kinds: their predicates are positional
 * ({@link Words#assertionHolds}) and a reversed walk visits the same positions, so an
 * {@code END_INPUT} that led the reversed program is simply evaluated at the seed position.
 *
 * <h2>What v1 refuses (Jon's ruling, 2026-08-24)</h2>
 * Reversal must reach byte level. A literal's bytes reverse trivially — even multi-byte
 * UTF-8, since exact bytes are consumed in exact reverse order — but a non-ASCII character
 * class compiles to a lead-byte-first tree whose reversal is the part rust-regex calls
 * genuinely hard. v1 therefore accepts a class only where direction cannot matter: an
 * ASCII-only class (single bytes), or an unbounded repeat of a byte-safe class, which the
 * program compiles through {@link NfaCompiler#compileByteLevel} as a single-byte table —
 * order cannot matter for a table — covering the {@code .*} and {@code [^x]+} tails this
 * exists for. (The audit of the first cut proved the licence has to be enforced, not
 * assumed: compiled as ordinary character tries, the reversed walk died at the first
 * continuation byte and a non-ASCII input turned a trusted miss into a wrong answer.)
 * Everything else returns null and the search falls back to the forward scan, correct and
 * unaccelerated.
 */
public final class Reverse {

    private Reverse() {
    }

    /**
     * The compiled reverse program, or null when the pattern is not cleanly reversible.
     * The caller establishes the other qualifications (END_INPUT trailing anchor, unbounded
     * maximum, not fancy, not input-anchored at the front, no {@code \G} — the published
     * anchorsToSearchStart fact, checked at the one qualification site).
     *
     * <p>The {@code encoding} decides the byte level: placed by phase 1's audit, read since
     * phase 3, when the table shape arrived (design/19).
     */
    public static Nfa program(final Hir root, final boolean multiline, final String pattern,
                              final Encoding encoding) {
        final ByteForm form = ByteForm.of(encoding);
        if (!reversible(root, form)) {
            return null;
        }
        return NfaCompiler.compileByteLevel(reverse(root), 0, multiline, pattern, form);
    }

    private static boolean reversible(final Hir node, final ByteForm form) {
        return switch (node) {
            case Hir.Empty ignored -> true;
            // Every assertion here is a positional predicate the reversed walk evaluates
            // unchanged — \G, the one that is not, is refused upstream by the published
            // anchorsToSearchStart fact before this walk is consulted.
            case Hir.Assertion ignored -> true;
            case Hir.Bytes ignored -> true;
            case Hir.CharClass charClass -> form.singleByte() || charClass.set().isAsciiOnly();
            case Hir.Group group -> reversible(group.body(), form);
            case Hir.Concat concat -> concat.items().stream().allMatch(item -> reversible(item, form));
            case Hir.Alt alt -> alt.branches().stream().allMatch(branch -> reversible(branch, form));
            // An unbounded repeat of a byte-safe class runs at byte level in either
            // direction — the compiler's own licence, borrowed whole.
            case Hir.Repeat repeat -> reversible(repeat.body(), form)
                                      || (repeat.isUnbounded()
                                          && NfaCompiler.byteSafe(repeat.body(), form));
            // Fancy constructs never reach here (the caller excludes fancy patterns), and
            // a backreference has no static shape to reverse.
            case Hir.Look ignored -> false;
            case Hir.Atomic ignored -> false;
            case Hir.Backref ignored -> false;
        };
    }

    private static Hir reverse(final Hir node) {
        return switch (node) {
            case Hir.Empty ignored -> node;
            case Hir.Assertion ignored -> node;
            case Hir.CharClass ignored -> node;
            case Hir.Bytes bytes -> {
                final byte[] value = bytes.value();
                final byte[] reversed = new byte[value.length];
                for (int i = 0; i < value.length; i++) {
                    reversed[i] = value[value.length - 1 - i];
                }
                yield new Hir.Bytes(reversed, bytes.label());
            }
            // The finder proposes a start; the forward attempt owns the captures. Stripping
            // the group here is what makes the program capture-free.
            case Hir.Group group -> reverse(group.body());
            case Hir.Concat concat -> {
                final List<Hir> items = new ArrayList<>(concat.items().size());
                for (int i = concat.items().size() - 1; i >= 0; i--) {
                    items.add(reverse(concat.items().get(i)));
                }
                yield new Hir.Concat(items);
            }
            case Hir.Alt alt -> new Hir.Alt(
                    alt.branches().stream().map(Reverse::reverse).toList());
            case Hir.Repeat repeat -> new Hir.Repeat(
                    reverse(repeat.body()), repeat.min(), repeat.max(), repeat.greedy());
            case Hir.Look ignored -> throw new IllegalStateException("not reversible");
            case Hir.Atomic ignored -> throw new IllegalStateException("not reversible");
            case Hir.Backref ignored -> throw new IllegalStateException("not reversible");
        };
    }
}
