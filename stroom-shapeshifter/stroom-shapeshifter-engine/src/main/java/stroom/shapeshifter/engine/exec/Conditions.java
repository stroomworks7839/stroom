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

import stroom.shapeshifter.engine.config.EngineVars;
import stroom.shapeshifter.engine.graph.CompiledCondition;
import stroom.shapeshifter.engine.graph.CompiledOperand;
import stroom.shapeshifter.engine.graph.CompiledRef;
import stroom.shapeshifter.engine.match.MatchResult;
import stroom.shapeshifter.engine.value.Comparisons;
import stroom.shapeshifter.engine.value.TypedValue;


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

    /** Absent resolves to nothing, which is what an empty subject says to a matcher. */
    private static final byte[] EMPTY = new byte[0];

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
            //
            // The subject is the value's bytes, not its text. Decoding and re-encoding used to
            // sit here, and did two things: it allocated a String and an array per evaluation,
            // and it substituted U+FFFD for anything undecodable — handing the pattern a
            // character to match where the input had none. D38 ruled that undecodable bytes
            // match nothing, and a value pattern already compiles for UTF-8 whatever the feed's
            // encoding (PatternKey.ofValue), so the value's own UTF-8 form is what it wants
            // (E45).
            case final CompiledCondition.Matches value -> {
                final byte[] subject =
                        CompiledRefs.resolve(value.select(), match, matchCount, vars);
                yield value.pattern().matcher().find(subject == null ? EMPTY : subject);
            }
            // These two decode, where matches above does not, and the difference is the
            // instruction rather than an oversight: a matches test runs a byte pattern, while
            // contains and starts-with compare text against authored text. So they still see a
            // replacement character where the input had an undecodable byte. Making them byte
            // operations would be equivalent for well-formed input — UTF-8 is self-synchronising,
            // so byte containment and character containment agree — and stricter for the rest;
            // E45 carries it as the follow-on rather than assuming it.
            case final CompiledCondition.Contains value ->
                    text(value.select(), match, matchCount, vars).contains(value.substring());
            case final CompiledCondition.StartsWith value ->
                    text(value.select(), match, matchCount, vars).startsWith(value.prefix());
            // A loop rather than a stream, and it is not only the array: the stream allocated a
            // pipeline and a capturing lambda on every evaluation of every and/or, which is
            // half of E46. Both still short-circuit, as allMatch and anyMatch did.
            case final CompiledCondition.And value -> {
                for (final CompiledCondition child : value.conditions()) {
                    if (!evaluate(child, match, matchCount, vars)) {
                        yield false;
                    }
                }
                yield true;
            }
            case final CompiledCondition.Or value -> {
                for (final CompiledCondition child : value.conditions()) {
                    if (evaluate(child, match, matchCount, vars)) {
                        yield true;
                    }
                }
                yield false;
            }
            case final CompiledCondition.Not value ->
                    !evaluate(value.condition(), match, matchCount, vars);
            // Set by the iteration (design/16 §4.3). Outside a
            // for-each nothing sets position(), so both read false — E21's hazard, which the
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
                final byte[] resolved =
                        CompiledRefs.resolve(value.select(), match, matchCount, vars);
                yield resolved != null && resolved.length > 0;
            }
        };
    }

    /** An engine variable's current whole-number value, or null when its frame is not open. */
    private static Long engineNumber(final VarRegistry vars, final EngineVars var) {
        final TypedValue value = vars.frames().value(var);
        return value == null ? null : value.asInteger();
    }

    private static String text(final CompiledRef ref,
                               final MatchResult match,
                               final int matchCount,
                               final VarRegistry vars) {
        final String resolved = CompiledRefs.resolveText(ref, match, matchCount, vars);
        return resolved == null ? "" : resolved;
    }

    /**
     * One side of a comparison.
     *
     * <p>A literal is already made and already cast — both are constant, and doing them per
     * evaluation is what design 30 phase 6 stopped. A reference resolves and then takes its
     * declared cast, which is not constant and cannot move.
     */
    private static TypedValue operand(final CompiledOperand operand,
                                      final MatchResult match,
                                      final int matchCount,
                                      final VarRegistry vars) {
        return operand.ref() == null
                ? operand.literal()
                : Comparisons.cast(
                        CompiledRefs.resolveValue(operand.ref(), match, matchCount, vars),
                        operand.as());
    }
}
