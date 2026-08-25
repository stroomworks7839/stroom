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

import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.config.CaptureBinding;
import stroom.shapeshifter.engine.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.Codec;
import stroom.shapeshifter.engine.config.CombinatorPattern;
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.Endianness;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.MatchStep;
import stroom.shapeshifter.engine.config.NumericType;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.OutputNode.Entry;
import stroom.shapeshifter.engine.config.OutputNode.Param;
import stroom.shapeshifter.engine.config.OutputNode.SwitchCase;
import stroom.shapeshifter.engine.config.OutputNode.WhenBranch;
import stroom.shapeshifter.engine.config.Predicate;
import stroom.shapeshifter.engine.config.Predicate.CharSet;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.Project.SourceConfig;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;
import stroom.shapeshifter.engine.config.StepRef;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.config.Template.MatchLimits;
import stroom.shapeshifter.engine.config.Template.ParamDecl;
import stroom.shapeshifter.engine.config.Template.RegexFlags;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The wire format, in one file.
 *
 * <p>Configurations are serde documents, and serde's conventions are load-bearing rather than
 * incidental: a sum type is a single-key object whose key is the variant, {@code {"regex": {…}}},
 * except when the variant carries nothing, in which case it collapses to a bare string,
 * {@code "source"}. Variant names are kebab-cased in the types that were designed for people to
 * write, and left in Rust's own casing in the ones that were not. Fields inside a variant stay
 * snake_case throughout. Absent means default, and default is not always the type's zero.
 *
 * <p>All of that is written out here, explicitly, rather than expressed as annotations spread
 * across twenty model classes. Two reasons. The model stays a plain tree of records that a
 * different serialiser — or none — could carry, which is what keeps D33's promise that a
 * JDK-only reader remains reachable. And the format's rules end up in one place a person can
 * read top to bottom when a document does not load, instead of being inferred from the
 * interaction of defaults.
 *
 * <p>Reading is strict. An unrecognised variant or an unrecognised field is a
 * {@link ConfigException}, not a silent default — a configuration that half-loads is worse than
 * one that does not load, because it runs.
 */
