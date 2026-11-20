/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.client.model;

import stroom.shapeshifter.config.BinaryCast;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/// The labels a part of a framed sequence can take its length from (design 47 §7.19): those the
/// parts before it bind — a `take`'s or a `read`'s, and any a pattern part labels in its tree, as
/// the engine binds them all. Rung 6's `take` is as long as the varint the `read` before it
/// labelled `len`, and nothing offered `len`: it had to be known, and typed.
///
/// A length is read as an integer, and only a `read` cast to one is sure to hold one; so only
/// such a label is ever filled in for the author, and the rest are listed for them to choose.
/// A pattern's regex leaf can name groups too; what it names is the regex parser's to say, and
/// is not read out of its text here.
public final class PartLabels {

    private static final Set<BinaryCast> INTEGERS = EnumSet.of(
            BinaryCast.UINT8, BinaryCast.INT8,
            BinaryCast.UINT16LE, BinaryCast.UINT16BE, BinaryCast.INT16LE, BinaryCast.INT16BE,
            BinaryCast.UINT32LE, BinaryCast.UINT32BE, BinaryCast.INT32LE, BinaryCast.INT32BE,
            BinaryCast.INT64LE, BinaryCast.INT64BE, BinaryCast.VARINT, BinaryCast.ZIGZAG);

    // What a new part's length starts as, which a label may replace
    private static final String STARTING_COUNT = "1";

    private PartLabels() {
    }

    /// The labels the parts before a position bind, in order, each once.
    ///
    /// @param parts The sequence's parts.
    /// @param at The position of the part being edited, or the size for one being added at the end.
    /// @return The labels.
    public static List<String> before(final List<MatchPart> parts, final int at) {
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i < Math.min(at, parts.size()); i++) {
            final MatchPart part = parts.get(i);
            if (part instanceof final MatchPart.Take take) {
                add(labels, take.label());
            } else if (part instanceof final MatchPart.Read read) {
                add(labels, read.label());
            } else if (part instanceof final MatchPart.Pattern pattern) {
                labelsOf(pattern.node(), labels);
            }
        }
        return labels;
    }

    /// The labels before a position that are sure to hold an integer: those of a `read` cast to
    /// one.
    ///
    /// @param parts The sequence's parts.
    /// @param at The position of the part being edited.
    /// @return The labels.
    public static List<String> integersBefore(final List<MatchPart> parts, final int at) {
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i < Math.min(at, parts.size()); i++) {
            if (parts.get(i) instanceof final MatchPart.Read read && INTEGERS.contains(read.as())) {
                add(labels, read.label());
            }
        }
        return labels;
    }

    /// What the length reads once it is chosen to be a label's value: the one integer an earlier
    /// part reads, where there is exactly one and the field holds nothing or the count a new part
    /// starts with; otherwise what it held. A count the author typed is theirs.
    ///
    /// @param current What the length field holds.
    /// @param integers The labels before it sure to hold an integer.
    /// @return What it is to hold.
    public static String filled(final String current, final List<String> integers) {
        final String held = current == null
                ? ""
                : current.trim();
        if (integers.size() == 1 && (held.isEmpty() || STARTING_COUNT.equals(held))) {
            return integers.get(0);
        }
        return current;
    }

    /// What is said under the length when it is a label's value.
    ///
    /// @param labels The labels the earlier parts bind.
    /// @return The hint.
    public static String hint(final List<String> labels) {
        return labels.isEmpty()
                ? "No part before this one has a label to take a length from"
                : "Labelled by the parts before this one: " + String.join(", ", labels);
    }

    private static void labelsOf(final PatternNode node, final List<String> labels) {
        if (node instanceof final PatternNode.Labelled labelled) {
            add(labels, labelled.label());
            labelsOf(labelled.body(), labels);
        } else if (node instanceof final PatternNode.Sequence sequence) {
            sequence.items().forEach(item -> labelsOf(item, labels));
        } else if (node instanceof final PatternNode.Choice choice) {
            choice.alternatives().forEach(item -> labelsOf(item, labels));
        } else if (node instanceof final PatternNode.Optional optional) {
            labelsOf(optional.body(), labels);
        } else if (node instanceof final PatternNode.Repeat repeat) {
            labelsOf(repeat.body(), labels);
        } else if (node instanceof final PatternNode.Peek peek) {
            labelsOf(peek.body(), labels);
        } else if (node instanceof final PatternNode.Not not) {
            labelsOf(not.body(), labels);
        }
    }

    private static void add(final List<String> labels, final String label) {
        if (label != null && !label.isEmpty() && !labels.contains(label)) {
            labels.add(label);
        }
    }
}
