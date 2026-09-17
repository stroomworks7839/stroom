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

package stroom.shapeshifter.config;

import java.util.List;

/**
 * A test that yields true or false without consuming anything.
 *
 * <p>Conditions appear in two places, and the difference matters. As a template's <b>guard</b> a
 * condition runs before matching, so it can only see scope — variables, parameters, depth. In a
 * body's {@code If} or {@code Choose} it runs after matching, so it can see the captures too.
 *
 * <p>The comparisons are deliberately few and deliberately typed. There is no general expression
 * evaluator here, and adding one should be a decision rather than a drift.
 */
public sealed interface Condition {

    /**
     * A strict typed comparison — {@code eq}/{@code ne}/{@code lt}/{@code le}/{@code gt}/
     * {@code ge}, XPath 2.0's value-comparison operators (design/17 §8). Same kind compares
     * natively ({@code Int}↔{@code Real} promoting within the numeric kind), a cross-kind
     * comparison is <b>false</b>, and the cast is explicit on the operand — there is no
     * coercion. The legacy spellings {@code greater-than} and {@code less-than} read as aliases
     * carrying the cast their semantics always implied, {@code as: "number"} on the left. The
     * string-equality trio — {@code equals}, {@code not-equals}, {@code ref-equals} — was
     * retired by design 35 phase 1: a string comparison is written as {@code eq} with
     * {@code as: "string"} on both sides, so that the comparison is said rather than implied
     * by a spelling.
     */
    record Compare(Op op, Operand left, Operand right) implements Condition {

        public Compare {
            if (op == null || left == null || right == null) {
                throw new ConfigException("A comparison needs an operator and two operands");
            }
        }

        /** The six operators. */
        public enum Op {
            EQ, NE, LT, LE, GT, GE
        }
    }

    /**
     * One side of a {@link Compare}: a reference or a literal, optionally read through a
     * cast. A literal's JSON type is its declared type — a JSON string is untyped bytes, a
     * JSON number is whole or fractional, a JSON boolean is a boolean.
     */
    record Operand(RefExpression ref, Literal literal, Cast as) {

        public Operand {
            if ((ref == null) == (literal == null)) {
                throw new ConfigException("An operand is a ref or a literal, exactly one");
            }
        }
    }

    /** A literal operand, carrying its declared type. */
    sealed interface Literal {

        /** A string literal — untyped, like a capture. */
        record Text(String value) implements Literal {

        }

        /** A whole-number literal. */
        record Whole(long value) implements Literal {

        }

        /** A fractional literal. */
        record Fractional(double value) implements Literal {

        }

        /** A boolean literal. */
        record Truth(boolean value) implements Literal {

        }
    }

    /**
     * The value matches a regex.
     *
     * @param select  the value to test
     * @param pattern the pattern, in {@link stroom.shapeshifter.regex.BytePattern}'s dialect
     */
    record Matches(RefExpression select, String pattern) implements Condition {

    }

    /** The value contains a substring. */
    record Contains(RefExpression select, String substring) implements Condition {

    }

    /** The value starts with a prefix. */
    record StartsWith(RefExpression select, String prefix) implements Condition {

    }

    /** Every sub-condition holds. */
    record And(List<Condition> conditions) implements Condition {

        public And {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }
    }

    /** At least one sub-condition holds. */
    record Or(List<Condition> conditions) implements Condition {

        public Or {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }
    }

    /** The sub-condition does not hold. */
    record Not(Condition condition) implements Condition {

    }

    /** The value is set and not empty. */
    record Exists(RefExpression select) implements Condition {

    }

    /**
     * The current iteration is on its first entry — {@code position() == 1}.
     *
     * <p>Deleted by E21 as vocabulary nothing set, and restored by design/16's ruling with
     * the iteration that sets it: E21's own words were "delete until a case needs for-each
     * positional index and count semantics", and this is that case. Outside an iteration
     * nothing sets {@code position()} and the condition is false, which is the hazard that
     * got it deleted — so the compiler warns when it appears in a body with no enclosing
     * {@code for-each}.
     */
    record IsFirst() implements Condition {

    }

    /** The current iteration is on its last entry — {@code position() == last()}. */
    record IsLast() implements Condition {

    }
}
