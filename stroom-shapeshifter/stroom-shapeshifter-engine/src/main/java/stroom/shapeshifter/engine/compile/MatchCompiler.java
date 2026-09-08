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

import stroom.shapeshifter.engine.config.CombinatorPattern;
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.MatchStep;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.match.Decoding;
import stroom.shapeshifter.engine.match.PatternKey;
import stroom.shapeshifter.engine.match.StepCompiler;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.text.RegexEncodings;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.PatternCompileException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The match side of compilation: the patterns a configuration uses, interned once, and each
 * template's match expression compiled against them.
 *
 * <p>Owns the interned patterns as state, because the order matters and is easy to get wrong
 * from outside: a body's compiled form resolves its regex replaces against the interned map,
 * so a template's guard and body patterns are interned before its body is compiled, and a
 * progressive template's steps — resolved once, library references inlined (D8) — before its
 * match is. The key is text, flags and encoding (design 19; design 27 ruling 9): guards and
 * bodies match resolved values, internal form, UTF-8 whatever the feed's encoding, and only
 * the match vocabulary sees feed bytes and compiles for the template's encoding.
 */
final class MatchCompiler {

    private final Project project;
    private final Map<PatternKey, BytePattern> patterns = new HashMap<>();

    MatchCompiler(final Project project) {
        this.project = project;
    }

    /** Every pattern interned so far, keyed by text, flags and encoding. */
    Map<PatternKey, BytePattern> patterns() {
        return patterns;
    }

