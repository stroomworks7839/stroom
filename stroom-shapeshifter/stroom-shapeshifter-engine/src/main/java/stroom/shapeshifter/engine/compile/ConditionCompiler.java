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
import stroom.shapeshifter.engine.graph.CompiledCondition;
import stroom.shapeshifter.engine.graph.CompiledOperand;
import stroom.shapeshifter.engine.match.PatternKey;
import stroom.shapeshifter.engine.value.Comparisons;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.regex.BytePattern;

import java.util.List;
import java.util.Map;

/**
 * Conditions, compiled: each {@code matches} test given the pattern it runs, each reference given
 * the slot it means, and each literal operand finished.
 *
 * <p>A pattern can nest arbitrarily deep inside {@code and}, {@code or} and {@code not}, which is
 * why there is a compiled condition tree at all — there was nowhere else to put the compiled
 * pattern (design 30 phase 2). Resolving it here rather than per evaluation also takes the
 * pattern map out of the run: {@code Conditions.evaluate} does not take one, so nothing threads
 * it through a guard evaluation on every template on every record.
 */
final class ConditionCompiler {

    private ConditionCompiler() {
    }

    /**
     * Compile a condition, resolving each {@code matches} pattern against the patterns the match
     * compiler interned. This is the only place that reads that map for a condition, and it runs
     * once per authored condition rather than once per evaluation.
     *
     * @param condition the authored condition, or null for a template with no guard
     * @param patterns  every pattern the configuration compiled, by key
     * @param names     the interner, so a condition's references become slots like any other
     * @return the compiled form, or null when there was no condition
     */
    static CompiledCondition compile(final Condition condition,
                                     final Map<PatternKey, BytePattern> patterns,
                                     final Interner names) {
        return switch (condition) {
            case null -> null;
            case final Condition.Compare value -> new CompiledCondition.Compare(value.op(),
                    operand(value.left(), names),
                    operand(value.right(), names));
            case final Condition.Matches value -> {
                final BytePattern pattern = patterns.get(PatternKey.ofValue(value.pattern()));
                if (pattern == null) {
                    // The match compiler interns every pattern a condition names, including the
                    // ones nested in a body; a miss here means it stopped walking somewhere.
                    throw new IllegalStateException("Pattern was not compiled: " + value.pattern());
                }
                yield new CompiledCondition.Matches(
                        RefCompiler.compile(value.select(), names), pattern);
            }
            case final Condition.Contains value -> new CompiledCondition.Contains(
                    RefCompiler.compile(value.select(), names), value.substring());
            case final Condition.StartsWith value -> new CompiledCondition.StartsWith(
                    RefCompiler.compile(value.select(), names), value.prefix());
            case final Condition.And value ->
                    new CompiledCondition.And(all(value.conditions(), patterns, names));
            case final Condition.Or value ->
                    new CompiledCondition.Or(all(value.conditions(), patterns, names));
            case final Condition.Not value ->
                    new CompiledCondition.Not(compile(value.condition(), patterns, names));
            case final Condition.Exists value ->
                    new CompiledCondition.Exists(RefCompiler.compile(value.select(), names));
            case final Condition.IsFirst ignored -> new CompiledCondition.IsFirst();
            case final Condition.IsLast ignored -> new CompiledCondition.IsLast();
        };
    }

    private static CompiledCondition[] all(final List<Condition> conditions,
                                           final Map<PatternKey, BytePattern> patterns,
                                           final Interner names) {
        return conditions.stream()
                .map(child -> compile(child, patterns, names))
                .toArray(CompiledCondition[]::new);
    }

    /**
     * Compile one side of a comparison.
     *
     * <p>A reference becomes a {@link stroom.shapeshifter.engine.graph.CompiledRef}. <b>A literal
     * is finished here</b>: the authored form allocated its value and applied its declared cast on
     * every evaluation, and both are constant. Where the cast fails the value is absent, which is
     * what it was per evaluation too — a comparison that cannot be made is false, {@code ne}
     * included (design/17 §8).
     */
    private static CompiledOperand operand(final Condition.Operand operand, final Interner names) {
        if (operand.ref() != null) {
            return new CompiledOperand(
                    RefCompiler.compile(operand.ref(), names), null, operand.as());
        }
        final TypedValue value = switch (operand.literal()) {
            case final Condition.Literal.Text text -> TypedValue.of(text.value());
            case final Condition.Literal.Whole whole -> new TypedValue.Integer(whole.value());
            case final Condition.Literal.Fractional fraction ->
                    new TypedValue.Double(fraction.value());
            case final Condition.Literal.Truth truth -> new TypedValue.Bool(truth.value());
        };
        return new CompiledOperand(null, Comparisons.cast(value, operand.as()), null);
    }
}
