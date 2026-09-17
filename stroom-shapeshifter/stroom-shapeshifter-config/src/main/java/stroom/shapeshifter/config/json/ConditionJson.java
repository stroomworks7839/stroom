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

package stroom.shapeshifter.config.json;

import stroom.shapeshifter.config.Cast;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.RefExpression;



/**
 * The condition family of the wire format: conditions, the six comparisons and their five
 * legacy spellings, operands — read and written together.
 */
final class ConditionJson {

    private ConditionJson() {
    }

    static Condition readCondition(final JsonValue node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "condition");
        final JsonValue body = tagged.body();
        return switch (tagged.name()) {
            // The six comparisons, and beneath them the five legacy spellings, kept for
            // ever as aliases. Each alias carries the cast its semantics always implied:
            // as-string on both sides for the equality trio — the engine's counters are
            // already typed Int, and legacy equality compares string forms — and as-number
            // on the left for the ordered pair (design/17 §8).
            case "eq" -> readCompare(body, Condition.Compare.Op.EQ);
            case "ne" -> readCompare(body, Condition.Compare.Op.NE);
            case "lt" -> readCompare(body, Condition.Compare.Op.LT);
            case "le" -> readCompare(body, Condition.Compare.Op.LE);
            case "gt" -> readCompare(body, Condition.Compare.Op.GT);
            case "ge" -> readCompare(body, Condition.Compare.Op.GE);
            case "matches" -> {
                JsonFields.checkFields(body, "matches", "select", "pattern");
                yield new Condition.Matches(
                        ReferenceJson.readRef(JsonFields.required(body, "select", "matches")),
                        JsonFields.text(body, "pattern", "matches"));
            }
            case "contains" -> {
                JsonFields.checkFields(body, "contains", "select", "substring");
                yield new Condition.Contains(
                        ReferenceJson.readRef(JsonFields.required(body, "select", "contains")),
                        JsonFields.text(body, "substring", "contains"));
            }
            case "starts-with" -> {
                JsonFields.checkFields(body, "starts-with", "select", "prefix");
                yield new Condition.StartsWith(
                        ReferenceJson.readRef(JsonFields.required(body, "select", "starts-with")),
                        JsonFields.text(body, "prefix", "starts-with"));
            }
            case "greater-than" -> {
                JsonFields.checkFields(body, "greater-than", "select", "value");
                yield numericOrdering(Condition.Compare.Op.GT,
                        ReferenceJson.readRef(JsonFields.required(body, "select", "greater-than")),
                        JsonFields.number(body, "value", "greater-than"));
            }
            case "less-than" -> {
                JsonFields.checkFields(body, "less-than", "select", "value");
                yield numericOrdering(Condition.Compare.Op.LT,
                        ReferenceJson.readRef(JsonFields.required(body, "select", "less-than")),
                        JsonFields.number(body, "value", "less-than"));
            }
            case "and" -> new Condition.And(JsonFields.list(body, "and", ConditionJson::readCondition));
            case "or" -> new Condition.Or(JsonFields.list(body, "or", ConditionJson::readCondition));
            case "not" -> new Condition.Not(readCondition(body));
            case "is-first" -> {
                JsonFields.checkFields(body, "is-first");
                yield new Condition.IsFirst();
            }
            case "is-last" -> {
                JsonFields.checkFields(body, "is-last");
                yield new Condition.IsLast();
            }
            case "exists" -> {
                JsonFields.checkFields(body, "exists", "select");
                yield new Condition.Exists(ReferenceJson.readRef(JsonFields.required(body, "select", "exists")));
            }
            default -> throw new ConfigException("Unknown condition: " + tagged.name());
        };
    }

    static JsonValue writeCondition(final Condition condition) {
        if (condition instanceof Condition.Compare value) {
            final JsonObject body = new JsonObject();
            body.put("left", writeOperand(value.left()));
            body.put("right", writeOperand(value.right()));
            return JsonFields.wrap(JsonFields.label(value.op()), body);
        } else if (condition instanceof Condition.Matches value) {
            return JsonFields.wrap("matches", selectAnd("pattern", value.select(),
                value.pattern()));
        } else if (condition instanceof Condition.Contains value) {
            return JsonFields.wrap("contains", selectAnd("substring", value.select(), value.substring()));
        } else if (condition instanceof Condition.StartsWith value) {
            return JsonFields.wrap("starts-with", selectAnd("prefix", value.select(), value.prefix()));
        } else if (condition instanceof Condition.And value) {
            return JsonFields.wrap("and", JsonFields.array(value.conditions(),
                ConditionJson::writeCondition));
        } else if (condition instanceof Condition.Or value) {
            return JsonFields.wrap("or", JsonFields.array(value.conditions(),
                ConditionJson::writeCondition));
        } else if (condition instanceof Condition.Not value) {
            return JsonFields.wrap("not", writeCondition(value.condition()));
        } else if (condition instanceof Condition.IsFirst) {
            return new JsonString("is-first");
        } else if (condition instanceof Condition.IsLast) {
            return new JsonString("is-last");
        } else if (condition instanceof Condition.Exists value) {
            final JsonObject body = new JsonObject();
            body.put("select", ReferenceJson.writeRef(value.select()));
            return JsonFields.wrap("exists", body);
        } else {
            throw new IllegalStateException("Unknown variant: " + condition);
        }

    }

    private static Condition readCompare(final JsonValue body, final Condition.Compare.Op op) {
        JsonFields.checkFields(body, "comparison", "left", "right");
        return new Condition.Compare(op,
                readOperand(JsonFields.required(body, "left", "comparison")),
                readOperand(JsonFields.required(body, "right", "comparison")));
    }


    /** A legacy ordering: the numeric parse it always performed, made visible. */
    private static Condition numericOrdering(final Condition.Compare.Op op,
                                             final RefExpression select,
                                             final double value) {
        return new Condition.Compare(op,
                new Condition.Operand(select, null, Cast.NUMBER),
                new Condition.Operand(null, new Condition.Literal.Fractional(value), null));
    }

    private static Condition.Operand readOperand(final JsonValue node) {
        JsonFields.checkFields(node, "operand", "ref", "value", "as");
        final Cast as = JsonFields.readCast(node);
        final boolean hasRef = JsonFields.optional(node, "ref") != null;
        final boolean hasValue = JsonFields.optional(node, "value") != null;
        if (hasRef == hasValue) {
            throw new ConfigException("An operand is a ref or a value, exactly one");
        }
        if (hasRef) {
            return new Condition.Operand(ReferenceJson.readRef(node.get("ref")), null, as);
        }
        // The literal's JSON type is its declared type (design/17 §8).
        final JsonValue value = node.get("value");
        final Condition.Literal literal;
        if (value.isString()) {
            literal = new Condition.Literal.Text(value.asString());
        } else if (value.isBoolean()) {
            literal = new Condition.Literal.Truth(value.asBoolean());
        } else if (value.isIntegralNumber()) {
            literal = new Condition.Literal.Whole(value.asLong());
        } else if (value.isNumber()) {
            literal = new Condition.Literal.Fractional(value.asDouble());
        } else {
            throw new ConfigException("An operand value must be a string, number or boolean");
        }
        return new Condition.Operand(null, literal, as);
    }

    private static JsonValue writeOperand(final Condition.Operand operand) {
        final JsonObject node = new JsonObject();
        if (operand.ref() != null) {
            node.put("ref", ReferenceJson.writeRef(operand.ref()));
        } else {
            final Condition.Literal literal = operand.literal();
            if (literal instanceof Condition.Literal.Text value) {
                node.put("value", value.value());
            } else if (literal instanceof Condition.Literal.Whole value) {
                node.put("value", value.value());
            } else if (literal instanceof Condition.Literal.Fractional value) {
                node.put("value", value.value());
            } else if (literal instanceof Condition.Literal.Truth value) {
                node.put("value", value.value());
            } else {
                throw new IllegalStateException("Unknown literal: " + literal);
            }
        }
        JsonFields.writeCast(node, operand.as());
        return node;
    }

    private static JsonObject selectAnd(final String field, final RefExpression select, final String value) {
        final JsonObject body = new JsonObject();
        body.put("select", ReferenceJson.writeRef(select));
        body.put(field, value);
        return body;
    }
}