public final class ProjectJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ProjectJson() {
    }

    // -----------------------------------------------------------------------------------
    // Project
    // -----------------------------------------------------------------------------------

    /** Read a whole configuration. */
    public static Project readProject(final JsonNode node) {
        expectObject(node, "project");
        checkFields(node, "project", "name", "version", "source", "templates", "patterns");
        return new Project(
                text(node, "name", "project"),
                required(node, "version", "project").asInt(),
                node.has("source") ? readSource(node.get("source")) : SourceConfig.defaults(),
                list(node.get("templates"), "templates", ProjectJson::readTemplate),
                list(node.get("patterns"), "patterns", ProjectJson::readPattern));
    }

    /** Write a whole configuration. */
    public static ObjectNode writeProject(final Project project) {
        final ObjectNode node = NODES.objectNode();
        node.put("name", project.name());
        node.put("version", project.version());
        node.set("source", writeSource(project.source()));
        node.set("templates", array(project.templates(), ProjectJson::writeTemplate));
        if (!project.patterns().isEmpty()) {
            node.set("patterns", array(project.patterns(), ProjectJson::writePattern));
        }
        return node;
    }

    private static SourceConfig readSource(final JsonNode node) {
        checkFields(node, "source", "buffer_size", "ignore_errors", "encoding", "dispatch",
                "strict_values");
        return new SourceConfig(
                node.path("buffer_size").asInt(SourceConfig.DEFAULT_BUFFER_SIZE),
                node.path("ignore_errors").asBoolean(false),
                node.has("encoding") ? node.get("encoding").asString() : SourceConfig.AUTO,
                readDispatch(node),
                node.path("strict_values").asBoolean(false));
    }

    /** The dispatch mode, spelt lowercase, or null to inherit (D36). */
    private static Dispatch readDispatch(final JsonNode node) {
        if (!node.has("dispatch")) {
            return null;
        }
        final String text = node.get("dispatch").asString();
        try {
            return Dispatch.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new ConfigException("Unknown dispatch mode: " + text);
        }
    }

    private static void writeDispatch(final ObjectNode node, final Dispatch dispatch) {
        if (dispatch != null) {
            node.put("dispatch", dispatch.name().toLowerCase(Locale.ROOT));
        }
    }

    private static ObjectNode writeSource(final SourceConfig source) {
        final ObjectNode node = NODES.objectNode();
        node.put("buffer_size", source.bufferSize());
        node.put("ignore_errors", source.ignoreErrors());
        writeDispatch(node, source.dispatch());
        node.put("encoding", source.encoding());
        if (source.strictValues()) {
            node.put("strict_values", true);
        }
        return node;
    }

    private static CombinatorPattern readPattern(final JsonNode node) {
        checkFields(node, "pattern", "id", "name", "steps");
        return new CombinatorPattern(
                uuid(node, "id", "pattern"),
                text(node, "name", "pattern"),
                list(node.get("steps"), "steps", ProjectJson::readStep));
    }

    private static ObjectNode writePattern(final CombinatorPattern pattern) {
        final ObjectNode node = NODES.objectNode();
        node.put("id", pattern.id().toString());
        node.put("name", pattern.name());
        node.set("steps", array(pattern.steps(), ProjectJson::writeStep));
        return node;
    }

    // -----------------------------------------------------------------------------------
    // Template
    // -----------------------------------------------------------------------------------

    private static Template readTemplate(final JsonNode node) {
        checkFields(node, "template", "id", "name", "mode", "guard", "param", "match",
                "match_limits", "captures", "body", "encoding", "ignore_errors", "consume");
        return new Template(
                uuid(node, "id", "template"),
                text(node, "name", "template"),
                optionalText(node, "mode"),
                node.path("consume").asBoolean(false),
                node.has("guard") ? readCondition(node.get("guard")) : null,
                list(node.get("param"), "param", ProjectJson::readParamDecl),
                readMatch(required(node, "match", "template")),
                node.has("match_limits") ? readMatchLimits(node.get("match_limits")) : MatchLimits.unlimited(),
                list(node.get("captures"), "captures", ProjectJson::readCapture),
                list(node.get("body"), "body", ProjectJson::readOutput),
                optionalText(node, "encoding"),
                node.path("ignore_errors").asBoolean(false));
    }

    private static ObjectNode writeTemplate(final Template template) {
        final ObjectNode node = NODES.objectNode();
        node.put("id", template.id().toString());
        node.put("name", template.name());
        putIfPresent(node, "mode", template.mode());
        if (template.consume()) {
            node.put("consume", true);
        }
        if (template.guard() != null) {
            node.set("guard", writeCondition(template.guard()));
        }
        if (!template.param().isEmpty()) {
            node.set("param", array(template.param(), ProjectJson::writeParamDecl));
        }
        node.set("match", writeMatch(template.match()));
        node.set("match_limits", writeMatchLimits(template.matchLimits()));
        if (!template.captures().isEmpty()) {
            node.set("captures", array(template.captures(), ProjectJson::writeCapture));
        }
        if (!template.body().isEmpty()) {
            node.set("body", array(template.body(), ProjectJson::writeOutput));
        }
        putIfPresent(node, "encoding", template.encoding());
        if (template.ignoreErrors()) {
            node.put("ignore_errors", true);
        }
        return node;
    }

    private static ParamDecl readParamDecl(final JsonNode node) {
        checkFields(node, "param", "name", "default");
        return new ParamDecl(text(node, "name", "param"), optionalText(node, "default"));
    }

    private static ObjectNode writeParamDecl(final ParamDecl param) {
        final ObjectNode node = NODES.objectNode();
        node.put("name", param.name());
        putIfPresent(node, "default", param.defaultValue());
        return node;
    }

    private static MatchLimits readMatchLimits(final JsonNode node) {
        checkFields(node, "match_limits", "min_match", "max_match", "only_match");
        Set<Integer> only = null;
        if (node.has("only_match") && !node.get("only_match").isNull()) {
            only = new LinkedHashSet<>();
            for (final JsonNode index : node.get("only_match")) {
                only.add(index.asInt());
            }
        }
        return new MatchLimits(
                node.path("min_match").asInt(0),
                node.path("max_match").asInt(MatchLimits.UNLIMITED),
                only);
    }

    private static ObjectNode writeMatchLimits(final MatchLimits limits) {
        final ObjectNode node = NODES.objectNode();
        node.put("min_match", limits.minMatch());
        node.put("max_match", limits.maxMatch());
        if (limits.onlyMatch() != null) {
            final ArrayNode only = node.putArray("only_match");
            limits.onlyMatch().forEach(only::add);
        }
        return node;
    }

    private static RegexFlags readFlags(final JsonNode node) {
        if (node == null || node.isNull()) {
            return RegexFlags.none();
        }
        checkFields(node, "flags", "case_insensitive", "dot_all");
        return new RegexFlags(
                node.path("case_insensitive").asBoolean(false), node.path("dot_all").asBoolean(false));
    }

    private static ObjectNode writeFlags(final RegexFlags flags) {
        final ObjectNode node = NODES.objectNode();
        node.put("case_insensitive", flags.caseInsensitive());
        node.put("dot_all", flags.dotAll());
        return node;
    }

    // -----------------------------------------------------------------------------------
    // Match expressions
    // -----------------------------------------------------------------------------------

    private static MatchExpression readMatch(final JsonNode node) {
        final Tagged tagged = tag(node, "match expression");
        final JsonNode body = tagged.body();
        return switch (tagged.name()) {
            case "regex" -> {
                checkFields(body, "regex", "pattern", "flags", "advance");
                yield new MatchExpression.Regex(
                        text(body, "pattern", "regex"), readFlags(body.get("flags")),
                        body.path("advance").asInt(0));
            }
            case "delimiter" -> {
                checkFields(body, "delimiter", "delimiter", "escape", "container_start", "container_end");
                yield new MatchExpression.Delimiter(
                        text(body, "delimiter", "delimiter"),
                        optionalText(body, "escape"),
                        optionalText(body, "container_start"),
                        optionalText(body, "container_end"));
            }
            case "progressive" -> new MatchExpression.Progressive(
                    list(body, "progressive", ProjectJson::readStep));
            case "source" -> {
                checkFields(body, "source");
                yield new MatchExpression.Source();
            }
            case "all" -> {
                checkFields(body, "all");
                yield new MatchExpression.All();
            }
            case "named" -> {
                checkFields(body, "named");
                yield new MatchExpression.Named();
            }
            case "avro" -> {
                checkFields(body, "avro", "schema");
                yield new MatchExpression.Avro(optionalText(body, "schema"));
            }
            case "parquet" -> {
                checkFields(body, "parquet", "columns");
                yield new MatchExpression.Parquet(list(body.get("columns"), "columns", JsonNode::asString));
            }
            case "protobuf" -> {
                checkFields(body, "protobuf", "descriptor_path", "message_type");
                yield new MatchExpression.Protobuf(
                        text(body, "descriptor_path", "protobuf"), text(body, "message_type", "protobuf"));
            }
            default -> throw new ConfigException("Unknown match expression: " + tagged.name());
        };
    }

    private static JsonNode writeMatch(final MatchExpression match) {
        return switch (match) {
            case MatchExpression.Regex regex -> {
                final ObjectNode body = NODES.objectNode();
                body.put("pattern", regex.pattern());
                body.set("flags", writeFlags(regex.flags()));
                body.put("advance", regex.advance());
                yield wrap("regex", body);
            }
            case MatchExpression.Delimiter delimiter -> {
                final ObjectNode body = NODES.objectNode();
                body.put("delimiter", delimiter.delimiter());
                putIfPresent(body, "escape", delimiter.escape());
                putIfPresent(body, "container_start", delimiter.containerStart());
                putIfPresent(body, "container_end", delimiter.containerEnd());
                yield wrap("delimiter", body);
            }
            case MatchExpression.Progressive progressive ->
                    wrap("progressive", array(progressive.steps(), ProjectJson::writeStep));
            case MatchExpression.Source ignored -> NODES.stringNode("source");
            case MatchExpression.All ignored -> NODES.stringNode("all");
            case MatchExpression.Named ignored -> NODES.stringNode("named");
            case MatchExpression.Avro avro -> {
                final ObjectNode body = NODES.objectNode();
                putIfPresent(body, "schema", avro.schema());
                yield wrap("avro", body);
            }
            case MatchExpression.Parquet parquet -> {
                final ObjectNode body = NODES.objectNode();
                if (!parquet.columns().isEmpty()) {
                    final ArrayNode columns = body.putArray("columns");
                    parquet.columns().forEach(columns::add);
                }
                yield wrap("parquet", body);
            }
            case MatchExpression.Protobuf protobuf -> {
                final ObjectNode body = NODES.objectNode();
                body.put("descriptor_path", protobuf.descriptorPath());
                body.put("message_type", protobuf.messageType());
                yield wrap("protobuf", body);
            }
        };
    }

    // -----------------------------------------------------------------------------------
    // Match steps
    // -----------------------------------------------------------------------------------

    private static MatchStep readStep(final JsonNode node) {
        final Tagged tagged = tag(node, "match step");
        final JsonNode body = tagged.body();
        return switch (tagged.name()) {
            case "Tag" -> new MatchStep.Tag(body.asString());
            case "MatchByte" -> {
                final byte[] value = new byte[body.size()];
                for (int i = 0; i < value.length; i++) {
                    final int b = body.get(i).asInt();
                    if (b < 0 || b > 255) {
                        throw new ConfigException("A MatchByte value must be 0-255, but was " + b);
                    }
                    value[i] = (byte) b;
                }
                yield new MatchStep.MatchByte(value);
            }
            case "TakeWhile" -> new MatchStep.TakeWhile(readPredicate(body));
            case "TakeUntil" -> {
                checkFields(body, "TakeUntil", "pattern", "inclusive");
                yield new MatchStep.TakeUntil(
                        text(body, "pattern", "TakeUntil"), body.path("inclusive").asBoolean(false));
            }
            case "TakeBytes" -> new MatchStep.TakeBytes(readStepRef(body));
            case "TakeN" -> new MatchStep.TakeN(body.asInt());
            case "AnyChar" -> {
                checkFields(body, "AnyChar");
                yield new MatchStep.AnyChar();
            }
            case "ReadNumeric" -> {
                checkFields(body, "ReadNumeric", "numeric_type", "signed", "endian");
                yield new MatchStep.ReadNumeric(
                        constant(NumericType.class, text(body, "numeric_type", "ReadNumeric")),
                        body.path("signed").asBoolean(false),
                        body.has("endian")
                                ? constant(Endianness.class, body.get("endian").asString())
                                : Endianness.BIG);
            }
            case "ReadVarint" -> {
                checkFields(body, "ReadVarint");
                yield new MatchStep.ReadVarint();
            }
            case "ReadVarintZigZag" -> {
                checkFields(body, "ReadVarintZigZag");
                yield new MatchStep.ReadVarintZigZag();
            }
            case "Seek" -> new MatchStep.Seek(readStepRef(body));
            case "SeekAbs" -> new MatchStep.SeekAbs(readStepRef(body));
            case "SeekBack" -> new MatchStep.SeekBack(readStepRef(body));
            case "Tell" -> {
                checkFields(body, "Tell");
                yield new MatchStep.Tell();
            }
            case "Decode" -> {
                checkFields(body, "Decode", "data", "codec");
                yield new MatchStep.Decode(
                        readStepRef(required(body, "data", "Decode")),
                        constant(Codec.class, text(body, "codec", "Decode")));
            }
            case "Encode" -> {
                checkFields(body, "Encode", "data", "codec");
                yield new MatchStep.Encode(
                        readStepRef(required(body, "data", "Encode")),
                        constant(Codec.class, text(body, "codec", "Encode")));
            }
            case "Regex" -> {
                checkFields(body, "Regex", "pattern", "flags");
                yield new MatchStep.Regex(text(body, "pattern", "Regex"), readFlags(body.get("flags")));
            }
            case "Choice" -> {
                final List<List<MatchStep>> alternatives = new ArrayList<>();
                for (final JsonNode alternative : body) {
                    alternatives.add(list(alternative, "Choice alternative", ProjectJson::readStep));
                }
                yield new MatchStep.Choice(alternatives);
            }
            case "Optional" -> new MatchStep.Optional(list(body, "Optional", ProjectJson::readStep));
            case "Repeat" -> {
                checkFields(body, "Repeat", "steps", "min", "max");
                yield new MatchStep.Repeat(
                        list(body.get("steps"), "steps", ProjectJson::readStep),
                        body.path("min").asInt(0),
                        body.has("max") && !body.get("max").isNull() ? body.get("max").asInt() : null);
            }
            case "Sequence" -> new MatchStep.Sequence(list(body, "Sequence", ProjectJson::readStep));
            case "PatternRef" -> new MatchStep.PatternRef(UUID.fromString(body.asString()));
            case "Peek" -> new MatchStep.Peek(list(body, "Peek", ProjectJson::readStep));
            case "Not" -> new MatchStep.Not(list(body, "Not", ProjectJson::readStep));
            default -> throw new ConfigException("Unknown match step: " + tagged.name());
        };
    }

    private static JsonNode writeStep(final MatchStep step) {
        return switch (step) {
            case MatchStep.Tag value -> wrap("Tag", NODES.stringNode(value.value()));
            case MatchStep.MatchByte value -> {
                final ArrayNode bytes = NODES.arrayNode();
                for (final byte b : value.value()) {
                    bytes.add(b & 0xFF);
                }
                yield wrap("MatchByte", bytes);
            }
            case MatchStep.TakeWhile value -> wrap("TakeWhile", writePredicate(value.predicate()));
            case MatchStep.TakeUntil value -> {
                final ObjectNode body = NODES.objectNode();
                body.put("pattern", value.pattern());
                body.put("inclusive", value.inclusive());
                yield wrap("TakeUntil", body);
            }
            case MatchStep.TakeBytes value -> wrap("TakeBytes", writeStepRef(value.count()));
            case MatchStep.TakeN value -> wrap("TakeN", NODES.numberNode(value.count()));
            case MatchStep.AnyChar ignored -> NODES.stringNode("AnyChar");
            case MatchStep.ReadNumeric value -> {
                final ObjectNode body = NODES.objectNode();
                body.put("numeric_type", name(value.numericType()));
                body.put("signed", value.signed());
                body.put("endian", name(value.endian()));
                yield wrap("ReadNumeric", body);
            }
            case MatchStep.ReadVarint ignored -> NODES.stringNode("ReadVarint");
            case MatchStep.ReadVarintZigZag ignored -> NODES.stringNode("ReadVarintZigZag");
            case MatchStep.Seek value -> wrap("Seek", writeStepRef(value.count()));
            case MatchStep.SeekAbs value -> wrap("SeekAbs", writeStepRef(value.offset()));
            case MatchStep.SeekBack value -> wrap("SeekBack", writeStepRef(value.count()));
            case MatchStep.Tell ignored -> NODES.stringNode("Tell");
            case MatchStep.Decode value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("data", writeStepRef(value.data()));
                body.put("codec", name(value.codec()));
                yield wrap("Decode", body);
            }
            case MatchStep.Encode value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("data", writeStepRef(value.data()));
                body.put("codec", name(value.codec()));
                yield wrap("Encode", body);
            }
            case MatchStep.Regex value -> {
                final ObjectNode body = NODES.objectNode();
                body.put("pattern", value.pattern());
                body.set("flags", writeFlags(value.flags()));
                yield wrap("Regex", body);
            }
            case MatchStep.Choice value -> {
                final ArrayNode alternatives = NODES.arrayNode();
                value.alternatives().forEach(a -> alternatives.add(array(a, ProjectJson::writeStep)));
                yield wrap("Choice", alternatives);
            }
            case MatchStep.Optional value -> wrap("Optional", array(value.steps(), ProjectJson::writeStep));
            case MatchStep.Repeat value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("steps", array(value.steps(), ProjectJson::writeStep));
                body.put("min", value.min());
                if (value.max() != null) {
                    body.put("max", value.max());
                }
                yield wrap("Repeat", body);
            }
            case MatchStep.Sequence value -> wrap("Sequence", array(value.steps(), ProjectJson::writeStep));
            case MatchStep.PatternRef value -> wrap("PatternRef", NODES.stringNode(value.pattern().toString()));
            case MatchStep.Peek value -> wrap("Peek", array(value.steps(), ProjectJson::writeStep));
            case MatchStep.Not value -> wrap("Not", array(value.steps(), ProjectJson::writeStep));
        };
    }

    private static StepRef readStepRef(final JsonNode node) {
        final Tagged tagged = tag(node, "step reference");
        return switch (tagged.name()) {
            case "Literal" -> new StepRef.Literal(tagged.body().asInt());
            case "StepOutput" -> new StepRef.StepOutput(tagged.body().asInt());
            default -> throw new ConfigException("Unknown step reference: " + tagged.name());
        };
    }

    private static JsonNode writeStepRef(final StepRef ref) {
        return switch (ref) {
            case StepRef.Literal literal -> wrap("Literal", NODES.numberNode(literal.value()));
            case StepRef.StepOutput output -> wrap("StepOutput", NODES.numberNode(output.index()));
        };
    }

    // -----------------------------------------------------------------------------------
    // Predicates
    // -----------------------------------------------------------------------------------

    private static Predicate readPredicate(final JsonNode node) {
        final Tagged tagged = tag(node, "predicate");
        return switch (tagged.name()) {
            case "Alphabetic" -> {
                checkFields(tagged.body(), "Alphabetic");
                yield new Predicate.Alphabetic();
            }
            case "Alphanumeric" -> {
                checkFields(tagged.body(), "Alphanumeric");
                yield new Predicate.Alphanumeric();
            }
            case "Numeric" -> {
                checkFields(tagged.body(), "Numeric");
                yield new Predicate.Numeric();
            }
            case "Whitespace" -> {
                checkFields(tagged.body(), "Whitespace");
                yield new Predicate.Whitespace();
            }
            case "NonWhitespace" -> {
                checkFields(tagged.body(), "NonWhitespace");
                yield new Predicate.NonWhitespace();
            }
            case "Any" -> {
                checkFields(tagged.body(), "Any");
                yield new Predicate.Any();
            }
            case "Custom" -> new Predicate.Custom(readCharSet(tagged.body()));
            default -> throw new ConfigException("Unknown predicate: " + tagged.name());
        };
    }

    private static JsonNode writePredicate(final Predicate predicate) {
        return switch (predicate) {
            case Predicate.Alphabetic ignored -> NODES.stringNode("Alphabetic");
            case Predicate.Alphanumeric ignored -> NODES.stringNode("Alphanumeric");
            case Predicate.Numeric ignored -> NODES.stringNode("Numeric");
            case Predicate.Whitespace ignored -> NODES.stringNode("Whitespace");
            case Predicate.NonWhitespace ignored -> NODES.stringNode("NonWhitespace");
            case Predicate.Any ignored -> NODES.stringNode("Any");
            case Predicate.Custom custom -> wrap("Custom", writeCharSet(custom.charSet()));
        };
    }

    private static CharSet readCharSet(final JsonNode node) {
        checkFields(node, "charset", "expression", "chars", "ranges", "negated");
        final List<Character> chars = new ArrayList<>();
        for (final JsonNode ch : node.path("chars")) {
            chars.add(character(ch));
        }
        final List<CharSet.Range> ranges = new ArrayList<>();
        for (final JsonNode range : node.path("ranges")) {
            if (!range.isArray() || range.size() != 2) {
                throw new ConfigException("A charset range must be a [from, to] pair");
            }
            ranges.add(new CharSet.Range(character(range.get(0)), character(range.get(1))));
        }
        return new CharSet(text(node, "expression", "charset"), chars, ranges,
                node.path("negated").asBoolean(false));
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
        final ObjectNode node = NODES.objectNode();
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

    // -----------------------------------------------------------------------------------
    // Captures
    // -----------------------------------------------------------------------------------

    private static CaptureBinding readCapture(final JsonNode node) {
        checkFields(node, "capture", "name", "select");
        return new CaptureBinding(
                text(node, "name", "capture"), readCaptureSource(required(node, "select", "capture")));
    }

    private static ObjectNode writeCapture(final CaptureBinding capture) {
        final ObjectNode node = NODES.objectNode();
        node.put("name", capture.name());
        node.set("select", writeCaptureSource(capture.select()));
        return node;
    }

    private static CaptureSource readCaptureSource(final JsonNode node) {
        final Tagged tagged = tag(node, "capture source");
        final JsonNode body = tagged.body();
        return switch (tagged.name()) {
            case "group" -> new CaptureSource.Group(body.asInt());
            case "step" -> new CaptureSource.Step(body.asInt());
            case "field" -> new CaptureSource.Field(body.asString());
            case "select" -> new CaptureSource.Select(readRef(body));
            case "key-value" -> {
                checkFields(body, "key-value", "key_ref", "value_ref");
                yield new CaptureSource.KeyValue(
                        readRef(required(body, "key_ref", "key-value")),
                        readRef(required(body, "value_ref", "key-value")));
            }
            default -> throw new ConfigException("Unknown capture source: " + tagged.name());
        };
    }

    private static JsonNode writeCaptureSource(final CaptureSource source) {
        return switch (source) {
            case CaptureSource.Group group -> wrap("group", NODES.numberNode(group.group()));
            case CaptureSource.Step step -> wrap("step", NODES.numberNode(step.index()));
            case CaptureSource.Field field -> wrap("field", NODES.stringNode(field.name()));
            case CaptureSource.Select select -> wrap("select", writeRef(select.select()));
            case CaptureSource.KeyValue keyValue -> {
                final ObjectNode body = NODES.objectNode();
                body.set("key_ref", writeRef(keyValue.keyRef()));
                body.set("value_ref", writeRef(keyValue.valueRef()));
                yield wrap("key-value", body);
            }
        };
    }

    // -----------------------------------------------------------------------------------
    // Reference expressions
    // -----------------------------------------------------------------------------------

    private static RefExpression readRef(final JsonNode node) {
        checkFields(node, "reference", "parts");
        return new RefExpression(list(node.get("parts"), "parts", ProjectJson::readRefPart));
    }

    private static ObjectNode writeRef(final RefExpression ref) {
        final ObjectNode node = NODES.objectNode();
        node.set("parts", array(ref.parts(), ProjectJson::writeRefPart));
        return node;
    }

    private static RefPart readRefPart(final JsonNode node) {
        final Tagged tagged = tag(node, "reference part");
        final JsonNode body = tagged.body();
        return switch (tagged.name()) {
            // "Store" is the old spelling. serde carries it as an alias and so must we — a
            // thousand of the corpus's parts still use it, and rewriting them would be a
            // change to fixtures the port is meant to be measured against.
            case "capture", "Store" -> {
                checkFields(body, "capture", "var_id", "group", "match_index");
                yield new RefPart.Capture(
                        optionalText(body, "var_id"),
                        body.path("group").asInt(0),
                        body.has("match_index") && !body.get("match_index").isNull()
                                ? readMatchIndex(body.get("match_index"))
                                : null);
            }
            case "text" -> new RefPart.Text(body.asString());
            default -> throw new ConfigException("Unknown reference part: " + tagged.name());
        };
    }

    private static JsonNode writeRefPart(final RefPart part) {
        return switch (part) {
            case RefPart.Capture capture -> {
                final ObjectNode body = NODES.objectNode();
                putIfPresent(body, "var_id", capture.varId());
                body.put("group", capture.group());
                if (capture.matchIndex() != null) {
                    body.set("match_index", writeMatchIndex(capture.matchIndex()));
                }
                yield wrap("capture", body);
            }
            case RefPart.Text value -> wrap("text", NODES.stringNode(value.value()));
        };
    }

    private static MatchIndex readMatchIndex(final JsonNode node) {
        checkFields(node, "match index", "index", "is_offset", "is_last", "var_ref");
        return new MatchIndex(
                node.path("index").asInt(0),
                node.path("is_offset").asBoolean(false),
                node.path("is_last").asBoolean(false),
                optionalText(node, "var_ref"));
    }

    private static ObjectNode writeMatchIndex(final MatchIndex index) {
        final ObjectNode node = NODES.objectNode();
        node.put("index", index.index());
        node.put("is_offset", index.isOffset());
        node.put("is_last", index.isLast());
        putIfPresent(node, "var_ref", index.varRef());
        return node;
    }

    // -----------------------------------------------------------------------------------
    // Conditions
    // -----------------------------------------------------------------------------------

    private static Condition readCondition(final JsonNode node) {
        final Tagged tagged = tag(node, "condition");
        final JsonNode body = tagged.body();
        return switch (tagged.name()) {
            // The six comparisons, and beneath them the five legacy spellings, kept for
            // ever as aliases (the Store/capture precedent). Each alias carries the cast its
            // semantics always implied: as-string on both sides for the equality trio — the
            // engine's counters are already typed Int, and legacy equality compares string
            // forms — and as-number on the left for the ordered pair (design/17 §8, the
            // phase 1 audit's correction).
            case "eq" -> readCompare(body, Condition.Compare.Op.EQ);
            case "ne" -> readCompare(body, Condition.Compare.Op.NE);
            case "lt" -> readCompare(body, Condition.Compare.Op.LT);
            case "le" -> readCompare(body, Condition.Compare.Op.LE);
            case "gt" -> readCompare(body, Condition.Compare.Op.GT);
            case "ge" -> readCompare(body, Condition.Compare.Op.GE);
            case "equals" -> {
                checkFields(body, "equals", "select", "value");
                yield stringEquality(Condition.Compare.Op.EQ,
                        readRef(required(body, "select", "equals")), text(body, "value", "equals"));
            }
            case "not-equals" -> {
                checkFields(body, "not-equals", "select", "value");
                yield stringEquality(Condition.Compare.Op.NE,
                        readRef(required(body, "select", "not-equals")), text(body, "value", "not-equals"));
            }
            case "ref-equals" -> {
                checkFields(body, "ref-equals", "left", "right");
                // Legacy ref-equals read both sides through "absent counts as empty", so two
                // absent sides were equal. The strict eq says absent never compares — the
                // both-absent case rides alongside explicitly (the phase 3 audit's finding).
                final RefExpression left = readRef(required(body, "left", "ref-equals"));
                final RefExpression right = readRef(required(body, "right", "ref-equals"));
                yield new Condition.Or(List.of(
                        new Condition.Compare(Condition.Compare.Op.EQ,
                                new Condition.Operand(left, null, Cast.STRING),
                                new Condition.Operand(right, null, Cast.STRING)),
                        new Condition.And(List.of(
                                new Condition.Not(new Condition.Exists(left)),
                                new Condition.Not(new Condition.Exists(right))))));
            }
            case "matches" -> {
                checkFields(body, "matches", "select", "pattern");
                yield new Condition.Matches(
                        readRef(required(body, "select", "matches")), text(body, "pattern", "matches"));
            }
            case "contains" -> {
                checkFields(body, "contains", "select", "substring");
                yield new Condition.Contains(
                        readRef(required(body, "select", "contains")), text(body, "substring", "contains"));
            }
            case "starts-with" -> {
                checkFields(body, "starts-with", "select", "prefix");
                yield new Condition.StartsWith(
                        readRef(required(body, "select", "starts-with")), text(body, "prefix", "starts-with"));
            }
            case "greater-than" -> {
                checkFields(body, "greater-than", "select", "value");
                yield numericOrdering(Condition.Compare.Op.GT,
                        readRef(required(body, "select", "greater-than")),
                        required(body, "value", "greater-than").asDouble());
            }
            case "less-than" -> {
                checkFields(body, "less-than", "select", "value");
                yield numericOrdering(Condition.Compare.Op.LT,
                        readRef(required(body, "select", "less-than")),
                        required(body, "value", "less-than").asDouble());
            }
            case "and" -> new Condition.And(list(body, "and", ProjectJson::readCondition));
            case "or" -> new Condition.Or(list(body, "or", ProjectJson::readCondition));
            case "not" -> new Condition.Not(readCondition(body));
            case "exists" -> {
                checkFields(body, "exists", "select");
                yield new Condition.Exists(readRef(required(body, "select", "exists")));
            }
            default -> throw new ConfigException("Unknown condition: " + tagged.name());
        };
    }

    private static Condition readCompare(final JsonNode body, final Condition.Compare.Op op) {
        checkFields(body, "comparison", "left", "right");
        return new Condition.Compare(op,
                readOperand(required(body, "left", "comparison")),
                readOperand(required(body, "right", "comparison")));
    }

    /**
     * A legacy equality: string forms compared, whatever the types (design/17 §8) — with the
     * legacy absent rule preserved exactly (the phase 3 audit's finding). The old evaluator
     * read an absent side as the empty string, so {@code equals($x, "")} was an absence test
     * and {@code not-equals($x, "v")} was true on a missing field. The strict {@code eq}
     * says absent never compares, so the aliases spell those cases out: an empty literal
     * becomes an {@code exists} test, and {@code not-equals} becomes {@code not(eq(...))},
     * which is true on absence exactly as the old reading was.
     */
    private static Condition stringEquality(final Condition.Compare.Op op,
                                            final RefExpression select,
                                            final String value) {
        if (value.isEmpty()) {
            // Empty is absent: matching "" is exactly "there is no value".
            final Condition missing = new Condition.Not(new Condition.Exists(select));
            return op == Condition.Compare.Op.EQ ? missing : new Condition.Exists(select);
        }
        final Condition equal = new Condition.Compare(Condition.Compare.Op.EQ,
                new Condition.Operand(select, null, Cast.STRING),
                new Condition.Operand(null, new Condition.Literal.Text(value), Cast.STRING));
        return op == Condition.Compare.Op.EQ ? equal : new Condition.Not(equal);
    }

    /** A legacy ordering: the numeric parse it always performed, made visible. */
    private static Condition numericOrdering(final Condition.Compare.Op op,
                                             final RefExpression select,
                                             final double value) {
        return new Condition.Compare(op,
                new Condition.Operand(select, null, Cast.NUMBER),
                new Condition.Operand(null, new Condition.Literal.Fractional(value), null));
    }

    private static Condition.Operand readOperand(final JsonNode node) {
        checkFields(node, "operand", "ref", "value", "as");
        final Cast as;
        if (node.has("as") && !node.get("as").isNull()) {
            final String label = node.get("as").asString();
            try {
                as = Cast.valueOf(label.toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new ConfigException("Unknown cast: " + label);
            }
        } else {
            as = null;
        }
        final boolean hasRef = node.has("ref") && !node.get("ref").isNull();
        final boolean hasValue = node.has("value") && !node.get("value").isNull();
        if (hasRef == hasValue) {
            throw new ConfigException("An operand is a ref or a value, exactly one");
        }
        if (hasRef) {
            return new Condition.Operand(readRef(node.get("ref")), null, as);
        }
        // The literal's JSON type is its declared type (design/17 §8).
        final JsonNode value = node.get("value");
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

    private static JsonNode writeOperand(final Condition.Operand operand) {
        final ObjectNode node = NODES.objectNode();
        if (operand.ref() != null) {
            node.set("ref", writeRef(operand.ref()));
        } else {
            switch (operand.literal()) {
                case Condition.Literal.Text value -> node.put("value", value.value());
                case Condition.Literal.Whole value -> node.put("value", value.value());
                case Condition.Literal.Fractional value -> node.put("value", value.value());
                case Condition.Literal.Truth value -> node.put("value", value.value());
            }
        }
        if (operand.as() != null) {
            node.put("as", operand.as().name().toLowerCase(Locale.ROOT));
        }
        return node;
    }

    private static JsonNode writeCondition(final Condition condition) {
        return switch (condition) {
            case Condition.Compare value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("left", writeOperand(value.left()));
                body.set("right", writeOperand(value.right()));
                yield wrap(value.op().name().toLowerCase(Locale.ROOT), body);
            }
            case Condition.Matches value -> wrap("matches", selectAnd("pattern", value.select(), value.pattern()));
            case Condition.Contains value ->
                    wrap("contains", selectAnd("substring", value.select(), value.substring()));
            case Condition.StartsWith value ->
                    wrap("starts-with", selectAnd("prefix", value.select(), value.prefix()));
            case Condition.And value -> wrap("and", array(value.conditions(), ProjectJson::writeCondition));
            case Condition.Or value -> wrap("or", array(value.conditions(), ProjectJson::writeCondition));
            case Condition.Not value -> wrap("not", writeCondition(value.condition()));
            case Condition.Exists value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("select", writeRef(value.select()));
                yield wrap("exists", body);
            }
        };
    }

    private static ObjectNode selectAnd(final String field, final RefExpression select, final String value) {
        final ObjectNode body = NODES.objectNode();
        body.set("select", writeRef(select));
        body.put(field, value);
        return body;
    }

    // -----------------------------------------------------------------------------------
    // Output nodes
    // -----------------------------------------------------------------------------------

    private static OutputNode readOutput(final JsonNode node) {
        final Tagged tagged = tag(node, "output node");
        final JsonNode body = tagged.body();
        return switch (tagged.name()) {
            case "text" -> new OutputNode.Text(body.asString());
            case "value-of" -> new OutputNode.ValueOf(readRef(body));
            case "if" -> {
                checkFields(body, "if", "test", "then");
                yield new OutputNode.If(
                        readCondition(required(body, "test", "if")),
                        list(body.get("then"), "then", ProjectJson::readOutput));
            }
            case "choose" -> {
                checkFields(body, "choose", "when", "otherwise");
                yield new OutputNode.Choose(
                        list(body.get("when"), "when", ProjectJson::readWhen),
                        list(body.get("otherwise"), "otherwise", ProjectJson::readOutput));
            }
            case "switch" -> {
                checkFields(body, "switch", "select", "cases", "default");
                yield new OutputNode.Switch(
                        readRef(required(body, "select", "switch")),
                        list(body.get("cases"), "cases", ProjectJson::readCase),
                        list(body.get("default"), "default", ProjectJson::readOutput));
            }
            case "apply-templates" -> new OutputNode.ApplyTemplates(readApply(body));
            case "emit-error" -> {
                checkFields(body, "emit-error", "severity", "message");
                final String severity = text(body, "severity", "emit-error");
                try {
                    yield new OutputNode.EmitError(
                            Severity.valueOf(severity.toUpperCase(Locale.ROOT)),
                            readRef(required(body, "message", "emit-error")));
                } catch (final IllegalArgumentException e) {
                    throw new ConfigException("Unknown emit-error severity: " + severity);
                }
            }
            case "call-template" -> {
                checkFields(body, "call-template", "name", "with-param");
                yield new OutputNode.CallTemplate(
                        text(body, "name", "call-template"),
                        list(body.get("with-param"), "with-param", ProjectJson::readParam));
            }
            case "variable" -> {
                checkFields(body, "variable", "name", "body");
                yield new OutputNode.Variable(
                        text(body, "name", "variable"), list(body.get("body"), "body", ProjectJson::readOutput));
            }
            case "value-map" -> {
                checkFields(body, "value-map", "select", "entries", "default", "name");
                yield new OutputNode.ValueMap(
                        readRef(required(body, "select", "value-map")),
                        list(body.get("entries"), "entries", ProjectJson::readEntry),
                        optionalText(body, "default"),
                        optionalText(body, "name"));
            }
            case "translate" -> {
                checkFields(body, "translate", "select", "from", "to", "name");
                yield new OutputNode.Translate(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        list(body.get("from"), "from", JsonNode::asString),
                        list(body.get("to"), "to", JsonNode::asString),
                        optionalText(body, "name"));
            }
            case "string-join" -> {
                checkFields(body, "string-join", "select", "separator", "name");
                yield new OutputNode.StringJoin(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        optionalText(body, "separator"),
                        optionalText(body, "name"));
            }
            case "replace" -> {
                checkFields(body, "replace", "select", "pattern", "replacement", "is_regex", "name");
                yield new OutputNode.Replace(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        text(body, "pattern", "replace"),
                        text(body, "replacement", "replace"),
                        body.path("is_regex").asBoolean(false),
                        optionalText(body, "name"));
            }
            case "lower-case" -> new OutputNode.LowerCase(
                    selectList(body, "lower-case"), optionalText(body, "name"));
            case "upper-case" -> new OutputNode.UpperCase(
                    selectList(body, "upper-case"), optionalText(body, "name"));
            case "normalize-space" -> new OutputNode.NormalizeSpace(
                    selectList(body, "normalize-space"), optionalText(body, "name"));
            case "trim" -> new OutputNode.Trim(selectList(body, "trim"), optionalText(body, "name"));
            case "substring" -> {
                checkFields(body, "substring", "select", "start", "length", "name");
                yield new OutputNode.Substring(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        body.path("start").asInt(0),
                        body.has("length") && !body.get("length").isNull() ? body.get("length").asInt() : null,
                        optionalText(body, "name"));
            }
            case "tokenize" -> {
                checkFields(body, "tokenize", "select", "delimiter", "name");
                yield new OutputNode.Tokenize(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        text(body, "delimiter", "tokenize"),
                        optionalText(body, "name"));
            }
            case "number" -> new OutputNode.Number(selectList(body, "number"), optionalText(body, "name"));
            case "add" -> new OutputNode.Add(selectList(body, "add"), optionalText(body, "name"));
            case "subtract" -> new OutputNode.Subtract(
                    selectList(body, "subtract"), optionalText(body, "name"));
            case "multiply" -> new OutputNode.Multiply(
                    selectList(body, "multiply"), optionalText(body, "name"));
            case "divide" -> new OutputNode.Divide(selectList(body, "divide"), optionalText(body, "name"));
            case "mod" -> new OutputNode.Mod(selectList(body, "mod"), optionalText(body, "name"));
            case "round" -> new OutputNode.Round(selectList(body, "round"), optionalText(body, "name"));
            case "floor" -> new OutputNode.Floor(selectList(body, "floor"), optionalText(body, "name"));
            case "ceiling" -> new OutputNode.Ceiling(
                    selectList(body, "ceiling"), optionalText(body, "name"));
            case "abs" -> new OutputNode.Abs(selectList(body, "abs"), optionalText(body, "name"));
            case "string-length" -> new OutputNode.StringLength(
                    selectList(body, "string-length"), optionalText(body, "name"));
            case "substring-before" -> {
                checkFields(body, "substring-before", "select", "marker", "name");
                yield new OutputNode.SubstringBefore(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        text(body, "marker", "substring-before"),
                        optionalText(body, "name"));
            }
            case "substring-after" -> {
                checkFields(body, "substring-after", "select", "marker", "name");
                yield new OutputNode.SubstringAfter(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        text(body, "marker", "substring-after"),
                        optionalText(body, "name"));
            }
            case "starts-with" -> {
                checkFields(body, "starts-with", "select", "prefix", "name");
                yield new OutputNode.StartsWith(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        text(body, "prefix", "starts-with"),
                        optionalText(body, "name"));
            }
            case "ends-with" -> {
                checkFields(body, "ends-with", "select", "suffix", "name");
                yield new OutputNode.EndsWith(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        text(body, "suffix", "ends-with"),
                        optionalText(body, "name"));
            }
            case "contains" -> {
                checkFields(body, "contains", "select", "substring", "name");
                yield new OutputNode.Contains(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        text(body, "substring", "contains"),
                        optionalText(body, "name"));
            }
            case "format-number" -> {
                checkFields(body, "format-number", "select", "picture", "name");
                yield new OutputNode.FormatNumber(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        text(body, "picture", "format-number"),
                        optionalText(body, "name"));
            }
            case "parse-date" -> {
                checkFields(body, "parse-date", "select", "pattern", "timezone", "reference", "name");
                yield new OutputNode.ParseDate(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        text(body, "pattern", "parse-date"),
                        optionalText(body, "timezone"),
                        body.has("reference") && !body.get("reference").isNull()
                                ? readRef(body.get("reference"))
                                : null,
                        optionalText(body, "name"));
            }
            case "format-date" -> {
                checkFields(body, "format-date", "select", "pattern", "timezone", "name");
                yield new OutputNode.FormatDate(
                        list(body.get("select"), "select", ProjectJson::readRef),
                        text(body, "pattern", "format-date"),
                        optionalText(body, "timezone"),
                        optionalText(body, "name"));
            }
            default -> throw new ConfigException("Unknown output node: " + tagged.name());
        };
    }

    private static List<RefExpression> selectList(final JsonNode body, final String owner) {
        checkFields(body, owner, "select", "name");
        return list(body.get("select"), "select", ProjectJson::readRef);
    }

    private static JsonNode writeOutput(final OutputNode output) {
        return switch (output) {
            case OutputNode.Text value -> wrap("text", NODES.stringNode(value.value()));
            case OutputNode.ValueOf value -> wrap("value-of", writeRef(value.select()));
            case OutputNode.If value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("test", writeCondition(value.test()));
                body.set("then", array(value.then(), ProjectJson::writeOutput));
                yield wrap("if", body);
            }
            case OutputNode.Choose value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("when", array(value.when(), ProjectJson::writeWhen));
                if (!value.otherwise().isEmpty()) {
                    body.set("otherwise", array(value.otherwise(), ProjectJson::writeOutput));
                }
                yield wrap("choose", body);
            }
            case OutputNode.Switch value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("select", writeRef(value.select()));
                body.set("cases", array(value.cases(), ProjectJson::writeCase));
                if (!value.defaultBody().isEmpty()) {
                    body.set("default", array(value.defaultBody(), ProjectJson::writeOutput));
                }
                yield wrap("switch", body);
            }
            case OutputNode.ApplyTemplates value -> wrap("apply-templates", writeApply(value.directive()));
            case OutputNode.EmitError value -> {
                final ObjectNode body = NODES.objectNode();
                body.put("severity", value.severity().name().toLowerCase(Locale.ROOT));
                body.set("message", writeRef(value.message()));
                yield wrap("emit-error", body);
            }
            case OutputNode.CallTemplate value -> {
                final ObjectNode body = NODES.objectNode();
                body.put("name", value.name());
                if (!value.withParam().isEmpty()) {
                    body.set("with-param", array(value.withParam(), ProjectJson::writeParam));
                }
                yield wrap("call-template", body);
            }
            case OutputNode.Variable value -> {
                final ObjectNode body = NODES.objectNode();
                body.put("name", value.name());
                body.set("body", array(value.body(), ProjectJson::writeOutput));
                yield wrap("variable", body);
            }
            case OutputNode.ValueMap value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("select", writeRef(value.select()));
                body.set("entries", array(value.entries(), ProjectJson::writeEntry));
                putIfPresent(body, "default", value.defaultValue());
                putIfPresent(body, "name", value.name());
                yield wrap("value-map", body);
            }
            case OutputNode.Translate value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("select", array(value.select(), ProjectJson::writeRef));
                final ArrayNode from = body.putArray("from");
                value.from().forEach(from::add);
                final ArrayNode to = body.putArray("to");
                value.to().forEach(to::add);
                putIfPresent(body, "name", value.name());
                yield wrap("translate", body);
            }
            case OutputNode.StringJoin value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("select", array(value.select(), ProjectJson::writeRef));
                putIfPresent(body, "separator", value.separator());
                putIfPresent(body, "name", value.name());
                yield wrap("string-join", body);
            }
            case OutputNode.Replace value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("select", array(value.select(), ProjectJson::writeRef));
                body.put("pattern", value.pattern());
                body.put("replacement", value.replacement());
                body.put("is_regex", value.isRegex());
                putIfPresent(body, "name", value.name());
                yield wrap("replace", body);
            }
            case OutputNode.LowerCase value -> wrap("lower-case", selectAndName(value.select(), value.name()));
            case OutputNode.UpperCase value -> wrap("upper-case", selectAndName(value.select(), value.name()));
            case OutputNode.NormalizeSpace value ->
                    wrap("normalize-space", selectAndName(value.select(), value.name()));
            case OutputNode.Trim value -> wrap("trim", selectAndName(value.select(), value.name()));
            case OutputNode.Substring value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("select", array(value.select(), ProjectJson::writeRef));
                body.put("start", value.start());
                if (value.length() != null) {
                    body.put("length", value.length());
                }
                putIfPresent(body, "name", value.name());
                yield wrap("substring", body);
            }
            case OutputNode.Tokenize value -> {
                final ObjectNode body = NODES.objectNode();
                body.set("select", array(value.select(), ProjectJson::writeRef));
                body.put("delimiter", value.delimiter());
                putIfPresent(body, "name", value.name());
                yield wrap("tokenize", body);
            }
            case OutputNode.Number value -> wrap("number", selectAndName(value.select(), value.name()));
            case OutputNode.Add value -> wrap("add", selectAndName(value.select(), value.name()));
            case OutputNode.Subtract value ->
                    wrap("subtract", selectAndName(value.select(), value.name()));
            case OutputNode.Multiply value ->
                    wrap("multiply", selectAndName(value.select(), value.name()));
            case OutputNode.Divide value -> wrap("divide", selectAndName(value.select(), value.name()));
            case OutputNode.Mod value -> wrap("mod", selectAndName(value.select(), value.name()));
            case OutputNode.Round value -> wrap("round", selectAndName(value.select(), value.name()));
            case OutputNode.Floor value -> wrap("floor", selectAndName(value.select(), value.name()));
            case OutputNode.Ceiling value ->
                    wrap("ceiling", selectAndName(value.select(), value.name()));
            case OutputNode.Abs value -> wrap("abs", selectAndName(value.select(), value.name()));
            case OutputNode.StringLength value ->
                    wrap("string-length", selectAndName(value.select(), value.name()));
            case OutputNode.SubstringBefore value ->
                    wrap("substring-before", selectAndMarker(value.select(), "marker",
                            value.marker(), value.name()));
            case OutputNode.SubstringAfter value ->
                    wrap("substring-after", selectAndMarker(value.select(), "marker",
                            value.marker(), value.name()));
            case OutputNode.StartsWith value ->
                    wrap("starts-with", selectAndMarker(value.select(), "prefix",
                            value.prefix(), value.name()));
            case OutputNode.EndsWith value ->
                    wrap("ends-with", selectAndMarker(value.select(), "suffix",
                            value.suffix(), value.name()));
            case OutputNode.Contains value ->
                    wrap("contains", selectAndMarker(value.select(), "substring",
                            value.substring(), value.name()));
            case OutputNode.FormatNumber value ->
                    wrap("format-number", selectAndMarker(value.select(), "picture",
                            value.picture(), value.name()));
            case OutputNode.ParseDate value -> {
                final ObjectNode body = selectAndMarker(value.select(), "pattern",
                        value.pattern(), value.name());
                putIfPresent(body, "timezone", value.timezone());
                if (value.reference() != null) {
                    body.set("reference", writeRef(value.reference()));
                }
                yield wrap("parse-date", body);
            }
            case OutputNode.FormatDate value -> {
                final ObjectNode body = selectAndMarker(value.select(), "pattern",
                        value.pattern(), value.name());
                putIfPresent(body, "timezone", value.timezone());
                yield wrap("format-date", body);
            }
        };
    }

    /** A one-input transform with one string parameter beside its select. */
    private static ObjectNode selectAndMarker(final List<RefExpression> select,
                                              final String field,
                                              final String parameter,
                                              final String name) {
        final ObjectNode body = selectAndName(select, name);
        body.put(field, parameter);
        return body;
    }

    private static ObjectNode selectAndName(final List<RefExpression> select, final String name) {
        final ObjectNode body = NODES.objectNode();
        body.set("select", array(select, ProjectJson::writeRef));
        putIfPresent(body, "name", name);
        return body;
    }

    private static WhenBranch readWhen(final JsonNode node) {
        checkFields(node, "when", "test", "body");
        return new WhenBranch(
                readCondition(required(node, "test", "when")),
                list(node.get("body"), "body", ProjectJson::readOutput));
    }

    private static ObjectNode writeWhen(final WhenBranch branch) {
        final ObjectNode node = NODES.objectNode();
        node.set("test", writeCondition(branch.test()));
        node.set("body", array(branch.body(), ProjectJson::writeOutput));
        return node;
    }

    private static SwitchCase readCase(final JsonNode node) {
        checkFields(node, "case", "value", "body");
        return new SwitchCase(
                text(node, "value", "case"), list(node.get("body"), "body", ProjectJson::readOutput));
    }

    private static ObjectNode writeCase(final SwitchCase switchCase) {
        final ObjectNode node = NODES.objectNode();
        node.put("value", switchCase.value());
        node.set("body", array(switchCase.body(), ProjectJson::writeOutput));
        return node;
    }

    private static Entry readEntry(final JsonNode node) {
        checkFields(node, "entry", "from", "to");
        return new Entry(text(node, "from", "entry"), text(node, "to", "entry"));
    }

    private static ObjectNode writeEntry(final Entry entry) {
        final ObjectNode node = NODES.objectNode();
        node.put("from", entry.from());
        node.put("to", entry.to());
        return node;
    }

    /** A parameter is a two-element array, which is how serde carries a tuple. */
    private static Param readParam(final JsonNode node) {
        if (!node.isArray() || node.size() != 2) {
            throw new ConfigException("A parameter must be a [name, value] pair");
        }
        return new Param(node.get(0).asString(), readRef(node.get(1)));
    }

    private static ArrayNode writeParam(final Param param) {
        final ArrayNode node = NODES.arrayNode();
        node.add(param.name());
        node.add(writeRef(param.value()));
        return node;
    }

    private static ApplyDirective readApply(final JsonNode node) {
        checkFields(node, "apply-templates", "select", "mode", "with-param", "max_depth", "template_ref",
                "ignore_errors", "dispatch");
        return new ApplyDirective(
                readRef(required(node, "select", "apply-templates")),
                optionalText(node, "mode"),
                list(node.get("with-param"), "with-param", ProjectJson::readParam),
                node.path("max_depth").asInt(ApplyDirective.DEFAULT_MAX_DEPTH),
                optionalText(node, "template_ref"),
                node.path("ignore_errors").asBoolean(false),
                readDispatch(node));
    }

    private static ObjectNode writeApply(final ApplyDirective directive) {
        final ObjectNode node = NODES.objectNode();
        node.set("select", writeRef(directive.select()));
        putIfPresent(node, "mode", directive.mode());
        if (!directive.withParam().isEmpty()) {
            node.set("with-param", array(directive.withParam(), ProjectJson::writeParam));
        }
        node.put("max_depth", directive.maxDepth());
        putIfPresent(node, "template_ref", directive.templateRef());
        if (directive.ignoreErrors()) {
            node.put("ignore_errors", true);
        }
        writeDispatch(node, directive.dispatch());
        return node;
    }

    // -----------------------------------------------------------------------------------
    // Wire-format primitives
    // -----------------------------------------------------------------------------------

    /** A variant and its payload, however serde chose to spell them. */
    private record Tagged(String name, JsonNode body) {

    }

    /**
     * Read a sum type's variant.
     *
     * <p>Two spellings, and the difference is not decorative: a variant carrying nothing is a
     * bare string, and a variant carrying something is a single-key object. Anything else — an
     * object with two keys, say — means the document was produced by something that is not
     * serde, and saying so here is more useful than guessing.
     */
    private static Tagged tag(final JsonNode node, final String what) {
        if (node == null || node.isNull()) {
            throw new ConfigException("Missing " + what);
        }
        if (node.isString()) {
            return new Tagged(node.asString(), NODES.objectNode());
        }
        if (!node.isObject()) {
            throw new ConfigException(
                    "A " + what + " must be a name or a single-key object, but was of type "
                    + node.getNodeType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        if (node.size() != 1) {
            throw new ConfigException(
                    "A " + what + " must be a name or a single-key object, but had " + node.size() + " keys");
        }
        final var property = node.propertyStream().findFirst().orElseThrow();
        return new Tagged(property.getKey(), property.getValue());
    }

    private static ObjectNode wrap(final String name, final JsonNode body) {
        final ObjectNode node = NODES.objectNode();
        node.set(name, body);
        return node;
    }

    /**
     * Reject fields the model does not know.
     *
     * <p>The alternative is a configuration that loads with a typo in it and runs with the
     * setting silently absent, which is a bug that presents as a data problem weeks later.
     * Absent is tolerated — an optional object that is not there — but a value of the wrong
     * shape is not, because a document carrying a string where an object belongs was written
     * by something with a different format in mind.
     */
    private static void checkFields(final JsonNode node, final String what, final String... known) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            throw new ConfigException("Expected an object for '" + what + "'");
        }
        node.propertyStream().forEach(property -> {
            for (final String field : known) {
                if (field.equals(property.getKey())) {
                    return;
                }
            }
            throw new ConfigException("Unknown field '" + property.getKey() + "' in " + what);
        });
    }

    private static void expectObject(final JsonNode node, final String what) {
        if (node == null || !node.isObject()) {
            throw new ConfigException("Expected an object for " + what);
        }
    }

    private static JsonNode required(final JsonNode node, final String field, final String what) {
        final JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            throw new ConfigException("Missing '" + field + "' in " + what);
        }
        return value;
    }

    private static String text(final JsonNode node, final String field, final String what) {
        return required(node, field, what).asString();
    }

    private static String optionalText(final JsonNode node, final String field) {
        final JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static UUID uuid(final JsonNode node, final String field, final String what) {
        try {
            return UUID.fromString(text(node, field, what));
        } catch (final IllegalArgumentException e) {
            throw new ConfigException("Not a valid id: " + optionalText(node, field), e);
        }
    }

    private static void putIfPresent(final ObjectNode node, final String field, final String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static <T> List<T> list(final JsonNode node, final String what,
                                    final Function<JsonNode, T> reader) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ConfigException("Expected an array for '" + what + "'");
        }
        final List<T> values = new ArrayList<>(node.size());
        node.forEach(child -> values.add(reader.apply(child)));
        return values;
    }

    private static <T> ArrayNode array(final List<T> values, final Function<T, ? extends JsonNode> writer) {
        final ArrayNode node = NODES.arrayNode(values.size());
        values.forEach(value -> node.add(writer.apply(value)));
        return node;
    }

    /**
     * Rust's enum names are PascalCase and Java's constants are SCREAMING_SNAKE, so the two
     * have to be mapped rather than matched. Doing it by shape keeps the enums free of
     * serialisation detail.
     */
    private static <E extends Enum<E>> E constant(final Class<E> type, final String name) {
        final String screaming = name
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT);
        for (final E value : type.getEnumConstants()) {
            if (value.name().equals(screaming)) {
                return value;
            }
        }
        throw new ConfigException("Unknown " + type.getSimpleName() + ": " + name);
    }

    private static String name(final Enum<?> value) {
        final StringBuilder pascal = new StringBuilder();
        for (final String word : value.name().split("_")) {
            pascal.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return pascal.toString();
    }
}
