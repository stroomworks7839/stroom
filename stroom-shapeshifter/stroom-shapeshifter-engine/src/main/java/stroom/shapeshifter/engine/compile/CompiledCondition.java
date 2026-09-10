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

import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.match.PatternKey;
import stroom.shapeshifter.regex.BytePattern;

import java.util.List;
import java.util.Map;

/**
 * A condition, compiled.
 *
 * <p>The vocabulary is the authored {@link Condition}'s, kind for kind, with one difference that
 * is the whole reason this exists: a {@code matches} test holds the {@link BytePattern} it runs,
 * where the authored form holds the pattern's <em>text</em> and the interpreter hashed that text
 * against the project's pattern map on every evaluation. A pattern can nest arbitrarily deep
 * inside {@code and}, {@code or} and {@code not}, so there is nowhere to put the compiled one
 * without a compiled tree to hold it.
 *
 * <p>That makes every compiled node hold its own pattern without exception —
 * {@link CompiledMatch.Regex} since design 10, {@code CompiledStep.Regex} since design 29 phase
 * 2, the body's regex replace since phase 3, and now conditions. It also takes the map out of
 * the run: {@code Conditions.evaluate} no longer takes one, so nothing threads it through a
 * guard evaluation on every template on every record.
 *
 * <p><b>What this deliberately does not compile</b> is the references. A condition still resolves
 * its {@link RefExpression} operands through {@code Refs}, walking the authored form, because
 * design 29 phase 4 measured that path at 0.4% of {@code win_sec_strict} and 0.7% of
 * {@code ausearch} and E39 is deferred on that measurement. Compiling the tree does not reopen
 * it; the refs travel unchanged.
 */
public sealed interface CompiledCondition {

    /** Two operands compared, each a reference or a literal, each with its declared cast. */
    record Compare(Condition.Compare.Op op,
                   Condition.Operand left,
                   Condition.Operand right) implements CompiledCondition {

    }

    /** A value matched against a pattern this node holds. */
    record Matches(RefExpression select, BytePattern pattern) implements CompiledCondition {

    }

    /** A value containing a substring. */
    record Contains(RefExpression select, String substring) implements CompiledCondition {

    }

    /** A value starting with a prefix. */
    record StartsWith(RefExpression select, String prefix) implements CompiledCondition {

    }

    /** Every one of them. */
    record And(List<CompiledCondition> conditions) implements CompiledCondition {

        public And {
            conditions = List.copyOf(conditions);
        }
    }

    /** Any one of them. */
    record Or(List<CompiledCondition> conditions) implements CompiledCondition {

        public Or {
            conditions = List.copyOf(conditions);
        }
    }

    /** The opposite. */
    record Not(CompiledCondition condition) implements CompiledCondition {

    }

    /** Whether there was a value at all, which is not the same question as what it equals. */
    record Exists(RefExpression select) implements CompiledCondition {

    }

    /** The iteration's first position. */
    record IsFirst() implements CompiledCondition {

    }

    /** The iteration's last position. */
    record IsLast() implements CompiledCondition {

    }

    /**
     * Compile a condition, resolving each {@code matches} pattern against the patterns the match
     * compiler interned. This is the only place that reads that map for a condition, and it runs
     * once per authored condition rather than once per evaluation.
     *
     * @param condition the authored condition, or null for a template with no guard
     * @param patterns  every pattern the configuration compiled, by key
     * @return the compiled form, or null when there was no condition
     */
    static CompiledCondition of(final Condition condition,
                                final Map<PatternKey, BytePattern> patterns,
                                final VarNames names) {
        return switch (condition) {
            case null -> null;
            case final Condition.Compare value -> {
                intern(value.left(), names);
                intern(value.right(), names);
                yield new Compare(value.op(), value.left(), value.right());
            }
            case final Condition.Matches value -> {
                names.intern(value.select());
                final BytePattern pattern = patterns.get(PatternKey.ofValue(value.pattern()));
                if (pattern == null) {
                    // The match compiler interns every pattern a condition names, including the
                    // ones nested in a body; a miss here means it stopped walking somewhere.
                    throw new IllegalStateException("Pattern was not compiled: " + value.pattern());
                }
                yield new Matches(value.select(), pattern);
            }
            case final Condition.Contains value -> {
                names.intern(value.select());
                yield new Contains(value.select(), value.substring());
            }
            case final Condition.StartsWith value -> {
                names.intern(value.select());
                yield new StartsWith(value.select(), value.prefix());
            }
            case final Condition.And value -> new And(all(value.conditions(), patterns, names));
            case final Condition.Or value -> new Or(all(value.conditions(), patterns, names));
            case final Condition.Not value -> new Not(of(value.condition(), patterns, names));
            case final Condition.Exists value -> {
                names.intern(value.select());
                yield new Exists(value.select());
            }
            case final Condition.IsFirst ignored -> new IsFirst();
            case final Condition.IsLast ignored -> new IsLast();
        };
    }

    private static List<CompiledCondition> all(final List<Condition> conditions,
                                               final Map<PatternKey, BytePattern> patterns,
                                               final VarNames names) {
        return conditions.stream().map(child -> of(child, patterns, names)).toList();
    }

    /**
     * Intern the names an operand reads, so the table means every name the configuration
     * mentions rather than every name a compiled node holds.
     *
     * <p>A condition still resolves the <em>authored</em> expression (E39 owns that seam), so
     * these names never reach a {@link CompiledRef}: the guard looks the name up by string while
     * the record runs. <b>That is safe without this</b>, and the reason is worth stating because
     * it is not obvious — whatever <i>writes</i> a name interns it, and the compiler refuses a
     * read of a name nothing writes, so a guard's name is always in the table already. Where it
     * is not, both the write and the read go through the <em>same</em> run-time map and agree on
     * the slot they invent. Interning here is completeness, not correctness.
     */
    private static void intern(final Condition.Operand operand, final VarNames names) {
        if (operand != null) {
            names.intern(operand.ref());
        }
    }
}
