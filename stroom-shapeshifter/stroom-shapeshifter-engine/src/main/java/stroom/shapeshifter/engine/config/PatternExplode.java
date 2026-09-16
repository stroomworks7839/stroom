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

import stroom.shapeshifter.engine.config.Template.RegexFlags;
import stroom.shapeshifter.regex.Flag;
import stroom.shapeshifter.regex.comb.Matcher;
import stroom.shapeshifter.regex.comb.Matchers;

import java.util.EnumSet;
import java.util.Set;

/**
 * A regex brought into the tree (design 38 §3a): the regex library explodes it into the
 * composition it is, and the composition is the pattern tree node for node — the same mapping
 * {@code PatternCompiler} runs the other way. The tree is canonical: an author's regex is
 * exploded on import and the tree is what the configuration stores; labels arrive through
 * named groups, and an unnamed group is a label named by its number.
 *
 * <p>The explode always succeeds and preserves meaning, not text: every fixture regex's
 * explode compiles to the identical plan ({@code PatternExplodeTest}). What the tree cannot
 * name stays a {@code regex} leaf.
 */
public final class PatternExplode {

    private PatternExplode() {
    }

    /** The tree a regex is, under the flags a configuration can set. */
    public static PatternNode explode(final String pattern, final RegexFlags flags) {
        final Set<Flag> set = EnumSet.noneOf(Flag.class);
        if (flags.caseInsensitive()) {
            set.add(Flag.CASE_INSENSITIVE);
        }
        if (flags.dotAll()) {
            set.add(Flag.DOT_ALL);
        }
        return node(Matchers.explode(pattern, set));
    }

    private static PatternNode node(final Matcher matcher) {
        return switch (matcher) {
            case final Matcher.Tag tag -> new PatternNode.Tag(tag.text());
            case final Matcher.Characters characters -> new PatternNode.TakeWhile(characters.classExpression(),
                    characters.min(), bound(characters.max()));
            case final Matcher.Until until -> new PatternNode.TakeUntil(
                    new String(Character.toChars(until.codePoint())), until.inclusive());
            case final Matcher.Regex regex -> new PatternNode.Regex(regex.pattern(), new RegexFlags(
                    regex.flags().contains(Flag.CASE_INSENSITIVE), regex.flags().contains(Flag.DOT_ALL)));
            case final Matcher.Ref ref -> new PatternNode.Ref(ref.name());
            case final Matcher.Sequence sequence -> new PatternNode.Sequence(
                    sequence.items().stream().map(PatternExplode::node).toList());
            case final Matcher.Choice choice -> new PatternNode.Choice(
                    choice.alternatives().stream().map(PatternExplode::node).toList());
            case final Matcher.Repeat repeat -> repeat.min() == 0 && repeat.max() == 1 && repeat.greedy()
                    ? new PatternNode.Optional(node(repeat.body()))
                    : new PatternNode.Repeat(node(repeat.body()), repeat.min(), bound(repeat.max()), repeat.greedy());
            case final Matcher.Labelled labelled ->
                    new PatternNode.Labelled(node(labelled.body()), labelled.label(), null);
            case final Matcher.Peek peek -> new PatternNode.Peek(node(peek.body()));
            case final Matcher.Not not -> new PatternNode.Not(node(not.body()));
        };
    }

    private static int bound(final int max) {
        return max == Matcher.Repeat.UNBOUNDED ? PatternNode.Repeat.UNBOUNDED : max;
    }
}
