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

import stroom.shapeshifter.engine.config.Codec;
import stroom.shapeshifter.engine.config.CombinatorPattern;
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.MatchStep;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.exec.Codecs;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.text.RegexEncodings;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.PatternCompileException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
     */
    CompiledMatch compile(final Template template, final Encoding matchEncoding) {
        if (template.guard() != null) {
            collect(template.guard(), template, stroom.shapeshifter.regex.Encoding.UTF_8);
        }
        collect(template.body(), template, stroom.shapeshifter.regex.Encoding.UTF_8);
        final List<MatchStep> resolvedSteps;
        if (template.match() instanceof MatchExpression.Progressive progressive) {
            // Resolved, not raw: the executor matches the inlined sequence, so a regex
            // reached through a library reference is interned like one written in place.
            resolvedSteps = resolve(progressive.steps(), new HashSet<>());
            // Never null here: a transcode-family source is decoded first and a template may
            // not declare one, so the lowering exists (RegexEncodings.forMatch says why).
            steps(resolvedSteps, template, RegexEncodings.forMatch(matchEncoding));
        } else {
            resolvedSteps = null;
        }
        return compileMatch(template, matchEncoding, resolvedSteps);
    }

    /**
     * Compile the patterns hiding inside bodies and conditions.
     *
     * <p>A template's own pattern is obvious; these are the ones in a {@code matches} test or a
     * regex {@code replace}, nested arbitrarily deep in a {@code choose} inside a
     * {@code variable}. They are just as capable of being wrong, and finding out at compile time
     * is the difference between a configuration that is rejected and one that fails on a record.
     */
    private void collect(final List<OutputNode> body,
                         final Template template,
                         final stroom.shapeshifter.regex.Encoding encoding) {
        for (final OutputNode node : body) {
            switch (node) {
                case OutputNode.Replace replace -> {
                    if (replace.isRegex()) {
                        intern(PatternKey.of(replace.pattern(), encoding), template);
                    }
                }
                case OutputNode.If value -> collect(value.test(), template, encoding);
                case OutputNode.Choose value ->
                        value.when().forEach(branch -> collect(branch.test(), template, encoding));
                default -> {
                    // No pattern of its own; what it holds is walked below.
                }
            }
            for (final List<OutputNode> nested : Containers.bodies(node)) {
                collect(nested, template, encoding);
            }
        }
    }

    private void collect(final Condition condition,
                         final Template template,
                         final stroom.shapeshifter.regex.Encoding encoding) {
        switch (condition) {
            case Condition.Matches matches -> intern(PatternKey.of(matches.pattern(), encoding), template);
            case Condition.And value -> value.conditions()
                    .forEach(child -> collect(child, template, encoding));
            case Condition.Or value -> value.conditions()
                    .forEach(child -> collect(child, template, encoding));
            case Condition.Not value -> collect(value.condition(), template, encoding);
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

    /**
     * Walk a step sequence for the patterns it uses and the codecs it needs.
     *
     * <p>A codec this build cannot apply is refused here rather than returning nothing at match
     * time, because "no match" and "cannot do that" are different answers and only one of them
     * is the configuration's fault.
     */
    private void steps(final List<MatchStep> steps,
                       final Template template,
                       final stroom.shapeshifter.regex.Encoding encoding) {
        for (final MatchStep step : steps) {
            switch (step) {
                case MatchStep.Regex regex ->
                        intern(PatternKey.of(regex.pattern(), regex.flags(), encoding), template);
                case MatchStep.Decode decode -> requireCodec(decode.codec(), template);
                case MatchStep.Encode encode -> requireCodec(encode.codec(), template);
                case MatchStep.Choice choice ->
                        choice.alternatives().forEach(alternative -> steps(alternative, template, encoding));
                case MatchStep.Optional optional -> steps(optional.steps(), template, encoding);
                case MatchStep.Repeat repeat -> steps(repeat.steps(), template, encoding);
                case MatchStep.Sequence sequence -> steps(sequence.steps(), template, encoding);
                case MatchStep.Peek peek -> steps(peek.steps(), template, encoding);
                case MatchStep.Not not -> steps(not.steps(), template, encoding);
                default -> {
                    // The remaining atoms need nothing compiled.
                }
            }
        }
    }

    private static void requireCodec(final Codec codec, final Template template) {
        if (!Codecs.isSupported(codec)) {
            throw ConfigException.notYet(template.name(), codec.name().toLowerCase(Locale.ROOT) + " coding");
        }
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
                                       final List<MatchStep> resolvedSteps) {
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
            case MatchExpression.Progressive ignored -> new CompiledMatch.Progressive(resolvedSteps);
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
