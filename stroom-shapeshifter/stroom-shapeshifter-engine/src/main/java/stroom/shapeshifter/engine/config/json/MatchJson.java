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

import stroom.shapeshifter.engine.config.Codec;
import stroom.shapeshifter.engine.config.CombinatorPattern;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Endianness;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.MatchStep;
import stroom.shapeshifter.engine.config.NumericType;
import stroom.shapeshifter.engine.config.Predicate;
import stroom.shapeshifter.engine.config.Predicate.CharSet;
import stroom.shapeshifter.engine.config.StepRef;
import stroom.shapeshifter.engine.config.Template.RegexFlags;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * The match family of the wire format: match expressions, regex flags, the combinator pattern
 * library, progressive steps, step references, predicates and character sets — read and
 * written together, because the round trip is the property that matters and it is kept most
 * easily where both halves can be seen at once.
 */
final class MatchJson {

    private MatchJson() {
    }

    static CombinatorPattern readPattern(final JsonNode node) {
        JsonFields.checkFields(node, "pattern", "id", "name", "steps");
        return new CombinatorPattern(
                JsonFields.uuid(node, "id", "pattern"),
                JsonFields.text(node, "name", "pattern"),
                JsonFields.list(node.get("steps"), "steps", MatchJson::readStep));
    }

