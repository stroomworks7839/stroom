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

package stroom.shapeshifter.engine.match;

import stroom.shapeshifter.engine.config.Codec;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.MatchStep;
import stroom.shapeshifter.engine.text.RegexEncodings;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.regex.BytePattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Authored steps to {@link CompiledStep}: the compile-time half of the step vocabulary.
 *
 * <p>It lives beside {@link Steps} rather than with the rest of compilation because the two have
 * to agree. What a character class means, what bytes a literal is, which byte values can be
 * settled from a table — each is one rule, stated once, used by the compiler to bake an answer
 * and by the interpreter to read it. Splitting them across packages is how they drift apart.
 */
public final class StepCompiler {

    private StepCompiler() {
    }

    /**
     * Compile a step sequence: one {@link CompiledStep} per authored step, with everything the
     * interpreter would otherwise work out per attempt worked out here instead (design 29 §3.3).
     *
     * <p>A literal is encoded, a regex is interned and given its own matcher, a predicate is
     * turned into a byte table where the reading admits one. Interning uses the same map the
     * rest of compilation does, so a pattern written twice compiles once, but each occurrence
     * gets its own compiled step and so its own matcher.
     *
     * <p>A codec this build cannot apply is refused here rather than returning nothing at match
     * time, because "no match" and "cannot do that" are different answers and only one of them
     * is the configuration's fault.
     *
     * @param steps        the authored steps, pattern references already inlined
     * @param templateName only for the messages a refusal carries
     * @param decoding     the encoding to compile for, and how bytes read as characters under it
     * @param patterns     compiles and interns a pattern, so that a pattern a step names and one
     *                     a body names are the same compiled pattern
     */
    public static List<CompiledStep> compile(final List<MatchStep> steps,
                                             final String templateName,
                                             final Decoding decoding,
                                             final Function<PatternKey, BytePattern> patterns) {
        // Never null here: a transcode-family source is decoded before anything matches and a
        // template may not declare one, so the lowering exists (RegexEncodings.forMatch says why).
        return compile(steps, templateName, decoding, patterns,
                RegexEncodings.forMatch(decoding.encoding()));
    }

    private static List<CompiledStep> compile(final List<MatchStep> steps,
                                              final String templateName,
                                              final Decoding decoding,
                                              final Function<PatternKey, BytePattern> patterns,
                                              final stroom.shapeshifter.regex.Encoding encoding) {
        final List<CompiledStep> compiled = new ArrayList<>(steps.size());
        for (final MatchStep step : steps) {
            final CompiledStep one = switch (step) {
                case MatchStep.Tag tag -> {
                    final byte[] bytes = decoding.encoding().encode(tag.value());
                    yield new CompiledStep.Tag(bytes, TypedValue.of(bytes, decoding.encoding()));
                }
                case MatchStep.MatchByte value -> new CompiledStep.MatchByte(
                        value.value(), TypedValue.of(value.value(), decoding.encoding()));
                case MatchStep.TakeWhile takeWhile -> new CompiledStep.TakeWhile(
                        takeWhile.predicate(), Steps.table(takeWhile.predicate(), decoding));
                case MatchStep.TakeUntil takeUntil -> new CompiledStep.TakeUntil(
                        decoding.encoding().encode(takeUntil.pattern()), takeUntil.inclusive());
                case MatchStep.TakeBytes takeBytes -> new CompiledStep.TakeBytes(takeBytes.count());
                case MatchStep.TakeN takeN -> new CompiledStep.TakeN(takeN.count());
                case MatchStep.AnyChar ignored -> new CompiledStep.AnyChar();
                case MatchStep.Regex regex -> new CompiledStep.Regex(patterns.apply(
                        PatternKey.of(regex.pattern(), regex.flags(), encoding)));
                case MatchStep.ReadNumeric numeric -> new CompiledStep.ReadNumeric(
                        numeric.numericType(), numeric.signed(), numeric.endian());
                case MatchStep.ReadVarint ignored -> new CompiledStep.ReadVarint();
                case MatchStep.ReadVarintZigZag ignored -> new CompiledStep.ReadVarintZigZag();
                case MatchStep.Seek seek -> new CompiledStep.Seek(seek.count());
                case MatchStep.SeekAbs seek -> new CompiledStep.SeekAbs(seek.offset());
                case MatchStep.SeekBack seek -> new CompiledStep.SeekBack(seek.count());
                case MatchStep.Tell ignored -> new CompiledStep.Tell();
                case MatchStep.Decode decode -> {
                    requireCodec(decode.codec(), templateName);
                    yield new CompiledStep.Decode(decode.data(), decode.codec());
                }
                case MatchStep.Encode encode -> {
                    requireCodec(encode.codec(), templateName);
                    yield new CompiledStep.Encode(encode.data(), encode.codec());
                }
                case MatchStep.Choice choice -> new CompiledStep.Choice(
                        choice.alternatives().stream()
                                .map(alternative -> compile(alternative, templateName, decoding, patterns, encoding))
                                .toList());
                case MatchStep.Optional optional -> new CompiledStep.Optional(
                        compile(optional.steps(), templateName, decoding, patterns, encoding));
                case MatchStep.Repeat repeat -> new CompiledStep.Repeat(
                        compile(repeat.steps(), templateName, decoding, patterns, encoding),
                        repeat.min(), repeat.max());
                case MatchStep.Sequence sequence -> new CompiledStep.Sequence(
                        compile(sequence.steps(), templateName, decoding, patterns, encoding));
                case MatchStep.Peek peek -> new CompiledStep.Peek(
                        compile(peek.steps(), templateName, decoding, patterns, encoding));
                case MatchStep.Not not -> new CompiledStep.Not(
                        compile(not.steps(), templateName, decoding, patterns, encoding));
                // Inlined by resolve() before this walk sees the sequence (D8), so there is
                // nothing left to compile and no compiled form to compile it into.
                case MatchStep.PatternRef ignored -> throw new IllegalStateException(
                        "Pattern references should have been resolved before compilation");
            };
            compiled.add(one);
        }
        return compiled;
    }

    private static void requireCodec(final Codec codec, final String templateName) {
        if (!Codecs.isSupported(codec)) {
            throw ConfigException.notYet(templateName, codec.name().toLowerCase(Locale.ROOT) + " coding");
        }
    }
}
