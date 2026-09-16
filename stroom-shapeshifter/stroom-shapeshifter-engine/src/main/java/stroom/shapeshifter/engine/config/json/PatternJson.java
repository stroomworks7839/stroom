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

package stroom.shapeshifter.engine.config.json;

import stroom.shapeshifter.engine.config.BinaryCast;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.MatchExpression.Length;
import stroom.shapeshifter.engine.config.MatchExpression.MatchPart;
import stroom.shapeshifter.engine.config.PatternNode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * The pattern tree and the match sequence as JSON (design 38 §4). A node is an object with
 * exactly one node key — {@code tag}, {@code take_while}, {@code take_until}, {@code
 * take_through}, {@code take}, {@code any}, {@code regex}, {@code ref}, {@code sequence},
 * {@code choice}, {@code optional}, {@code repeat}, {@code peek}, {@code not} — beside the
 * optional {@code label} and {@code as}, and {@code repeat} also takes {@code min}, {@code max}
 * and {@code greedy}. A part is {@code {"pattern": node}}, {@code {"take": length}} or
 * {@code {"seek": length}}, where a take may be {@code {"length": …, "label": name}} and a
 * seek {@code {"length": …, "absolute": true}} for a seek to an offset; a length is a number,
 * {@code {"label": name}} or {@code {"var": name}}.
 */
final class PatternJson {

    private static final Set<String> NODE_KEYS = Set.of("tag", "take_while", "take_until", "take_through", "take",
            "any", "regex", "ref", "sequence", "choice", "optional", "repeat", "peek", "not");

    private PatternJson() {
    }

