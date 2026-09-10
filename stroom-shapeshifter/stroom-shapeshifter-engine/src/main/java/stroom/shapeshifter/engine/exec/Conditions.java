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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.compile.CompiledCondition;
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.EngineVars;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.match.MatchResult;
import stroom.shapeshifter.engine.value.Comparisons;
import stroom.shapeshifter.engine.value.TypedValue;

import java.nio.charset.StandardCharsets;

/**
 * Deciding whether a condition holds.
 *
 * <p>Two rules run through all of it. A comparison that cannot be made — a side absent, a cast
 * that fails, a cross-kind pair — is false rather than an error, which is what a configuration
 * written against messy data needs. And the predicates treat an expression that resolves to
 * nothing as the empty string, for the same reason.
 *
 * <p>{@code Exists} is the one that distinguishes them: it asks whether there was a value at
 * all, which is a different question from whether the value equals something.
 */
public final class Conditions {

    private Conditions() {
    }

    /** Evaluate a compiled condition. */
    public static boolean evaluate(final CompiledCondition condition,
                                   final MatchResult match,
                                   final int matchCount,
                                   final VarRegistry vars) {
        return switch (condition) {
            case final CompiledCondition.Compare value -> {
                final TypedValue left = operand(value.left(), match, matchCount, vars);
                final TypedValue right = operand(value.right(), match, matchCount, vars);
                final Integer order = Comparisons.compare(left, right);
                if (order == null) {
                    // Absent, or a cross-kind pair: the comparison cannot be made, and a
                    // comparison that cannot be made is false — ne included (design/17 §8).
                    yield false;
                }
                yield switch (value.op()) {
                        case EQ -> order == 0;
                        case NE -> order != 0;
                        case LT -> order < 0;
                        case LE -> order <= 0;
                        case GT -> order > 0;
                        case GE -> order >= 0;
                    };
            }
            // The pattern is the node's own: compiled when the condition was, not found by
            // hashing its text on every evaluation (design 30).
            case final CompiledCondition.Matches value -> value.pattern().matcher().find(
                    text(value.select(), match, matchCount, vars)
                            .getBytes(StandardCharsets.UTF_8));
            case final CompiledCondition.Contains value ->
                    text(value.select(), match, matchCount, vars).contains(value.substring());
            case final CompiledCondition.StartsWith value ->
                    text(value.select(), match, matchCount, vars).startsWith(value.prefix());
            case final CompiledCondition.And value -> value.conditions().stream()
                    .allMatch(child -> evaluate(child, match, matchCount, vars));
            case final CompiledCondition.Or value -> value.conditions().stream()
                    .anyMatch(child -> evaluate(child, match, matchCount, vars));
            case final CompiledCondition.Not value ->
                    !evaluate(value.condition(), match, matchCount, vars);
            // Set by the iteration (design/16 §4.3). Outside a
            // for-each nothing sets __position, so both read false — E21's hazard, which the
            // compiler now warns about rather than leaving to be discovered.
            case final CompiledCondition.IsFirst ignored -> {
                final Long position = engineNumber(vars, EngineVars.POSITION);
                yield position != null && position == 1L;
            }
            case final CompiledCondition.IsLast ignored -> {
                final Long position = engineNumber(vars, EngineVars.POSITION);
                final Long last = engineNumber(vars, EngineVars.LAST);
                yield position != null && position.equals(last);
            }
            case final CompiledCondition.Exists value -> {
                final byte[] resolved = Refs.resolve(value.select(), match, matchCount, vars);
                yield resolved != null && resolved.length > 0;
            }
        };
    }

    /** An engine variable's current whole-number value, or null when its frame is not open. */
    private static Long engineNumber(final VarRegistry vars, final EngineVars var) {
        final TypedValue value = vars.frames().value(var);
        return value == null ? null : value.asInteger();
    }

    private static String text(final RefExpression expression,
                               final MatchResult match,
                               final int matchCount,
                               final VarRegistry vars) {
        final String resolved = Refs.resolveText(expression, match, matchCount, vars);
        return resolved == null ? "" : resolved;
    }

    /** One side of a comparison: resolve or materialise, then the explicit cast, if any. */
    private static TypedValue operand(final Condition.Operand operand,
                                      final MatchResult match,
                                      final int matchCount,
                                      final VarRegistry vars) {
        if (operand.ref() != null) {
            return Comparisons.cast(
                    Refs.resolveValue(operand.ref(), match, matchCount, vars),
                    operand.as());
        }
        final TypedValue value = switch (operand.literal()) {
            case final Condition.Literal.Text text -> TypedValue.of(text.value());
            case final Condition.Literal.Whole whole -> new TypedValue.Integer(whole.value());
            case final Condition.Literal.Fractional fraction -> new TypedValue.Double(fraction.value());
            case final Condition.Literal.Truth truth -> new TypedValue.Bool(truth.value());
        };
        return Comparisons.cast(value, operand.as());
    }
}
