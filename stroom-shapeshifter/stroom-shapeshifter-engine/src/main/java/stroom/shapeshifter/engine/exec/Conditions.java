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

import stroom.shapeshifter.engine.compile.PatternKey;
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.regex.BytePattern;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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

    /**
     * Evaluate a condition.
     *
     * @param patterns the project's compiled patterns, keyed by their text
     */
    public static boolean evaluate(final Condition condition,
                                   final MatchResult match,
                                   final int matchCount,
                                   final VarRegistry vars,
                                   final Encoding encoding,
                                   final Map<PatternKey, BytePattern> patterns) {
        return switch (condition) {
            case Condition.Compare value -> {
                final TypedValue left = operand(value.left(), match, matchCount, vars, encoding);
                final TypedValue right = operand(value.right(), match, matchCount, vars, encoding);
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
            case Condition.Matches value -> {
                // Conditions match resolved values, and a value's internal form is UTF-8
                // whatever the feed's encoding — so the pattern is the UTF-8 compilation,
                // always. Only the match vocabulary sees feed bytes (design 19 phase 3).
                final BytePattern pattern = patterns.get(
                        PatternKey.of(value.pattern(), stroom.shapeshifter.regex.Encoding.UTF_8));
                if (pattern == null) {
                    throw new IllegalStateException("Pattern was not compiled: " + value.pattern());
                }
                yield pattern.matcher().find(
                        text(value.select(), match, matchCount, vars, encoding).getBytes(StandardCharsets.UTF_8));
            }
            case Condition.Contains value ->
                    text(value.select(), match, matchCount, vars, encoding).contains(value.substring());
            case Condition.StartsWith value ->
                    text(value.select(), match, matchCount, vars, encoding).startsWith(value.prefix());
            case Condition.And value -> value.conditions().stream()
                    .allMatch(child -> evaluate(child, match, matchCount, vars, encoding, patterns));
            case Condition.Or value -> value.conditions().stream()
                    .anyMatch(child -> evaluate(child, match, matchCount, vars, encoding, patterns));
            case Condition.Not value -> !evaluate(value.condition(), match, matchCount, vars, encoding, patterns);
            // Restored with the iteration that sets them (design/16 §4.3). Outside a
            // for-each nothing sets __position, so both read false — E21's hazard, which the
            // compiler now warns about rather than leaving to be discovered.
            case Condition.IsFirst ignored -> {
                final Long position = engineNumber(vars, EngineVars.POSITION);
                yield position != null && position == 1L;
            }
            case Condition.IsLast ignored -> {
                final Long position = engineNumber(vars, EngineVars.POSITION);
                final Long last = engineNumber(vars, EngineVars.LAST);
                yield position != null && position.equals(last);
            }
            case Condition.Exists value -> {
                final byte[] resolved = Refs.resolve(value.select(), match, matchCount, vars, encoding);
                yield resolved != null && resolved.length > 0;
            }
        };
    }

    /** An engine variable's current whole-number value, or null when nothing has set it. */
    private static Long engineNumber(final VarRegistry vars, final String name) {
        final List<Store> stores = vars.get(name);
        if (stores == null || stores.isEmpty()) {
            return null;
        }
        final TypedValue value = stores.getFirst().latest();
        return value == null ? null : value.asInteger();
    }

    private static String text(final RefExpression expression,
                               final MatchResult match,
                               final int matchCount,
                               final VarRegistry vars,
                               final Encoding encoding) {
        final String resolved = Refs.resolveText(expression, match, matchCount, vars, encoding);
        return resolved == null ? "" : resolved;
    }

    /** One side of a comparison: resolve or materialise, then the explicit cast, if any. */
    private static TypedValue operand(final Condition.Operand operand,
                                      final MatchResult match,
                                      final int matchCount,
                                      final VarRegistry vars,
                                      final Encoding encoding) {
        if (operand.ref() != null) {
            return Comparisons.cast(
                    Refs.resolveValue(operand.ref(), match, matchCount, vars, encoding),
                    operand.as());
        }
        final TypedValue value = switch (operand.literal()) {
            case Condition.Literal.Text text -> TypedValue.of(text.value());
            case Condition.Literal.Whole whole -> new TypedValue.Int(whole.value());
            case Condition.Literal.Fractional fraction -> new TypedValue.Real(fraction.value());
            case Condition.Literal.Truth truth -> new TypedValue.Bool(truth.value());
        };
        return Comparisons.cast(value, operand.as());
    }
}
