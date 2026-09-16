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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.config.BinaryCast;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.PatternNode;
import stroom.shapeshifter.engine.config.Template.RegexFlags;
import stroom.shapeshifter.engine.match.PatternKey;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.text.RegexEncodings;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.PatternCompileException;
import stroom.shapeshifter.regex.comb.Matcher;
import stroom.shapeshifter.regex.comb.MatcherLibrary;
import stroom.shapeshifter.regex.comb.Matchers;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A pattern tree to one plan (design 38 §5): the configuration's {@link PatternNode} maps node
 * for node onto the regex library's own composition, the library lowers it and chooses a tier,
 * and the labels come back as group numbers — the library numbers them, in tree order with a
 * regex leaf's own groups interleaved, exactly as it numbers parentheses.
 *
 * <p>Two nodes have no library form and lower here. A {@code take_until} whose terminator is
 * more than one character is a regex leaf, a lazy run of anything up to a lookahead of the
 * terminator, so the library gains nothing for it. And the binary casts never reach the
 * library at all: a labelled node's {@link BinaryCast} is kept by group number and applied when
 * the match binds its groups (design 38 §3).
 */
final class PatternCompiler {

    /** What a tree compiled to: the pattern, its labels by group, and each labelled group's cast. */
    record Compiled(BytePattern pattern, Map<String, Integer> labels, BinaryCast[] casts) {

    }

    private static final MatcherLibrary STANDARD = Matchers.standardLibrary();

    private final Map<String, BinaryCast> castsByLabel = new HashMap<>();

    private PatternCompiler() {
    }

    static Compiled compile(final PatternNode root, final Encoding encoding, final String templateName) {
        final PatternCompiler compiler = new PatternCompiler();
        final Matcher matcher = compiler.lower(root);
        final BytePattern pattern;
        try {
            pattern = STANDARD.compile(matcher, PatternKey.flags(RegexFlags.none()), RegexEncodings.forMatch(encoding));
        } catch (final PatternCompileException | IllegalArgumentException e) {
            throw new ConfigException("Template '" + templateName + "' has a pattern that does not compile: "
                                      + e.getMessage(), e);
        }
        final Map<String, Integer> labels = new LinkedHashMap<>();
        final BinaryCast[] casts = new BinaryCast[pattern.groupCount() + 1];
        for (final String name : pattern.groupNames()) {
            if (name == null) {
                continue;
            }
            final int group = pattern.groupIndex(name);
            labels.put(name, group);
            casts[group] = compiler.castsByLabel.get(name);
        }
        return new Compiled(pattern, labels, casts);
    }

    private Matcher lower(final PatternNode node) {
        return switch (node) {
            case final PatternNode.Tag tag -> Matchers.tag(tag.text());
            case final PatternNode.TakeWhile take -> new Matcher.Characters(take.classExpression(), take.min(),
                    take.max() == PatternNode.Repeat.UNBOUNDED ? Matcher.Repeat.UNBOUNDED : take.max());
            case final PatternNode.TakeUntil until -> lowerUntil(until);
            case final PatternNode.Take take -> new Matcher.Characters("[\\s\\S]", take.count(), take.count());
            case final PatternNode.Regex regex -> new Matcher.Regex(regex.pattern(), PatternKey.flags(regex.flags()));
            case final PatternNode.Ref ref -> Matchers.ref(ref.name());
            case final PatternNode.Sequence sequence -> new Matcher.Sequence(
                    sequence.items().stream().map(this::lower).toList());
            case final PatternNode.Choice choice -> new Matcher.Choice(
                    choice.alternatives().stream().map(this::lower).toList());
            case final PatternNode.Optional optional -> Matchers.optional(lower(optional.body()));
            case final PatternNode.Repeat repeat -> new Matcher.Repeat(lower(repeat.body()), repeat.min(),
                    repeat.max() == PatternNode.Repeat.UNBOUNDED ? Matcher.Repeat.UNBOUNDED : repeat.max(),
                    repeat.greedy());
            case final PatternNode.Peek peek -> Matchers.peek(lower(peek.body()));
            case final PatternNode.Not not -> Matchers.not(lower(not.body()));
            case final PatternNode.Labelled labelled -> {
                if (castsByLabel.containsKey(labelled.label())) {
                    throw new ConfigException("Label '" + labelled.label() + "' is used twice in one pattern");
                }
                castsByLabel.put(labelled.label(), labelled.as());
                yield lower(labelled.body()).label(labelled.label());
            }
        };
    }

    /**
     * A one-character terminator is the library's own {@code takeUntil}; a longer one is a lazy
     * run up to a lookahead of it, which the regex engine has and the library's vocabulary
     * does not name.
     */
    private static Matcher lowerUntil(final PatternNode.TakeUntil until) {
        final String terminator = until.terminator();
        if (terminator.codePointCount(0, terminator.length()) == 1) {
            final int codePoint = terminator.codePointAt(0);
            return until.inclusive() ? Matchers.takeThrough(codePoint) : Matchers.takeUntil(codePoint);
        }
        final String quoted = java.util.regex.Pattern.quote(terminator);
        return new Matcher.Regex(until.inclusive()
                ? "(?s:.*?)" + quoted
                : "(?s:.*?)(?=" + quoted + ")", java.util.Set.of());
    }
}
