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

import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.engine.graph.CompiledMatch;
import stroom.shapeshifter.engine.match.PatternKey;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.text.RegexEncodings;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.PatternCompileException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The match side of compilation: the patterns a configuration uses, interned once, and each
 * template's match expression compiled against them.
 *
 * <p>Owns the interned patterns as state, because the order matters and is easy to get wrong
 * from outside: a body's compiled form resolves its regex replaces against the interned map,
 * so a template's guard and body patterns are interned before its body is compiled. The key
 * is text, flags and encoding (design 19; design 27 ruling 9): guards and bodies match
 * resolved values, internal form, UTF-8 whatever the feed's encoding, and only the match
 * vocabulary sees feed bytes and compiles for the template's encoding.
 */
final class MatchCompiler {

    private final Map<PatternKey, BytePattern> patterns = new HashMap<>();

    /**
     * Every pattern interned so far, keyed by text, flags and encoding.
     */
    Map<PatternKey, BytePattern> patterns() {
        return patterns;
    }

    /**
     * Intern a template's patterns and compile its match, in that order.
     *
     * @param matchEncoding the encoding the template's match vocabulary sees: its own
     *                      declaration, or the source's — and there is only one, since design 32
     *                      settles the encoding before anything compiles
     */
    CompiledMatch compile(final Template template,
                          final Encoding matchEncoding,
                          final Interner names) {
        names.labels(null);
        if (template.guard() != null) {
            collect(template.guard(), template);
        }
        collect(template.body(), template);
        return compileMatch(template, matchEncoding, names);
    }

    /**
     * Compile the patterns hiding inside bodies and conditions.
     *
     * <p>A template's own pattern is obvious; these are the ones in a {@code matches} test or a
     * regex {@code replace}, nested arbitrarily deep in a {@code choose} inside a
     * {@code variable}. They are just as capable of being wrong, and finding out at compile time
     * is the difference between a configuration that is rejected and one that fails on a record.
     */
    private void collect(final List<OutputNode> body, final Template template) {
        for (final OutputNode node : body) {
            // An instruction with a pattern of its own says so on the model (D47).
            if (node instanceof final OutputNode.Regexed regexed && regexed.isRegex()) {
                intern(PatternKey.ofValue(regexed.pattern()), template);
            }
            switch (node) {
                case final OutputNode.If value -> collect(value.test(), template);
                case final OutputNode.Choose value -> value.when().forEach(branch -> collect(branch.test(), template));
                case final OutputNode.Holder ignored -> {
                    // No condition of its own; its bodies are walked below.
                }
                case final OutputNode.Binding ignored -> {
                    // No condition.
                }
                case final OutputNode.Leaf ignored -> {
                    // No condition.
                }
            }
            if (node instanceof final OutputNode.Holder holder) {
                for (final List<OutputNode> nested : holder.bodies()) {
                    collect(nested, template);
                }
            }
        }
    }

    /**
     * A body's and a condition's patterns run over resolved values: {@link PatternKey#ofValue}.
     */
    private void collect(final Condition condition, final Template template) {
        switch (condition) {
            case final Condition.Matches matches -> intern(PatternKey.ofValue(matches.pattern()), template);
            case final Condition.And value -> value.conditions().forEach(child -> collect(child, template));
            case final Condition.Or value -> value.conditions().forEach(child -> collect(child, template));
            case final Condition.Not value -> collect(value.condition(), template);
            default -> {
                // Everything else compares values rather than matching patterns.
            }
        }
    }

    private void intern(final PatternKey key, final Template template) {
        patterns.computeIfAbsent(key, k -> {
            try {
                return BytePattern.compile(k.text(), k.flags(), k.encoding());
            } catch (final PatternCompileException e) {
                throw new ConfigException("Template '" + template.name() + "' has an invalid pattern '"
                                          + key.text() + "': " + e.getMessage(), e);
            }
        });
    }

    private CompiledMatch compileMatch(final Template template,
                                       final Encoding matchEncoding,
                                       final Interner names) {
        return switch (template.match()) {
            case final MatchExpression.Pattern pattern -> {
                final PatternCompiler.Compiled compiled = PatternCompiler.compile(pattern.node(), matchEncoding,
                        template.name());
                names.labels(compiled.labels());
                yield new CompiledMatch.Pattern(compiled.pattern(), compiled.casts());
            }
            case final MatchExpression.Parts parts -> compileParts(template, parts, matchEncoding, names);
            case final MatchExpression.Regex regex -> {
                final BytePattern pattern;
                try {
                    pattern = BytePattern.compile(regex.pattern(), PatternKey.flags(regex.flags()),
                            RegexEncodings.forMatch(matchEncoding));
                } catch (final PatternCompileException e) {
                    throw new ConfigException(
                            "Template '" + template.name() + "' has an invalid pattern '"
                            + regex.pattern() + "': " + e.getMessage(), e);
                }
                if (regex.advance() > pattern.groupCount()) {
                    throw new ConfigException(
                            "Template '" + template.name() + "' advances to group " + regex.advance()
                            + ", but its pattern has only " + pattern.groupCount() + " groups");
                }
                yield new CompiledMatch.Regex(pattern, regex.advance());
            }
            case final MatchExpression.Delimiter delimiter -> new CompiledMatch.Delimiter(
                    encode(delimiter.delimiter(), matchEncoding),
                    encode(delimiter.escape(), matchEncoding),
                    encode(delimiter.containerStart(), matchEncoding),
                    encode(delimiter.containerEnd(), matchEncoding));
            case final MatchExpression.All ignored -> new CompiledMatch.All();
            case final MatchExpression.Source ignored -> new CompiledMatch.Source();
            case final MatchExpression.Named ignored -> new CompiledMatch.Named();
        };
    }

