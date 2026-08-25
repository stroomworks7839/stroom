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

package stroom.shapeshifter.engine.config;

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
     * coercion. The legacy spellings {@code equals}, {@code not-equals}, {@code ref-equals},
     * {@code greater-than} and {@code less-than} read for ever as aliases carrying the casts
     * their semantics always implied: {@code as: "string"} on both sides for the equality
     * trio — the engine's counters are already typed, and legacy equality compares string
     * forms — and {@code as: "number"} on the left for the ordered pair.
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
}
