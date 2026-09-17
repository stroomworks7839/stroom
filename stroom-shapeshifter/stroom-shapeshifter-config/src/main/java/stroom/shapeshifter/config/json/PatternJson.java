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

import stroom.shapeshifter.config.BinaryCast;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.MatchExpression.Length;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;

import java.util.Map;
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

    static PatternNode readNode(final JsonValue node) {
        if (node == null || !node.isObject()) {
            throw new ConfigException("A pattern node must be an object with one node key, but was "
                                      + (node == null ? "missing" : node.shape()));
        }
        String kind = null;
        for (final var property : ((JsonObject) node).entries()) {
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
                                      + ((JsonObject) node).entries().stream().map(Map.Entry::getKey).toList());
        }
        JsonFields.checkFields(node, kind, kind, "label", "as", "min", "max", "greedy", "flags");
        final JsonValue body = node.get(kind);
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
                    JsonFields.flag(node, "greedy", true));
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

    static JsonObject writeNode(final PatternNode node) {
        final JsonObject out = new JsonObject();
        if (node instanceof PatternNode.Labelled labelled) {
            final JsonObject inner = writeNode(labelled.body());
            inner.put("label", labelled.label());
            if (labelled.as() != null) {
                inner.put("as", labelled.as().label());
            }
            return inner;
        } else if (node instanceof PatternNode.Tag tag) {
            out.put("tag", tag.text());
        } else if (node instanceof PatternNode.TakeWhile take) {
            out.put("take_while", take.classExpression());
            if (take.min() != 1) {
                out.put("min", take.min());
            }
            if (take.max() != PatternNode.Repeat.UNBOUNDED) {
                out.put("max", take.max());
            }
        } else if (node instanceof PatternNode.TakeUntil until) {
            out.put(until.inclusive() ? "take_through" : "take_until",
                until.terminator());
        } else if (node instanceof PatternNode.Take take) {
            if (take.count() == 1) {
                out.put("any", true);
            } else {
                out.put("take", take.count());
            }
        } else if (node instanceof PatternNode.Regex regex) {
            out.put("regex", regex.pattern());
            if (regex.flags().caseInsensitive() || regex.flags().dotAll()) {
                out.put("flags", MatchJson.writeFlags(regex.flags()));
            }
        } else if (node instanceof PatternNode.Ref ref) {
            out.put("ref", ref.name());
        } else if (node instanceof PatternNode.Sequence sequence) {
            out.put("sequence",
                JsonFields.array(sequence.items(), PatternJson::writeNode));
        } else if (node instanceof PatternNode.Choice choice) {
            out.put("choice",
                JsonFields.array(choice.alternatives(), PatternJson::writeNode));
        } else if (node instanceof PatternNode.Optional optional) {
            out.put("optional", writeNode(optional.body()));
        } else if (node instanceof PatternNode.Repeat repeat) {
            out.put("repeat", writeNode(repeat.body()));
            out.put("min", repeat.min());
            if (repeat.max() != PatternNode.Repeat.UNBOUNDED) {
                out.put("max", repeat.max());
            }
            if (!repeat.greedy()) {
                out.put("greedy", false);
            }
        } else if (node instanceof PatternNode.Peek peek) {
            out.put("peek", writeNode(peek.body()));
        } else if (node instanceof PatternNode.Not not) {
            out.put("not", writeNode(not.body()));
        } else {
            throw new IllegalStateException("Unknown variant: " + node);
        }

        return out;
    }

    static MatchPart readPart(final JsonValue node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "match part");
        return switch (tagged.name()) {
            case "pattern" -> new MatchPart.Pattern(readNode(tagged.body()));
            case "take" -> {
                final JsonValue body = tagged.body();
                if (body.isObject() && body.has("length")) {
                    JsonFields.checkFields(body, "take", "length", "label");
                    yield new MatchPart.Take(readLength(body.get("length"), "take"),
                            JsonFields.optionalText(body, "label"));
                }
                yield new MatchPart.Take(readLength(body, "take"), null);
            }
            case "seek" -> {
                final JsonValue body = tagged.body();
                if (body.isObject() && body.has("length")) {
                    JsonFields.checkFields(body, "seek", "length", "absolute");
                    yield new MatchPart.Seek(readLength(body.get("length"), "seek"),
                            JsonFields.flag(body, "absolute"));
                }
                yield new MatchPart.Seek(readLength(body, "seek"), false);
            }
            case "read" -> {
                final JsonValue body = tagged.body();
                if (body.isObject()) {
                    JsonFields.checkFields(body, "read", "as", "label");
                    yield new MatchPart.Read(readCast(JsonFields.text(body, "as", "read")),
                            JsonFields.optionalText(body, "label"));
                }
                yield new MatchPart.Read(readCast(JsonFields.text(body, "read")), null);
            }
            default -> throw new ConfigException("Unknown match part '" + tagged.name()
                                                 + "'; a part is pattern, take, seek or read");
        };
    }

    private static Length readLength(final JsonValue node, final String what) {
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

    private static BinaryCast readCast(final String label) {
        final BinaryCast cast = BinaryCast.of(label);
        if (cast == null) {
            throw new ConfigException("Unknown cast '" + label + "'");
        }
        return cast;
    }

    static JsonValue writePart(final MatchPart part) {
        if (part instanceof MatchPart.Pattern pattern) {
            return JsonFields.wrap("pattern", writeNode(pattern.node()));
        } else if (part instanceof MatchPart.Take take) {
            if (take.label() == null) {
                return JsonFields.wrap("take", writeLength(take.length()));
            }
            final JsonObject body = new JsonObject();
            body.put("length", writeLength(take.length()));
            body.put("label", take.label());
            return JsonFields.wrap("take", body);
        } else if (part instanceof MatchPart.Seek seek) {
            if (!seek.absolute()) {
                return JsonFields.wrap("seek", writeLength(seek.length()));
            }
            final JsonObject body = new JsonObject();
            body.put("length", writeLength(seek.length()));
            body.put("absolute", true);
            return JsonFields.wrap("seek", body);
        } else if (part instanceof MatchPart.Read read) {
            if (read.label() == null) {
                return JsonFields.wrap("read", new JsonString(read.as().label()));
            }
            final JsonObject body = new JsonObject();
            body.put("as", read.as().label());
            body.put("label", read.label());
            return JsonFields.wrap("read", body);
        } else {
            throw new IllegalStateException("Unknown variant: " + part);
        }

    }

    private static JsonValue writeLength(final Length length) {
        if (length instanceof Length.Literal literal) {
            return JsonNumber.of(literal.count());
        } else if (length instanceof Length.Label label) {
            return new JsonObject().put("label", label.label());
        } else if (length instanceof Length.Var var) {
            return new JsonObject().put("var", var.name());
        } else {
            throw new IllegalStateException("Unknown variant: " + length);
        }

    }
}
