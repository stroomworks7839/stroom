/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.client.model;

import stroom.shapeshifter.config.Cast;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.Condition.Compare;
import stroom.shapeshifter.config.Condition.Literal;
import stroom.shapeshifter.config.Condition.Operand;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.json.ProjectJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// One row of the guard editor — *variable · operator · value*, optionally negated (design 18
/// §5.6) — and the reading of a [Condition] as rows joined by *and* or by *or*. A guard the rows
/// cannot express (a comparison between two references, a path reference, or any nesting deeper
/// than one level) reads as null, and the editor shows its wire form instead: the model is the
/// whole vocabulary, the rows are the common case.
///
/// <p>The rows express a clause, a negated clause, and an *and* or an *or* of those, because
/// that is what configurations are made of: of the 476 conditions across every project fixture,
/// 474 are one of those four shapes and none nests further (design 46 §7.3). A condition that
/// does nest — `not(not(x))` is the only one in the corpus — stays on the wire form rather than
/// being quietly rewritten into something simpler.
///
/// <p>The shape is not the only thing that decides: a clause's *select* must also read back as a
/// name, which [stroom.shapeshifter.config.json.ReferenceJson] allows only for a bare declared
/// name or a counter. A comparison of a bare group, a label, a path or an accessor is still the
/// wire form's, whatever its shape, and that is now the commoner reason to see JSON: 77 of the
/// 476 remain on it, where 234 did before the rows learnt *or* and *not*.
public final class GuardClause {

    /// The operators a row offers, spelt as the wire format spells them.
    public enum Op {
        EQ("eq", true, true),
        NE("ne", true, true),
        LT("lt", true, true),
        LE("le", true, true),
        GT("gt", true, true),
        GE("ge", true, true),
        MATCHES("matches", true, true),
        CONTAINS("contains", true, true),
        STARTS_WITH("starts-with", true, true),
        EXISTS("exists", true, false),
        IS_FIRST("is-first", false, false),
        IS_LAST("is-last", false, false);

        private final String spelling;
        private final boolean takesVariable;
        private final boolean takesValue;

        Op(final String spelling, final boolean takesVariable, final boolean takesValue) {
            this.spelling = spelling;
            this.takesVariable = takesVariable;
            this.takesValue = takesValue;
        }

        public String spelling() {
            return spelling;
        }

        public boolean takesVariable() {
            return takesVariable;
        }

        public boolean takesValue() {
            return takesValue;
        }

        public boolean isComparison() {
            return ordinal() <= GE.ordinal();
        }
    }

    private final String variable;
    private final Op op;
    private final String value;
    private final Cast as;
    private final boolean negated;

    public GuardClause(final String variable, final Op op, final String value, final Cast as) {
        this(variable, op, value, as, false);
    }

    public GuardClause(final String variable, final Op op, final String value, final Cast as,
                       final boolean negated) {
        this.variable = variable == null
                ? ""
                : variable;
        this.op = op;
        this.value = value == null
                ? ""
                : value;
        this.as = as;
        this.negated = negated;
    }

    public String getVariable() {
        return variable;
    }

    public Op getOp() {
        return op;
    }

    public String getValue() {
        return value;
    }

    /// The typed reading of the variable before a comparison, or null for the text it is.
    public Cast getAs() {
        return as;
    }

    /// Whether the row holds where its clause does *not*: the clause wrapped in a [Condition.Not].
    public boolean isNegated() {
        return negated;
    }

    /// This row negated, or not.
    ///
    /// @param negate Whether it holds where the clause does not.
    /// @return The row.
    public GuardClause withNegated(final boolean negate) {
        return new GuardClause(variable, op, value, as, negate);
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof GuardClause that
               && variable.equals(that.variable)
               && op == that.op
               && value.equals(that.value)
               && as == that.as
               && negated == that.negated;
    }

    @Override
    public int hashCode() {
        return Objects.hash(variable, op, value, as, negated);
    }

