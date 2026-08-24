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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Rewrites an {@link Hir} into an equivalent but easier-to-analyse form.
 * <p>
 * The one rewrite that earns its place is <b>factoring a shared leading atom out of adjacent
 * alternation branches</b>. The one-pass analysis decides a branch by one upcoming byte, so
 * {@code (GET|POST|PUT|DELETE)} is ambiguous to it — {@code POST} and {@code PUT} both begin
 * with {@code P} — even though a human can see it is perfectly deterministic. Factored to
 * {@code (GET|P(OST|UT)|DELETE)} it becomes decidable one byte at a time, and compiles to a
 * scan plan instead of an NFA.
 * <p>
 * Alternations of literals sharing a prefix are common in real patterns — HTTP verbs, log
 * levels, month names, version numbers — so this converts a good number of them from the
 * automaton tiers to the scan plan with no change in meaning.
 *
 * <h2>Why only adjacent branches</h2>
 * Alternation is ordered: earlier branches are preferred. Factoring adjacent branches keeps
 * every branch in its original position relative to the others, so preference is unchanged.
 * Regrouping non-adjacent branches would not: in {@code a|b|ab}, pulling the two {@code a}
 * branches together would move {@code ab} ahead of {@code b} and could change which match
 * wins. The restriction costs a little factoring and buys exact semantics.
 */
public final class Normalise {

    private Normalise() {
    }

    public static Hir normalise(final Hir node) {
        return switch (node) {
            case Hir.Alt alt -> {
                final List<Hir> branches = alt.branches().stream()
                        .map(Normalise::normalise)
                        .toList();
                final List<Hir> factored = factor(branches);
                yield factored.size() == 1
                        ? factored.getFirst()
                        : new Hir.Alt(factored);
            }
            case Hir.Concat concat -> {
                final List<Hir> items = concat.items().stream()
                        .map(Normalise::normalise)
                        .toList();
                final List<Hir> folded = foldLiterals(items);
                yield folded.size() == 1
                        ? folded.getFirst()
                        : new Hir.Concat(folded);
            }
            case Hir.Group group -> new Hir.Group(
                    normalise(group.body()), group.index(), group.name());
            case Hir.Repeat repeat -> new Hir.Repeat(
                    normalise(repeat.body()), repeat.min(), repeat.max(), repeat.greedy());
            case Hir.Look look -> new Hir.Look(
                    normalise(look.body()), look.behind(), look.negated());
            case Hir.Atomic atomic -> new Hir.Atomic(normalise(atomic.body()));
            default -> node;
        };
    }

    /**
     * Folds a run of adjacent single-character literals into one byte sequence.
     * <p>
     * A regex spells {@code ERROR} as five separate characters, which would compile to five byte
     * comparisons; as one sequence it is a single compare. It also means a composed
     * {@code tag("ERROR")} and the regex {@code ERROR} produce the same plan rather than merely
     * equivalent ones — which is the property the composition layer is supposed to have.
     * <p>
     * Only literals fold. A case-insensitive character is a two-member class, not a literal, and
     * stays as it is.
     */
    private static List<Hir> foldLiterals(final List<Hir> items) {
        final List<Hir> result = new ArrayList<>(items.size());
        int i = 0;
        while (i < items.size()) {
            final byte[] first = literalBytes(items.get(i));
            if (first == null) {
                result.add(items.get(i));
                i++;
                continue;
            }
            int j = i + 1;
            while (j < items.size() && literalBytes(items.get(j)) != null) {
                j++;
            }
            if (j - i == 1) {
                result.add(items.get(i));
            } else {
                final StringBuilder label = new StringBuilder();
                int length = 0;
                for (int k = i; k < j; k++) {
                    length += literalBytes(items.get(k)).length;
                    label.append(labelOf(items.get(k)));
                }
                final byte[] merged = new byte[length];
                int at = 0;
                for (int k = i; k < j; k++) {
                    final byte[] part = literalBytes(items.get(k));
                    System.arraycopy(part, 0, merged, at, part.length);
                    at += part.length;
                }
                result.add(new Hir.Bytes(merged, label.toString()));
            }
            i = j;
        }
        return result;
    }

    /** The bytes of a node that matches exactly one fixed character, else null. */
    private static byte[] literalBytes(final Hir node) {
        return switch (node) {
            case Hir.Bytes bytes -> bytes.value();
            case Hir.CharClass charClass -> {
                final int codePoint = charClass.set().singleCodePoint();
                yield codePoint < 0
                        ? null
                        : new String(Character.toChars(codePoint))
                                .getBytes(StandardCharsets.UTF_8);
            }
            default -> null;
        };
    }