    /**
     * A match sequence (design 38 §3b): each pattern part compiled as a pattern, its labels
     * renumbered after the parts before it; a take or a read is one group of its own; a length names a
     * label matched by an earlier part, a variable, or a number.
     */
    private CompiledMatch compileParts(final Template template,
                                       final MatchExpression.Parts parts,
                                       final Encoding matchEncoding,
                                       final Interner names) {
        final Map<String, Integer> labels = new LinkedHashMap<>();
        final List<CompiledMatch.CompiledPart> compiled = new ArrayList<>(parts.parts().size());
        int groups = 0;
        for (final MatchExpression.MatchPart part : parts.parts()) {
            switch (part) {
                case final MatchExpression.MatchPart.Pattern pattern -> {
                    // A bare tag is a byte compare, not a pattern (design 41 §6): the model says
                    // "this literal here", and the graph need not run a matcher to know it.
                    final PatternNode.Tag tag = bareTag(pattern.node());
                    if (tag != null) {
                        int group = 0;
                        if (pattern.node() instanceof final PatternNode.Labelled labelled) {
                            groups++;
                            group = groups;
                            if (labels.put(labelled.label(), groups) != null) {
                                throw new ConfigException("Template '" + template.name() + "' uses label '"
                                                          + labelled.label() + "' in two parts of its match");
                            }
                        }
                        compiled.add(new CompiledMatch.CompiledPart.Literal(encode(tag.text(), matchEncoding), group));
                        continue;
                    }
                    final PatternCompiler.Compiled one = PatternCompiler.compile(pattern.node(), matchEncoding,
                            template.name());
                    final int offset = groups;
                    for (final Map.Entry<String, Integer> label : one.labels().entrySet()) {
                        if (labels.put(label.getKey(), offset + label.getValue()) != null) {
                            throw new ConfigException("Template '" + template.name() + "' uses label '"
                                                      + label.getKey() + "' in two parts of its match");
                        }
                    }
                    groups += one.pattern().groupCount();
                    compiled.add(new CompiledMatch.CompiledPart.Pattern(
                            new CompiledMatch.Pattern(one.pattern(), one.casts()), offset));
                }
                case final MatchExpression.MatchPart.Take take -> {
                    final CompiledMatch.CompiledLength length = length(take.length(), labels, names, template);
                    groups++;
                    if (take.label() != null && labels.put(take.label(), groups) != null) {
                        throw new ConfigException("Template '" + template.name() + "' uses label '"
                                                  + take.label() + "' in two parts of its match");
                    }
                    compiled.add(new CompiledMatch.CompiledPart.Take(length, groups));
                }
                case final MatchExpression.MatchPart.Seek seek -> compiled.add(new CompiledMatch.CompiledPart.Seek(
                        length(seek.length(), labels, names, template), seek.absolute()));
                case final MatchExpression.MatchPart.Read read -> {
                    groups++;
                    if (read.label() != null && labels.put(read.label(), groups) != null) {
                        throw new ConfigException("Template '" + template.name() + "' uses label '"
                                                  + read.label() + "' in two parts of its match");
                    }
                    compiled.add(new CompiledMatch.CompiledPart.Read(read.as(), groups));
                }
            }
        }
        names.labels(labels);
        final String[] groupNames = new String[groups + 1];
        for (final Map.Entry<String, Integer> label : labels.entrySet()) {
            groupNames[label.getValue()] = label.getKey();
        }
        return new CompiledMatch.Parts(compiled.toArray(new CompiledMatch.CompiledPart[0]), groups, groupNames);
    }

    /** The node as a tag with nothing else — bare, or labelled with no cast — else null. */
    private static PatternNode.Tag bareTag(final PatternNode node) {
        if (node instanceof final PatternNode.Tag tag && !tag.text().isEmpty()) {
            return tag;
        }
        if (node instanceof final PatternNode.Labelled labelled && labelled.as() == null
            && labelled.body() instanceof final PatternNode.Tag tag && !tag.text().isEmpty()) {
            return tag;
        }
        return null;
    }

    private static CompiledMatch.CompiledLength length(final MatchExpression.Length length,
                                                       final Map<String, Integer> labels,
                                                       final Interner names,
                                                       final Template template) {
        return switch (length) {
            case final MatchExpression.Length.Literal literal -> {
                if (literal.count() < 0) {
                    throw new ConfigException("Template '" + template.name() + "' has a negative length");
                }
                yield new CompiledMatch.CompiledLength.Literal(literal.count());
            }
            case final MatchExpression.Length.Label label -> {
                final Integer group = labels.get(label.label());
                if (group == null) {
                    throw new ConfigException("Template '" + template.name() + "' takes a length from label '"
                                              + label.label() + "', which no earlier part of its match binds");
                }
                yield new CompiledMatch.CompiledLength.Group(group);
            }
            case final MatchExpression.Length.Var var -> new CompiledMatch.CompiledLength.Var(names.intern(var.name()));
        };
    }

    /**
     * A delimiter's byte form, through the same {@link Encoding#encode} the rest of the engine
     * uses: one encode path, one truth, so a RAW template's delimiter and its patterns cannot
     * disagree about the bytes of one text (design 19 phase 0).
     */
    private static byte[] encode(final String text, final Encoding encoding) {
        return text == null
                ? null
                : encoding.encode(text);
    }
}
