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

import stroom.shapeshifter.engine.Message;
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
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.Flag;
import stroom.shapeshifter.regex.PatternCompileException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an authored configuration into one that can run.
 *
 * <p>Compilation is where a configuration's mistakes are found. A pattern that will not compile
 * is an error now, with the template's name attached, rather than a surprise on the ten
 * thousandth record — which is the whole reason this is a separate pass rather than something
 * the match loop does lazily.
 */
public final class Compiler {

    private Compiler() {
    }

    /**
     * Compile a configuration.
     *
     * @throws ConfigException if anything in it cannot be compiled
     */
    public static CompiledProject compile(final Project project) {
        final Charset charset = charset(project.source().encoding());
        final List<CompiledTemplate> templates = new ArrayList<>(project.templates().size());
        final List<Message> warnings = new ArrayList<>();
        final Map<String, BytePattern> patterns = new HashMap<>();

        for (final Template template : project.templates()) {
            templates.add(new CompiledTemplate(template, compileMatch(template, charset, project)));
            if (template.guard() != null) {
                collect(template.guard(), template, patterns);
            }
            collect(template.body(), template, patterns);
            if (template.match() instanceof MatchExpression.Progressive progressive) {
                steps(progressive.steps(), template, patterns);
            }
        }
        return new CompiledProject(project, templates, patterns, warnings);
    }

    // -----------------------------------------------------------------------------------
    // Patterns used outside a template's own match
    // -----------------------------------------------------------------------------------

    /**
     * Compile the patterns hiding inside bodies and conditions.
     *
     * <p>A template's own pattern is obvious; these are the ones in a {@code matches} test or a
     * regex {@code replace}, nested arbitrarily deep in a {@code choose} inside a
     * {@code variable}. They are just as capable of being wrong, and finding out at compile time
     * is the difference between a configuration that is rejected and one that fails on a record.
     */
    private static void collect(final List<OutputNode> body,
                                final Template template,
                                final Map<String, BytePattern> patterns) {
        for (final OutputNode node : body) {
            switch (node) {
                case OutputNode.Replace replace -> {
                    if (replace.isRegex()) {
                        intern(replace.pattern(), template, patterns);
                    }
                }
                case OutputNode.If value -> {
                    collect(value.test(), template, patterns);
                    collect(value.then(), template, patterns);
                }
                case OutputNode.Choose value -> {
                    value.when().forEach(branch -> {
                        collect(branch.test(), template, patterns);
                        collect(branch.body(), template, patterns);
                    });
                    collect(value.otherwise(), template, patterns);
                }
                case OutputNode.Switch value -> {
                    value.cases().forEach(switchCase -> collect(switchCase.body(), template, patterns));
                    collect(value.defaultBody(), template, patterns);
                }
                case OutputNode.Variable value -> collect(value.body(), template, patterns);
                default -> {
                    // Every other instruction is a leaf as far as patterns are concerned.
                }
            }
        }
    }

    private static void collect(final Condition condition,
                                final Template template,
                                final Map<String, BytePattern> patterns) {
        switch (condition) {
            case Condition.Matches matches -> intern(matches.pattern(), template, patterns);
            case Condition.And value -> value.conditions()
                    .forEach(child -> collect(child, template, patterns));
            case Condition.Or value -> value.conditions()
                    .forEach(child -> collect(child, template, patterns));
            case Condition.Not value -> collect(value.condition(), template, patterns);
            default -> {
                // Everything else compares values rather than matching patterns.
            }
        }
    }

