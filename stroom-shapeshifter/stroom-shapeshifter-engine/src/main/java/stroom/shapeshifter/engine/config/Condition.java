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

    /** The value equals a literal. */
    record Equals(RefExpression select, String value) implements Condition {

    }

    /** The value does not equal a literal. */
    record NotEquals(RefExpression select, String value) implements Condition {

    }

    /** Two values are equal to each other. */
    record RefEquals(RefExpression left, RefExpression right) implements Condition {

    }

    /**
     * The value matches a regex.
     *
     * @param select  the value to test
     * @param pattern the pattern, in the dialect of Rust's {@code regex} crate
     */
    record Matches(RefExpression select, String pattern) implements Condition {

    }

    /** The value contains a substring. */
    record Contains(RefExpression select, String substring) implements Condition {

    }

    /** The value starts with a prefix. */
    record StartsWith(RefExpression select, String prefix) implements Condition {

    }

    /** The value, read as a number, is greater than a threshold. */
    record GreaterThan(RefExpression select, double value) implements Condition {

    }

    /** The value, read as a number, is less than a threshold. */
    record LessThan(RefExpression select, double value) implements Condition {

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

    /** This is the first iteration of the enclosing loop. */
    record IsFirst() implements Condition {

    }

    /** This is the last iteration of the enclosing loop. */
    record IsLast() implements Condition {

    }
}
