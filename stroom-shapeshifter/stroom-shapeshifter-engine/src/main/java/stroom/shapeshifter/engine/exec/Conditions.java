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

import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.regex.BytePattern;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Deciding whether a condition holds.
 *
 * <p>Two rules run through all of it. An expression that resolves to nothing counts as the empty
 * string, so a comparison against a missing field is false rather than an error — which is what
 * a configuration written against messy data needs. And a numeric comparison against something
 * that is not a number is false, not an exception, for the same reason.
 *
 * <p>{@code Exists} is the one that distinguishes them: it asks whether there was a value at
 * all, which is a different question from whether the value equals something.
 */
public final class Conditions {

    /** The engine's own variables, set while iterating, that {@code IsFirst}/{@code IsLast} read. */
    private static final String IS_FIRST = "__foreach_is_first";
    private static final String IS_LAST = "__foreach_is_last";

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
                                   final Map<String, BytePattern> patterns) {
        return switch (condition) {
            case Condition.Equals value ->
                    text(value.select(), match, matchCount, vars).equals(value.value());
            case Condition.NotEquals value ->
                    !text(value.select(), match, matchCount, vars).equals(value.value());
            case Condition.RefEquals value ->
                    text(value.left(), match, matchCount, vars)
                            .equals(text(value.right(), match, matchCount, vars));
            case Condition.Matches value -> {
                final BytePattern pattern = patterns.get(value.pattern());
                if (pattern == null) {
                    throw new IllegalStateException("Pattern was not compiled: " + value.pattern());
                }
                yield pattern.matcher().find(
                        text(value.select(), match, matchCount, vars).getBytes(StandardCharsets.UTF_8));
            }
            case Condition.Contains value ->
                    text(value.select(), match, matchCount, vars).contains(value.substring());
            case Condition.StartsWith value ->
                    text(value.select(), match, matchCount, vars).startsWith(value.prefix());
            case Condition.GreaterThan value -> {
                final Double number = number(value.select(), match, matchCount, vars);
                yield number != null && number > value.value();
            }
            case Condition.LessThan value -> {
                final Double number = number(value.select(), match, matchCount, vars);
                yield number != null && number < value.value();
            }
            case Condition.And value -> value.conditions().stream()
                    .allMatch(child -> evaluate(child, match, matchCount, vars, patterns));
            case Condition.Or value -> value.conditions().stream()
                    .anyMatch(child -> evaluate(child, match, matchCount, vars, patterns));
            case Condition.Not value -> !evaluate(value.condition(), match, matchCount, vars, patterns);
            case Condition.Exists value -> {
                final byte[] resolved = Refs.resolve(value.select(), match, matchCount, vars);
                yield resolved != null && resolved.length > 0;
            }
            case Condition.IsFirst ignored -> flag(IS_FIRST, matchCount, vars);
            case Condition.IsLast ignored -> flag(IS_LAST, matchCount, vars);
        };
    }

    private static String text(final stroom.shapeshifter.engine.config.RefExpression expression,
                               final MatchResult match,
                               final int matchCount,
                               final VarRegistry vars) {
        final String resolved = Refs.resolveText(expression, match, matchCount, vars);
        return resolved == null ? "" : resolved;
    }

    private static Double number(final stroom.shapeshifter.engine.config.RefExpression expression,
                                 final MatchResult match,
                                 final int matchCount,
                                 final VarRegistry vars) {
        try {
            return Double.valueOf(text(expression, match, matchCount, vars).trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** Read one of the iteration flags the engine sets while looping. */
    private static boolean flag(final String name, final int matchCount, final VarRegistry vars) {
        final List<Store> stores = vars.get(name);
        if (stores == null || stores.isEmpty()) {
            return false;
        }
        final TypedValue value = stores.getFirst().get(matchCount);
        return switch (value) {
            case null -> false;
            case TypedValue.Bool bool -> bool.value();
            case TypedValue.Bytes bytes -> "true".equals(bytes.toString());
            default -> false;
        };
    }
}