    static ObjectNode writePattern(final CombinatorPattern pattern) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("id", pattern.id().toString());
        node.put("name", pattern.name());
        node.set("steps", JsonFields.array(pattern.steps(), MatchJson::writeStep));
        return node;
    }

    private static RegexFlags readFlags(final JsonNode node) {
        if (node == null || node.isNull()) {
            return RegexFlags.none();
        }
        JsonFields.checkFields(node, "flags", "case_insensitive", "dot_all");
        return new RegexFlags(
                node.path("case_insensitive").asBoolean(false), node.path("dot_all").asBoolean(false));
    }

    private static ObjectNode writeFlags(final RegexFlags flags) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("case_insensitive", flags.caseInsensitive());
        node.put("dot_all", flags.dotAll());
        return node;
    }

    static MatchExpression readMatch(final JsonNode node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "match expression");
        final JsonNode body = tagged.body();
        return switch (tagged.name()) {
            case "regex" -> {
                JsonFields.checkFields(body, "regex", "pattern", "flags", "advance");
                yield new MatchExpression.Regex(
                        JsonFields.text(body, "pattern", "regex"), readFlags(body.get("flags")),
                        body.path("advance").asInt(0));
            }
            case "delimiter" -> {
                JsonFields.checkFields(body, "delimiter", "delimiter", "escape", "container_start", "container_end");
                yield new MatchExpression.Delimiter(
                        JsonFields.text(body, "delimiter", "delimiter"),
                        JsonFields.optionalText(body, "escape"),
                        JsonFields.optionalText(body, "container_start"),
                        JsonFields.optionalText(body, "container_end"));
            }
            case "progressive" -> new MatchExpression.Progressive(
                    JsonFields.list(body, "progressive", MatchJson::readStep));
            case "source" -> {
                JsonFields.checkFields(body, "source");
                yield new MatchExpression.Source();
            }
            case "all" -> {
                JsonFields.checkFields(body, "all");
                yield new MatchExpression.All();
            }
            case "named" -> {
                JsonFields.checkFields(body, "named");
                yield new MatchExpression.Named();
            }
            case "avro" -> {
                JsonFields.checkFields(body, "avro", "schema");
                yield new MatchExpression.Avro(JsonFields.optionalText(body, "schema"));
            }
            case "parquet" -> {
                JsonFields.checkFields(body, "parquet", "columns");
                yield new MatchExpression.Parquet(JsonFields.list(body.get("columns"), "columns", JsonNode::asString));
            }
            case "protobuf" -> {
                JsonFields.checkFields(body, "protobuf", "descriptor_path", "message_type");
                yield new MatchExpression.Protobuf(
                        JsonFields.text(body, "descriptor_path", "protobuf"), JsonFields.text(body,
                                "message_type", "protobuf"));
            }
            default -> throw new ConfigException("Unknown match expression: " + tagged.name());
        };
    }

    static JsonNode writeMatch(final MatchExpression match) {
        return switch (match) {
            case MatchExpression.Regex regex -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("pattern", regex.pattern());
                body.set("flags", writeFlags(regex.flags()));
                body.put("advance", regex.advance());
                yield JsonFields.wrap("regex", body);
            }
            case MatchExpression.Delimiter delimiter -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("delimiter", delimiter.delimiter());
                JsonFields.putIfPresent(body, "escape", delimiter.escape());
                JsonFields.putIfPresent(body, "container_start", delimiter.containerStart());
                JsonFields.putIfPresent(body, "container_end", delimiter.containerEnd());
                yield JsonFields.wrap("delimiter", body);
            }
            case MatchExpression.Progressive progressive ->
                    JsonFields.wrap("progressive", JsonFields.array(progressive.steps(), MatchJson::writeStep));
            case MatchExpression.Source ignored -> JsonFields.NODES.stringNode("source");
            case MatchExpression.All ignored -> JsonFields.NODES.stringNode("all");
            case MatchExpression.Named ignored -> JsonFields.NODES.stringNode("named");
            case MatchExpression.Avro avro -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                JsonFields.putIfPresent(body, "schema", avro.schema());
                yield JsonFields.wrap("avro", body);
            }
            case MatchExpression.Parquet parquet -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                if (!parquet.columns().isEmpty()) {
                    final ArrayNode columns = body.putArray("columns");
                    parquet.columns().forEach(columns::add);
                }
                yield JsonFields.wrap("parquet", body);
            }
            case MatchExpression.Protobuf protobuf -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("descriptor_path", protobuf.descriptorPath());
                body.put("message_type", protobuf.messageType());
                yield JsonFields.wrap("protobuf", body);
            }
        };
    }

    private static MatchStep readStep(final JsonNode node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "match step");
        final JsonNode body = tagged.body();
        return switch (tagged.name()) {
            case "Tag" -> new MatchStep.Tag(body.asString());
            case "MatchByte" -> {
                final List<Integer> bytes = JsonFields.list(body, "MatchByte", JsonNode::asInt);
                final byte[] value = new byte[bytes.size()];
                for (int i = 0; i < value.length; i++) {
                    final int b = bytes.get(i);
                    if (b < 0 || b > 255) {
                        throw new ConfigException("A MatchByte value must be 0-255, but was " + b);
                    }
                    value[i] = (byte) b;
                }
                yield new MatchStep.MatchByte(value);
            }
            case "TakeWhile" -> new MatchStep.TakeWhile(readPredicate(body));
            case "TakeUntil" -> {
                JsonFields.checkFields(body, "TakeUntil", "pattern", "inclusive");
                yield new MatchStep.TakeUntil(
                        JsonFields.text(body, "pattern", "TakeUntil"), body.path("inclusive").asBoolean(false));
            }
            case "TakeBytes" -> new MatchStep.TakeBytes(readStepRef(body));
            case "TakeN" -> new MatchStep.TakeN(body.asInt());
            case "AnyChar" -> {
                JsonFields.checkFields(body, "AnyChar");
                yield new MatchStep.AnyChar();
            }
            case "ReadNumeric" -> {
                JsonFields.checkFields(body, "ReadNumeric", "numeric_type", "signed", "endian");
                final String endian = JsonFields.optionalText(body, "endian");
                yield new MatchStep.ReadNumeric(
                        JsonFields.constant(NumericType.class, JsonFields.text(body, "numeric_type", "ReadNumeric")),
                        body.path("signed").asBoolean(false),
                        endian == null ? Endianness.BIG : JsonFields.constant(Endianness.class, endian));
            }
            case "ReadVarint" -> {
                JsonFields.checkFields(body, "ReadVarint");
                yield new MatchStep.ReadVarint();
            }
            case "ReadVarintZigZag" -> {
                JsonFields.checkFields(body, "ReadVarintZigZag");
                yield new MatchStep.ReadVarintZigZag();
            }
            case "Seek" -> new MatchStep.Seek(readStepRef(body));
            case "SeekAbs" -> new MatchStep.SeekAbs(readStepRef(body));
            case "SeekBack" -> new MatchStep.SeekBack(readStepRef(body));
            case "Tell" -> {
                JsonFields.checkFields(body, "Tell");
                yield new MatchStep.Tell();
            }
            case "Decode" -> {
                JsonFields.checkFields(body, "Decode", "data", "codec");
                yield new MatchStep.Decode(
                        readStepRef(JsonFields.required(body, "data", "Decode")),
                        JsonFields.constant(Codec.class, JsonFields.text(body, "codec", "Decode")));
            }
            case "Encode" -> {
                JsonFields.checkFields(body, "Encode", "data", "codec");
                yield new MatchStep.Encode(
                        readStepRef(JsonFields.required(body, "data", "Encode")),
                        JsonFields.constant(Codec.class, JsonFields.text(body, "codec", "Encode")));
            }
            case "Regex" -> {
                JsonFields.checkFields(body, "Regex", "pattern", "flags");
                yield new MatchStep.Regex(JsonFields.text(body, "pattern", "Regex"), readFlags(body.get("flags")));
            }
            case "Choice" -> new MatchStep.Choice(JsonFields.list(body, "Choice",
                    alternative -> JsonFields.list(alternative, "Choice alternative", MatchJson::readStep)));
            case "Optional" -> new MatchStep.Optional(JsonFields.list(body, "Optional", MatchJson::readStep));
            case "Repeat" -> {
                JsonFields.checkFields(body, "Repeat", "steps", "min", "max");
                final JsonNode max = JsonFields.optional(body, "max");
                yield new MatchStep.Repeat(
                        JsonFields.list(body.get("steps"), "steps", MatchJson::readStep),
                        body.path("min").asInt(0),
                        max == null ? null : max.asInt());
            }
            case "Sequence" -> new MatchStep.Sequence(JsonFields.list(body, "Sequence", MatchJson::readStep));
            case "PatternRef" -> new MatchStep.PatternRef(JsonFields.uuid(body, "PatternRef"));
            case "Peek" -> new MatchStep.Peek(JsonFields.list(body, "Peek", MatchJson::readStep));
            case "Not" -> new MatchStep.Not(JsonFields.list(body, "Not", MatchJson::readStep));
            default -> throw new ConfigException("Unknown match step: " + tagged.name());
        };
    }

    private static JsonNode writeStep(final MatchStep step) {
        return switch (step) {
            case MatchStep.Tag value -> JsonFields.wrap("Tag", JsonFields.NODES.stringNode(value.value()));
            case MatchStep.MatchByte value -> {
                final ArrayNode bytes = JsonFields.NODES.arrayNode();
                for (final byte b : value.value()) {
                    bytes.add(b & 0xFF);
                }
                yield JsonFields.wrap("MatchByte", bytes);
            }
            case MatchStep.TakeWhile value -> JsonFields.wrap("TakeWhile", writePredicate(value.predicate()));
            case MatchStep.TakeUntil value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("pattern", value.pattern());
                body.put("inclusive", value.inclusive());
                yield JsonFields.wrap("TakeUntil", body);
            }
            case MatchStep.TakeBytes value -> JsonFields.wrap("TakeBytes", writeStepRef(value.count()));
            case MatchStep.TakeN value -> JsonFields.wrap("TakeN", JsonFields.NODES.numberNode(value.count()));
            case MatchStep.AnyChar ignored -> JsonFields.NODES.stringNode("AnyChar");
            case MatchStep.ReadNumeric value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("numeric_type", JsonFields.name(value.numericType()));
                body.put("signed", value.signed());
                body.put("endian", JsonFields.name(value.endian()));
                yield JsonFields.wrap("ReadNumeric", body);
            }
            case MatchStep.ReadVarint ignored -> JsonFields.NODES.stringNode("ReadVarint");
            case MatchStep.ReadVarintZigZag ignored -> JsonFields.NODES.stringNode("ReadVarintZigZag");
            case MatchStep.Seek value -> JsonFields.wrap("Seek", writeStepRef(value.count()));
            case MatchStep.SeekAbs value -> JsonFields.wrap("SeekAbs", writeStepRef(value.offset()));
            case MatchStep.SeekBack value -> JsonFields.wrap("SeekBack", writeStepRef(value.count()));
            case MatchStep.Tell ignored -> JsonFields.NODES.stringNode("Tell");
            case MatchStep.Decode value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("data", writeStepRef(value.data()));
                body.put("codec", JsonFields.name(value.codec()));
                yield JsonFields.wrap("Decode", body);
            }
            case MatchStep.Encode value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("data", writeStepRef(value.data()));
                body.put("codec", JsonFields.name(value.codec()));
                yield JsonFields.wrap("Encode", body);
            }
            case MatchStep.Regex value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("pattern", value.pattern());
                body.set("flags", writeFlags(value.flags()));
                yield JsonFields.wrap("Regex", body);
            }
            case MatchStep.Choice value -> {
                final ArrayNode alternatives = JsonFields.NODES.arrayNode();
                value.alternatives().forEach(a -> alternatives.add(JsonFields.array(a, MatchJson::writeStep)));
                yield JsonFields.wrap("Choice", alternatives);
            }
            case MatchStep.Optional value -> JsonFields.wrap("Optional", JsonFields.array(value.steps(),
                    MatchJson::writeStep));
            case MatchStep.Repeat value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("steps", JsonFields.array(value.steps(), MatchJson::writeStep));
                body.put("min", value.min());
                if (value.max() != null) {
                    body.put("max", value.max());
                }
                yield JsonFields.wrap("Repeat", body);
            }
            case MatchStep.Sequence value -> JsonFields.wrap("Sequence", JsonFields.array(value.steps(),
                    MatchJson::writeStep));
            case MatchStep.PatternRef value -> JsonFields.wrap("PatternRef",
                    JsonFields.NODES.stringNode(value.pattern().toString()));
            case MatchStep.Peek value -> JsonFields.wrap("Peek", JsonFields.array(value.steps(), MatchJson::writeStep));
            case MatchStep.Not value -> JsonFields.wrap("Not", JsonFields.array(value.steps(), MatchJson::writeStep));
        };
    }

    private static StepRef readStepRef(final JsonNode node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "step reference");
        return switch (tagged.name()) {
            case "Literal" -> new StepRef.Literal(tagged.body().asInt());
            case "StepOutput" -> new StepRef.StepOutput(tagged.body().asInt());
            default -> throw new ConfigException("Unknown step reference: " + tagged.name());
        };
    }

    private static JsonNode writeStepRef(final StepRef ref) {
        return switch (ref) {
            case StepRef.Literal literal -> JsonFields.wrap("Literal", JsonFields.NODES.numberNode(literal.value()));
            case StepRef.StepOutput output -> JsonFields.wrap("StepOutput",
                    JsonFields.NODES.numberNode(output.index()));
        };
    }

    private static Predicate readPredicate(final JsonNode node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "predicate");
        return switch (tagged.name()) {
            case "Alphabetic" -> {
                JsonFields.checkFields(tagged.body(), "Alphabetic");
                yield new Predicate.Alphabetic();
            }
            case "Alphanumeric" -> {
                JsonFields.checkFields(tagged.body(), "Alphanumeric");
                yield new Predicate.Alphanumeric();
            }
            case "Numeric" -> {
                JsonFields.checkFields(tagged.body(), "Numeric");
                yield new Predicate.Numeric();
            }
            case "Whitespace" -> {
                JsonFields.checkFields(tagged.body(), "Whitespace");
                yield new Predicate.Whitespace();
            }
            case "NonWhitespace" -> {
                JsonFields.checkFields(tagged.body(), "NonWhitespace");
                yield new Predicate.NonWhitespace();
            }
            case "Any" -> {
                JsonFields.checkFields(tagged.body(), "Any");
                yield new Predicate.Any();
            }
            case "Custom" -> new Predicate.Custom(readCharSet(tagged.body()));
            default -> throw new ConfigException("Unknown predicate: " + tagged.name());
        };
    }

    private static JsonNode writePredicate(final Predicate predicate) {
        return switch (predicate) {
            case Predicate.Alphabetic ignored -> JsonFields.NODES.stringNode("Alphabetic");
            case Predicate.Alphanumeric ignored -> JsonFields.NODES.stringNode("Alphanumeric");
            case Predicate.Numeric ignored -> JsonFields.NODES.stringNode("Numeric");
            case Predicate.Whitespace ignored -> JsonFields.NODES.stringNode("Whitespace");
            case Predicate.NonWhitespace ignored -> JsonFields.NODES.stringNode("NonWhitespace");
            case Predicate.Any ignored -> JsonFields.NODES.stringNode("Any");
            case Predicate.Custom custom -> JsonFields.wrap("Custom", writeCharSet(custom.charSet()));
        };
    }

    private static CharSet readCharSet(final JsonNode node) {
        JsonFields.checkFields(node, "charset", "expression", "chars", "ranges", "negated");
        return new CharSet(JsonFields.text(node, "expression", "charset"),
                JsonFields.list(node.get("chars"), "chars", MatchJson::character),
                JsonFields.list(node.get("ranges"), "ranges", MatchJson::range),
                node.path("negated").asBoolean(false));
    }

    private static CharSet.Range range(final JsonNode node) {
        if (!node.isArray() || node.size() != 2) {
            throw new ConfigException("A charset range must be a [from, to] pair");
        }
        return new CharSet.Range(character(node.get(0)), character(node.get(1)));
    }

    /**
     * One character of a charset — exactly one. A supplementary character is two UTF-16 units,
     * which the model's {@code char} cannot carry, and truncating it to its high surrogate would
     * match something the author never wrote.
     */
    private static char character(final JsonNode node) {
        final String value = node.asString();
        if (value.length() != 1) {
            throw new ConfigException("A charset entry must be a single character, but was '" + value + "'");
        }
        return value.charAt(0);
    }

    private static ObjectNode writeCharSet(final CharSet charSet) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("expression", charSet.expression());
        final ArrayNode chars = node.putArray("chars");
        charSet.chars().forEach(c -> chars.add(String.valueOf(c)));
        final ArrayNode ranges = node.putArray("ranges");
        charSet.ranges().forEach(r -> {
            final ArrayNode pair = ranges.addArray();
            pair.add(String.valueOf(r.from()));
            pair.add(String.valueOf(r.to()));
        });
        node.put("negated", charSet.negated());
        return node;
    }
}