    private static void intern(final String pattern,
                               final Template template,
                               final Map<String, BytePattern> patterns) {
        patterns.computeIfAbsent(pattern, text -> {
            try {
                return BytePattern.compile(text);
            } catch (final PatternCompileException e) {
                throw new ConfigException("Template '" + template.name() + "' has an invalid pattern '"
                                          + text + "': " + e.getMessage(), e);
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
    private static void steps(final List<MatchStep> steps,
                              final Template template,
                              final Map<String, BytePattern> patterns) {
        for (final MatchStep step : steps) {
            switch (step) {
                case MatchStep.Regex regex -> intern(regex.pattern(), template, patterns);
                case MatchStep.Decode decode -> requireCodec(decode.codec(), template);
                case MatchStep.Encode encode -> requireCodec(encode.codec(), template);
                case MatchStep.Choice choice ->
                        choice.alternatives().forEach(alternative -> steps(alternative, template, patterns));
                case MatchStep.Optional optional -> steps(optional.steps(), template, patterns);
                case MatchStep.Repeat repeat -> steps(repeat.steps(), template, patterns);
                case MatchStep.Sequence sequence -> steps(sequence.steps(), template, patterns);
                case MatchStep.Peek peek -> steps(peek.steps(), template, patterns);
                case MatchStep.Not not -> steps(not.steps(), template, patterns);
                default -> {
                    // The remaining atoms need nothing compiled.
                }
            }
        }
    }

    private static void requireCodec(final Codec codec, final Template template) {
        if (!Codecs.isSupported(codec)) {
            throw notYet(template, codec.name().toLowerCase(java.util.Locale.ROOT) + " coding");
        }
    }

    /**
     * Inline the named patterns a sequence refers to.
     *
     * <p>Composition is an authoring convenience; by the time anything runs there are no
     * references left, only the steps they stood for (D8).
     */
    private static List<MatchStep> resolve(final List<MatchStep> steps, final Project project) {
        final List<MatchStep> resolved = new ArrayList<>(steps.size());
        for (final MatchStep step : steps) {
            final MatchStep inlined = switch (step) {
                case MatchStep.PatternRef reference -> {
                    final CombinatorPattern named = project.patterns().stream()
                            .filter(candidate -> candidate.id().equals(reference.pattern()))
                            .findFirst()
                            .orElseThrow(() -> new ConfigException(
                                    "No pattern with id " + reference.pattern()));
                    yield new MatchStep.Sequence(resolve(named.steps(), project));
                }
                case MatchStep.Choice choice -> new MatchStep.Choice(
                        choice.alternatives().stream().map(a -> resolve(a, project)).toList());
                case MatchStep.Optional optional -> new MatchStep.Optional(resolve(optional.steps(), project));
                case MatchStep.Repeat repeat ->
                        new MatchStep.Repeat(resolve(repeat.steps(), project), repeat.min(), repeat.max());
                case MatchStep.Sequence sequence -> new MatchStep.Sequence(resolve(sequence.steps(), project));
                case MatchStep.Peek peek -> new MatchStep.Peek(resolve(peek.steps(), project));
                case MatchStep.Not not -> new MatchStep.Not(resolve(not.steps(), project));
                default -> step;
            };
            resolved.add(inlined);
        }
        return resolved;
    }

    private static CompiledMatch compileMatch(final Template template,
                                              final Charset charset,
                                              final Project project) {
        return switch (template.match()) {
            case MatchExpression.Regex regex -> {
                final Set<Flag> flags = EnumSet.noneOf(Flag.class);
                if (regex.flags().caseInsensitive()) {
                    flags.add(Flag.CASE_INSENSITIVE);
                }
                if (regex.flags().dotAll()) {
                    flags.add(Flag.DOT_ALL);
                }
                try {
                    yield new CompiledMatch.Regex(BytePattern.compile(regex.pattern(), flags), regex.advance());
                } catch (final PatternCompileException e) {
                    throw new ConfigException(
                            "Template '" + template.name() + "' has an invalid pattern '"
                            + regex.pattern() + "': " + e.getMessage(), e);
                }
            }
            case MatchExpression.Delimiter delimiter -> new CompiledMatch.Delimiter(
                    encode(delimiter.delimiter(), charset),
                    encode(delimiter.escape(), charset),
                    encode(delimiter.containerStart(), charset),
                    encode(delimiter.containerEnd(), charset));
            case MatchExpression.All ignored -> new CompiledMatch.All();
            case MatchExpression.Source ignored -> new CompiledMatch.Source();
            case MatchExpression.Named ignored -> new CompiledMatch.Named();
            case MatchExpression.Progressive progressive ->
                    new CompiledMatch.Progressive(resolve(progressive.steps(), project));
            case MatchExpression.Avro ignored -> throw notYet(template, "Avro decoding");
            case MatchExpression.Parquet ignored -> throw notYet(template, "Parquet decoding");
            case MatchExpression.Protobuf ignored -> throw notYet(template, "Protobuf decoding");
        };
    }

    /**
     * Refuse clearly rather than fail obscurely.
     *
     * <p>The binary formats are deferred by decision (D33) and progressive matching by phase, and
     * in both cases a configuration that names them should be told so at compile time — not run
     * and produce nothing.
     */
    private static ConfigException notYet(final Template template, final String what) {
        return new ConfigException(
                "Template '" + template.name() + "' needs " + what + ", which this build does not support");
    }

    private static byte[] encode(final String text, final Charset charset) {
        return text == null ? null : text.getBytes(charset);
    }

    /**
     * The charset a configuration's encoding label names.
     *
     * <p>{@code auto} means "look at the input", which the executor does per stream; until it
     * has, UTF-8 is the assumption, and it is also what the delimiters are encoded as.
     */
    public static Charset charset(final String label) {
        if (label == null || label.isBlank() || "auto".equalsIgnoreCase(label)) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(label);
        } catch (final RuntimeException e) {
            throw new ConfigException("Unknown encoding: " + label, e);
        }
    }
}