    /// An untouched new row: an operator that wants a variable and a value, and neither given.
    public boolean isBlank() {
        return op != null && op.takesVariable() && variable.trim().isEmpty() && value.trim().isEmpty();
    }

    /// The rows a guard reads as, or null when the rows cannot say it.
    ///
    /// @param guard The guard, or null for none.
    /// @return Its rows and how they are joined, or null for a guard that must be edited as its
    /// wire form.
    public static Rows read(final Condition guard) {
        if (guard == null) {
            return new Rows(new ArrayList<>(), Join.ALL);
        }
        final Join join = guard instanceof Condition.Or
                ? Join.ANY
                : Join.ALL;
        final List<Condition> parts;
        if (guard instanceof Condition.And and) {
            parts = and.conditions();
        } else if (guard instanceof Condition.Or or) {
            parts = or.conditions();
        } else {
            parts = List.of(guard);
        }
        final List<GuardClause> clauses = new ArrayList<>();
        for (final Condition part : parts) {
            // A part may be negated, and that is the only nesting the rows allow: a not of
            // anything but a clause — not(not(x)), not(and(...)) — belongs to the wire form.
            final boolean negated = part instanceof Condition.Not;
            final Condition inner = negated
                    ? ((Condition.Not) part).condition()
                    : part;
            final GuardClause clause = readOne(inner);
            if (clause == null) {
                return null;
            }
            clauses.add(clause.withNegated(negated));
        }
        return new Rows(clauses, join);
    }

    private static GuardClause readOne(final Condition c) {
        if (c instanceof Compare compare) {
            final Operand left = compare.left();
            final Operand right = compare.right();
            if (left.ref() == null || right.literal() == null || right.as() != null) {
                return null;
            }
            final String variable = ProjectJson.refOrName(left.ref());
            return variable == null
                    ? null
                    : new GuardClause(variable, Op.valueOf(compare.op().name()), spell(right.literal()), left.as());
        } else if (c instanceof Condition.Matches m) {
            return simple(m.select(), Op.MATCHES, m.pattern());
        } else if (c instanceof Condition.Contains m) {
            return simple(m.select(), Op.CONTAINS, m.substring());
        } else if (c instanceof Condition.StartsWith m) {
            return simple(m.select(), Op.STARTS_WITH, m.prefix());
        } else if (c instanceof Condition.Exists e) {
            return simple(e.select(), Op.EXISTS, "");
        } else if (c instanceof Condition.IsFirst) {
            return new GuardClause("", Op.IS_FIRST, "", null);
        } else if (c instanceof Condition.IsLast) {
            return new GuardClause("", Op.IS_LAST, "", null);
        }
        return null;
    }

    private static GuardClause simple(final RefExpression ref, final Op op, final String value) {
        final String variable = ProjectJson.refOrName(ref);
        return variable == null
                ? null
                : new GuardClause(variable, op, value, null);
    }

    /// A literal as the value field shows it: text in quotes, so that "123" and 123 stay apart.
    private static String spell(final Literal literal) {
        if (literal instanceof Literal.Text t) {
            return "\"" + t.value() + "\"";
        } else if (literal instanceof Literal.Whole w) {
            return String.valueOf(w.value());
        } else if (literal instanceof Literal.Fractional f) {
            return String.valueOf(f.value());
        } else if (literal instanceof Literal.Truth b) {
            return String.valueOf(b.value());
        }
        return "";
    }

    /// The guard the rows mean: null for none, the one clause for one, else joined.
    ///
    /// @param clauses The rows.
    /// @param join    How they are joined, where there is more than one.
    /// @return The condition, or null where there are no rows.
    public static Condition write(final List<GuardClause> clauses, final Join join) {
        final List<Condition> conditions = new ArrayList<>();
        for (final GuardClause clause : clauses) {
            final Condition condition = clause.toCondition();
            conditions.add(clause.isNegated()
                    ? new Condition.Not(condition)
                    : condition);
        }
        if (conditions.isEmpty()) {
            return null;
        }
        if (conditions.size() == 1) {
            return conditions.get(0);
        }
        return join == Join.ANY
                ? new Condition.Or(conditions)
                : new Condition.And(conditions);
    }