    /**
     * Intern a template's patterns and compile its match, in that order.
     *
     * @param matchEncoding the encoding the template's match vocabulary sees: its own
     *                      declaration, or the source's
     * @param markEncoding  the encoding a byte-order mark could move this template to, or null
     *                      when none could — see {@link CompiledMatch.Progressive}
     */
    CompiledMatch compile(final Template template,
                          final Encoding matchEncoding,
                          final Encoding markEncoding) {
        if (template.guard() != null) {
            collect(template.guard(), template);
        }
        collect(template.body(), template);
        final CompiledMatch.Progressive progressiveSteps;
        if (template.match() instanceof MatchExpression.Progressive progressive) {
            // Resolved, not raw: the steps match the inlined sequence, so a regex
            // reached through a library reference is interned like one written in place.
            final List<MatchStep> resolved = resolve(progressive.steps(), new HashSet<>());
            progressiveSteps = new CompiledMatch.Progressive(
                    compiledSteps(resolved, template, matchEncoding),
                    markEncoding == null ? null : compiledSteps(resolved, template, markEncoding));
        } else {
            progressiveSteps = null;
        }
        return compileMatch(template, matchEncoding, progressiveSteps);
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
            if (node instanceof OutputNode.Regexed regexed && regexed.isRegex()) {
                intern(PatternKey.ofValue(regexed.pattern()), template);
            }
            switch (node) {
                case OutputNode.If value -> collect(value.test(), template);
                case OutputNode.Choose value ->
                        value.when().forEach(branch -> collect(branch.test(), template));
                case OutputNode.Holder ignored -> {
                    // No condition of its own; its bodies are walked below.
                }
                case OutputNode.Binding ignored -> {
                    // No condition.
                }
                case OutputNode.Leaf ignored -> {
                    // No condition.
                }
            }
            if (node instanceof OutputNode.Holder holder) {
                for (final List<OutputNode> nested : holder.bodies()) {
                    collect(nested, template);
                }
            }
        }
    }

    /** A body's and a condition's patterns run over resolved values: {@link PatternKey#ofValue}. */
    private void collect(final Condition condition, final Template template) {
        switch (condition) {
            case Condition.Matches matches ->
                    intern(PatternKey.ofValue(matches.pattern()), template);
            case Condition.And value ->
                    value.conditions().forEach(child -> collect(child, template));
            case Condition.Or value ->
                    value.conditions().forEach(child -> collect(child, template));
            case Condition.Not value -> collect(value.condition(), template);
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

    /** The same steps compiled for one encoding: its literals, its tables, its patterns. */
    private CompiledMatch.Compilation compiledSteps(final List<MatchStep> resolved,
                                                    final Template template,
                                                    final Encoding encoding) {
        final Decoding decoding = Decoding.of(encoding);
        // Interning stays here, so a pattern a step names and one a body names share one
        // compiled pattern and one failure message.
        return new CompiledMatch.Compilation(
                StepCompiler.compile(resolved, template.name(), decoding, key -> {
                    intern(key, template);
                    return patterns.get(key);
                }),
                decoding);
    }

    /**
     * Inline the named patterns a sequence refers to.
     *
     * <p>Composition is an authoring convenience; by the time anything runs there are no
     * references left, only the steps they stood for (D8).
     *
     * <p>{@code inProgress} is what stops a pattern that refers to itself, directly or round a
     * longer loop, from inlining for ever. It is unwound on the way out rather than accumulated,
     * so a pattern used twice in different branches is fine — only a pattern reached from inside
     * itself is a cycle.
     */
    private List<MatchStep> resolve(final List<MatchStep> steps, final Set<UUID> inProgress) {
        final List<MatchStep> resolved = new ArrayList<>(steps.size());
        for (final MatchStep step : steps) {
            final MatchStep inlined = switch (step) {
                case MatchStep.PatternRef reference -> {
                    if (!inProgress.add(reference.pattern())) {
                        throw new ConfigException(
                                "Pattern " + reference.pattern() + " refers to itself");
                    }
                    final CombinatorPattern named = project.patterns().stream()
                            .filter(candidate -> candidate.id().equals(reference.pattern()))
                            .findFirst()
                            .orElseThrow(() -> new ConfigException(
                                    "No pattern with id " + reference.pattern()));
                    final List<MatchStep> inner = resolve(named.steps(), inProgress);
                    inProgress.remove(reference.pattern());
                    yield new MatchStep.Sequence(inner);
                }
                case MatchStep.Choice choice -> new MatchStep.Choice(
                        choice.alternatives().stream().map(a -> resolve(a, inProgress)).toList());
                case MatchStep.Optional optional ->
                        new MatchStep.Optional(resolve(optional.steps(), inProgress));
                case MatchStep.Repeat repeat -> new MatchStep.Repeat(
                        resolve(repeat.steps(), inProgress), repeat.min(), repeat.max());
                case MatchStep.Sequence sequence ->
                        new MatchStep.Sequence(resolve(sequence.steps(), inProgress));
                case MatchStep.Peek peek -> new MatchStep.Peek(resolve(peek.steps(), inProgress));
                case MatchStep.Not not -> new MatchStep.Not(resolve(not.steps(), inProgress));
                default -> step;
            };
            resolved.add(inlined);
        }
        return resolved;
    }

    private CompiledMatch compileMatch(final Template template,
                                       final Encoding matchEncoding,
                                       final CompiledMatch.Progressive progressiveSteps) {
        return switch (template.match()) {
            case MatchExpression.Regex regex -> {
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
            case MatchExpression.Delimiter delimiter -> new CompiledMatch.Delimiter(
                    encode(delimiter.delimiter(), matchEncoding),
                    encode(delimiter.escape(), matchEncoding),
                    encode(delimiter.containerStart(), matchEncoding),
                    encode(delimiter.containerEnd(), matchEncoding));
            case MatchExpression.All ignored -> new CompiledMatch.All();
            case MatchExpression.Source ignored -> new CompiledMatch.Source();
            case MatchExpression.Named ignored -> new CompiledMatch.Named();
            case MatchExpression.Progressive ignored -> progressiveSteps;
            case MatchExpression.Avro ignored -> throw ConfigException.notYet(template.name(), "Avro decoding");
            case MatchExpression.Parquet ignored -> throw ConfigException.notYet(template.name(), "Parquet decoding");
            case MatchExpression.Protobuf ignored -> throw ConfigException.notYet(template.name(), "Protobuf decoding");
        };
    }

    /**
     * A delimiter's byte form, through the same {@link Encoding#encode} the step vocabulary
     * uses at run time: one encode path, one truth, so a RAW template's delimiter and its step
     * tag cannot disagree about the bytes of one text (design 19 phase 0).
     */
    private static byte[] encode(final String text, final Encoding encoding) {
        return text == null ? null : encoding.encode(text);
    }
}