    static PatternNode readNode(final JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new ConfigException("A pattern node must be an object with one node key, but was "
                                      + (node == null ? "missing" : node.getNodeType().toString().toLowerCase()));
        }
        String kind = null;
        for (final var property : node.properties()) {
            if (NODE_KEYS.contains(property.getKey())) {
                if (kind != null) {
                    throw new ConfigException("A pattern node has one node key, but this one has '"
                                              + kind + "' and '" + property.getKey() + "'");
                }
                kind = property.getKey();
            }
        }
        if (kind == null) {
            throw new ConfigException("A pattern node needs one of " + NODE_KEYS + ", but this one has "
                                      + node.propertyNames());
        }
        JsonFields.checkFields(node, kind, kind, "label", "as", "min", "max", "greedy", "flags");
        final JsonNode body = node.get(kind);
        final PatternNode bare = switch (kind) {
            case "tag" -> new PatternNode.Tag(JsonFields.text(node, "tag", "tag"));
            case "take_while" -> new PatternNode.TakeWhile(JsonFields.text(node, "take_while", "take_while"),
                    JsonFields.integer(node, "min", "take_while", 1),
                    node.has("max") ? JsonFields.integer(node, "max", "take_while") : PatternNode.Repeat.UNBOUNDED);
            case "take_until" -> new PatternNode.TakeUntil(
                    JsonFields.text(node, "take_until", "take_until"), false);
            case "take_through" -> new PatternNode.TakeUntil(
                    JsonFields.text(node, "take_through", "take_through"), true);
            case "take" -> new PatternNode.Take(JsonFields.integer(node, "take", "take"));
            case "any" -> new PatternNode.Take(1);
            case "regex" -> new PatternNode.Regex(JsonFields.text(node, "regex", "regex"),
                    node.has("flags") ? MatchJson.readFlags(node.get("flags")) : null);
            case "ref" -> new PatternNode.Ref(JsonFields.text(node, "ref", "ref"));
            case "sequence" -> new PatternNode.Sequence(JsonFields.list(body, "sequence", PatternJson::readNode));
            case "choice" -> new PatternNode.Choice(JsonFields.list(body, "choice", PatternJson::readNode));
            case "optional" -> new PatternNode.Optional(readNode(body));
            case "repeat" -> new PatternNode.Repeat(readNode(body),
                    JsonFields.integer(node, "min", "repeat", 0),
                    node.has("max") ? JsonFields.integer(node, "max", "repeat") : PatternNode.Repeat.UNBOUNDED,
                    !node.has("greedy") || node.get("greedy").asBoolean(true));
            case "peek" -> new PatternNode.Peek(readNode(body));
            case "not" -> new PatternNode.Not(readNode(body));
            default -> throw new IllegalStateException(kind);
        };
        final String label = JsonFields.optionalText(node, "label");
        final String as = JsonFields.optionalText(node, "as");
        if (as != null && label == null) {
            throw new ConfigException("A pattern node with 'as' needs a label: the cast applies to the capture");
        }
        if (label == null) {
            return bare;
        }
        final BinaryCast cast = as == null ? null : BinaryCast.of(as);
        if (as != null && cast == null) {
            throw new ConfigException("Unknown binary cast '" + as + "' on label '" + label + "'");
        }
        return new PatternNode.Labelled(bare, label, cast);
    }

    static ObjectNode writeNode(final PatternNode node) {
        final ObjectNode out = JsonFields.NODES.objectNode();
        switch (node) {
            case final PatternNode.Labelled labelled -> {
                final ObjectNode inner = writeNode(labelled.body());
                inner.put("label", labelled.label());
                if (labelled.as() != null) {
                    inner.put("as", labelled.as().label());
                }
                return inner;
            }
            case final PatternNode.Tag tag -> out.put("tag", tag.text());
            case final PatternNode.TakeWhile take -> {
                out.put("take_while", take.classExpression());
                if (take.min() != 1) {
                    out.put("min", take.min());
                }
                if (take.max() != PatternNode.Repeat.UNBOUNDED) {
                    out.put("max", take.max());
                }
            }
            case final PatternNode.TakeUntil until -> out.put(until.inclusive() ? "take_through" : "take_until",
                    until.terminator());
            case final PatternNode.Take take -> {
                if (take.count() == 1) {
                    out.put("any", true);
                } else {
                    out.put("take", take.count());
                }
            }
            case final PatternNode.Regex regex -> {
                out.put("regex", regex.pattern());
                if (regex.flags().caseInsensitive() || regex.flags().dotAll()) {
                    out.set("flags", MatchJson.writeFlags(regex.flags()));
                }
            }
            case final PatternNode.Ref ref -> out.put("ref", ref.name());
            case final PatternNode.Sequence sequence -> out.set("sequence",
                    JsonFields.array(sequence.items(), PatternJson::writeNode));
            case final PatternNode.Choice choice -> out.set("choice",
                    JsonFields.array(choice.alternatives(), PatternJson::writeNode));
            case final PatternNode.Optional optional -> out.set("optional", writeNode(optional.body()));
            case final PatternNode.Repeat repeat -> {
                out.set("repeat", writeNode(repeat.body()));
                out.put("min", repeat.min());
                if (repeat.max() != PatternNode.Repeat.UNBOUNDED) {
                    out.put("max", repeat.max());
                }
                if (!repeat.greedy()) {
                    out.put("greedy", false);
                }
            }
            case final PatternNode.Peek peek -> out.set("peek", writeNode(peek.body()));
            case final PatternNode.Not not -> out.set("not", writeNode(not.body()));
        }
        return out;
    }

    static MatchPart readPart(final JsonNode node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "match part");
        return switch (tagged.name()) {
            case "pattern" -> new MatchPart.Pattern(readNode(tagged.body()));
            case "take" -> {
                final JsonNode body = tagged.body();
                if (body.isObject() && body.has("length")) {
                    JsonFields.checkFields(body, "take", "length", "label");
                    yield new MatchPart.Take(readLength(body.get("length"), "take"),
                            JsonFields.optionalText(body, "label"));
                }
                yield new MatchPart.Take(readLength(body, "take"), null);
            }
            case "seek" -> {
                final JsonNode body = tagged.body();
                if (body.isObject() && body.has("length")) {
                    JsonFields.checkFields(body, "seek", "length", "absolute");
                    yield new MatchPart.Seek(readLength(body.get("length"), "seek"),
                            body.has("absolute") && body.get("absolute").asBoolean());
                }
                yield new MatchPart.Seek(readLength(body, "seek"), false);
            }
            default -> throw new ConfigException("Unknown match part '" + tagged.name()
                                                 + "'; a part is pattern, take or seek");
        };
    }

    private static Length readLength(final JsonNode node, final String what) {
        if (node.isNumber()) {
            return new Length.Literal(node.asInt());
        }
        if (node.isObject() && node.size() == 1) {
            if (node.has("label")) {
                return new Length.Label(JsonFields.text(node, "label", what));
            }
            if (node.has("var")) {
                return new Length.Var(JsonFields.text(node, "var", what));
            }
        }
        throw new ConfigException("A " + what + " length is a number, {\"label\": name} or {\"var\": name}");
    }

    static JsonNode writePart(final MatchPart part) {
        return switch (part) {
            case final MatchPart.Pattern pattern -> JsonFields.wrap("pattern", writeNode(pattern.node()));
            case final MatchPart.Take take -> {
                if (take.label() == null) {
                    yield JsonFields.wrap("take", writeLength(take.length()));
                }
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("length", writeLength(take.length()));
                body.put("label", take.label());
                yield JsonFields.wrap("take", body);
            }
            case final MatchPart.Seek seek -> {
                if (!seek.absolute()) {
                    yield JsonFields.wrap("seek", writeLength(seek.length()));
                }
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("length", writeLength(seek.length()));
                body.put("absolute", true);
                yield JsonFields.wrap("seek", body);
            }
        };
    }

    private static JsonNode writeLength(final Length length) {
        return switch (length) {
            case final Length.Literal literal -> JsonFields.NODES.numberNode(literal.count());
            case final Length.Label label -> JsonFields.NODES.objectNode().put("label", label.label());
            case final Length.Var var -> JsonFields.NODES.objectNode().put("var", var.name());
        };
    }
}