    public Condition toCondition() {
        if (op == null) {
            throw new ConfigException("A clause needs an operator");
        }
        final RefExpression ref = op.takesVariable()
                ? ProjectJson.readRefOrName(required(variable, "A clause needs a variable"))
                : null;
        switch (op) {
            case MATCHES:
                return new Condition.Matches(ref, required(value, "matches needs a pattern"));
            case CONTAINS:
                return new Condition.Contains(ref, required(value, "contains needs a substring"));
            case STARTS_WITH:
                return new Condition.StartsWith(ref, required(value, "starts-with needs a prefix"));
            case EXISTS:
                return new Condition.Exists(ref);
            case IS_FIRST:
                return new Condition.IsFirst();
            case IS_LAST:
                return new Condition.IsLast();
            default:
                return new Compare(Compare.Op.valueOf(op.name()), new Operand(ref, null, as),
                        new Operand(null, literal(required(value, op.spelling() + " needs a value")), null));
        }
    }

    /// The value field's spelling declares the literal's type (design 17 §8): a whole number, a
    /// fractional one, true or false, or text - quoted when it would otherwise read as one of those.
    static Literal literal(final String text) {
        final String t = text.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return new Literal.Text(t.substring(1, t.length() - 1));
        }
        if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
            return new Literal.Truth(Boolean.parseBoolean(t.toLowerCase(Locale.ROOT)));
        }
        if (t.matches("-?[0-9]+")) {
            try {
                return new Literal.Whole(Long.parseLong(t));
            } catch (final NumberFormatException e) {
                throw new ConfigException("Too large a whole number: " + t);
            }
        }
        if (t.matches("-?[0-9]*\\.[0-9]+([eE][-+]?[0-9]+)?")) {
            return new Literal.Fractional(Double.parseDouble(t));
        }
        return new Literal.Text(t);
    }

    private static String required(final String text, final String message) {
        if (text == null || text.trim().isEmpty()) {
            throw new ConfigException(message);
        }
        return text.trim();
    }

    /// One line for the strip: `status ge 400 and user exists`, or `a not b` where a row is
    /// negated, joined by the word the rows are joined with.
    ///
    /// @param guard The guard, or null for none.
    /// @return The line.
    public static String describe(final Condition guard) {
        final Rows read = read(guard);
        if (read == null) {
            return "guarded (see workbench)";
        }
        if (read.rows().isEmpty()) {
            return "no guard";
        }
        final String joiner = read.join() == Join.ANY
                ? " or "
                : " and ";
        final StringBuilder sb = new StringBuilder();
        for (final GuardClause clause : read.rows()) {
            if (sb.length() > 0) {
                sb.append(joiner);
            }
            if (clause.op.takesVariable()) {
                sb.append(clause.variable).append(' ');
            }
            if (clause.negated) {
                sb.append("not ");
            }
            sb.append(clause.op.spelling());
            if (clause.op.takesValue()) {
                sb.append(' ').append(clause.value);
            }
        }
        return sb.toString();
    }

    /// How a condition's rows are joined: every one of them, or any one of them.
    public enum Join {
        /// Every row holds — an {@code and}.
        ALL("all"),
        /// At least one row holds — an {@code or}.
        ANY("any");

        private final String label;

        Join(final String label) {
            this.label = label;
        }

        /// @return The word the picker shows.
        public String label() {
            return label;
        }
    }

    /// A condition as the editor holds it: its rows, and how they are joined.
    ///
    /// @param rows The rows, in order.
    /// @param join How they are joined, where there is more than one.
    public record Rows(List<GuardClause> rows, Join join) {

    }
}
