/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.presenter;

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

/**
 * One row of the guard editor — <i>variable · operator · value</i> (design 18 §5.6) — and the
 * reading of a {@link Condition} as rows joined by <i>and</i>. A guard the rows cannot express
 * (an <i>or</i>, a <i>not</i>, a comparison between two references, a path reference) reads as
 * null, and the editor shows its wire form instead: the model is the whole vocabulary, the rows
 * are the common case.
 */
public final class GuardClause {

    /** The operators a row offers, spelt as the wire format spells them. */
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

    public GuardClause(final String variable, final Op op, final String value, final Cast as) {
        this.variable = variable == null
                ? ""
                : variable;
        this.op = op;
        this.value = value == null
                ? ""
                : value;
        this.as = as;
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

    /** The typed reading of the variable before a comparison, or null for the text it is. */
    public Cast getAs() {
        return as;
    }

    /** An untouched new row: an operator that wants a variable and a value, and neither given. */
    public boolean isBlank() {
        return op != null && op.takesVariable() && variable.trim().isEmpty() && value.trim().isEmpty();
    }

    /** The rows a guard reads as, or null when it is not rows joined by and. */
    public static List<GuardClause> read(final Condition guard) {
        final List<GuardClause> clauses = new ArrayList<>();
        if (guard == null) {
            return clauses;
        }
        final List<Condition> parts = guard instanceof Condition.And and
                ? and.conditions()
                : List.of(guard);
        for (final Condition part : parts) {
            final GuardClause clause = readOne(part);
            if (clause == null) {
                return null;
            }
            clauses.add(clause);
        }
        return clauses;
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

    /** A literal as the value field shows it: text in quotes, so that "123" and 123 stay apart. */
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

    /** The guard the rows mean: null for none, the one clause for one, else <i>and</i>. */
    public static Condition write(final List<GuardClause> clauses) {
        final List<Condition> conditions = new ArrayList<>();
        for (final GuardClause clause : clauses) {
            conditions.add(clause.toCondition());
        }
        if (conditions.isEmpty()) {
            return null;
        }
        return conditions.size() == 1
                ? conditions.get(0)
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

    /**
     * The value field's spelling declares the literal's type (design 17 §8): a whole number, a
     * fractional one, true or false, or text - quoted when it would otherwise read as one of those.
     */
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

    /** One line for the strip: {@code status ge 400 and user exists}. */
    public static String describe(final Condition guard) {
        final List<GuardClause> clauses = read(guard);
        if (clauses == null) {
            return "guarded (see workbench)";
        }
        if (clauses.isEmpty()) {
            return "no guard";
        }
        final StringBuilder sb = new StringBuilder();
        for (final GuardClause clause : clauses) {
            if (sb.length() > 0) {
                sb.append(" and ");
            }
            if (clause.op.takesVariable()) {
                sb.append(clause.variable).append(' ');
            }
            sb.append(clause.op.spelling());
            if (clause.op.takesValue()) {
                sb.append(' ').append(clause.value);
            }
        }
        return sb.toString();
    }
}