    private static String labelOf(final Hir node) {
        return switch (node) {
            case Hir.Bytes bytes -> bytes.label();
            case Hir.CharClass charClass -> charClass.label();
            default -> "";
        };
    }

    /** Groups runs of adjacent branches that begin with the same atom and factors it out. */
    private static List<Hir> factor(final List<Hir> branches) {
        final List<Hir> result = new ArrayList<>();
        int i = 0;
        while (i < branches.size()) {
            final Hir lead = leadingAtom(branches.get(i));
            int j = i + 1;
            if (lead != null) {
                while (j < branches.size() && sameAtom(leadingAtom(branches.get(j)), lead)) {
                    j++;
                }
            }
            if (j - i < 2) {
                result.add(branches.get(i));
                i++;
                continue;
            }

            final List<Hir> remainders = new ArrayList<>();
            for (int k = i; k < j; k++) {
                remainders.add(withoutLeadingAtom(branches.get(k)));
            }
            // Recurse so a longer shared prefix is factored a byte at a time.
            final List<Hir> factoredRemainders = factor(remainders);
            final Hir tail = factoredRemainders.size() == 1
                    ? factoredRemainders.getFirst()
                    : new Hir.Alt(factoredRemainders);
            result.add(new Hir.Concat(List.of(lead, tail)));
            i = j;
        }
        return result;
    }

    /**
     * The branch's first element, if factoring it out is safe.
     * <p>
     * Only a plain literal or class qualifies. A leading group cannot be factored, because its
     * capture spans the branch and moving the atom out would move it outside the capture.
     */
    private static Hir leadingAtom(final Hir branch) {
        return switch (branch) {
            case Hir.Bytes bytes -> firstByteOf(bytes);
            case Hir.CharClass charClass -> charClass;
            case Hir.Concat concat -> concat.items().isEmpty()
                    ? null
                    : atomOrNull(concat.items().getFirst());
            default -> null;
        };
    }

    private static Hir atomOrNull(final Hir node) {
        return switch (node) {
            case Hir.Bytes bytes -> firstByteOf(bytes);
            case Hir.CharClass charClass -> charClass;
            default -> null;
        };
    }

    /**
     * Factoring works a byte at a time, so a folded literal contributes only its first byte.
     * Without this, folding {@code POST} and {@code PUT} into whole literals would hide the
     * {@code P} they share and undo the factoring that gets them onto the scan plan.
     */
    private static Hir firstByteOf(final Hir.Bytes bytes) {
        if (bytes.value().length == 0) {
            return null;
        }
        return bytes.value().length == 1
                ? bytes
                : new Hir.Bytes(new byte[]{bytes.value()[0]}, bytes.label());
    }

    private static Hir dropFirstByte(final Hir.Bytes bytes) {
        return bytes.value().length <= 1
                ? new Hir.Empty()
                : new Hir.Bytes(Arrays.copyOfRange(bytes.value(), 1, bytes.value().length),
                        bytes.label());
    }

    private static Hir withoutLeadingAtom(final Hir branch) {
        if (branch instanceof Hir.Bytes bytes) {
            return dropFirstByte(bytes);
        }
        if (!(branch instanceof Hir.Concat concat)) {
            return new Hir.Empty();
        }
        final List<Hir> items = new ArrayList<>(concat.items());
        if (items.getFirst() instanceof Hir.Bytes bytes) {
            final Hir remainder = dropFirstByte(bytes);
            if (remainder instanceof Hir.Empty) {
                items.removeFirst();
            } else {
                items.set(0, remainder);
            }
        } else {
            items.removeFirst();
        }
        return switch (items.size()) {
            case 0 -> new Hir.Empty();
            case 1 -> items.getFirst();
            default -> new Hir.Concat(List.copyOf(items));
        };
    }

    private static boolean sameAtom(final Hir a, final Hir b) {
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Hir.Bytes first && b instanceof Hir.Bytes second) {
            return Arrays.equals(first.value(), second.value());
        }
        if (a instanceof Hir.CharClass first && b instanceof Hir.CharClass second) {
            return Arrays.equals(first.set().ranges(), second.set().ranges());
        }
        return false;
    }
}
