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
import stroom.shapeshifter.engine.config.Codec;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.OutputNode.Param;
import stroom.shapeshifter.engine.config.OutputNode.SwitchCase;
import stroom.shapeshifter.engine.config.OutputNode.WhenBranch;
import stroom.shapeshifter.engine.config.RefExpression;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * The output family of the wire format: every output instruction, sorts, branches, cases,
 * entries, {@code with-param} parameters and apply directives — read and written together.
 */
final class OutputJson {

    private OutputJson() {
    }

    static OutputNode readOutput(final JsonNode node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "output node");
        final JsonNode body = tagged.body();
        return switch (tagged.name()) {
            case "text" -> new OutputNode.Text(JsonFields.text(body, "text"));
            case "value-of" -> new OutputNode.ValueOf(ReferenceJson.readRef(body));
            case "call" -> {
                JsonFields.checkFields(body, "call", "function", "select", "name");
                yield new OutputNode.Call(JsonFields.text(body, "function", "call"),
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.optionalText(body, "name"));
            }
            case "if" -> {
                JsonFields.checkFields(body, "if", "test", "then");
                yield new OutputNode.If(
                        ConditionJson.readCondition(JsonFields.required(body, "test", "if")),
                        JsonFields.list(body.get("then"), "then", OutputJson::readOutput));
            }
            case "choose" -> {
                JsonFields.checkFields(body, "choose", "when", "otherwise");
                yield new OutputNode.Choose(
                        JsonFields.list(body.get("when"), "when", OutputJson::readWhen),
                        JsonFields.list(body.get("otherwise"), "otherwise", OutputJson::readOutput));
            }
            case "switch" -> {
                JsonFields.checkFields(body, "switch", "select", "cases", "default");
                yield new OutputNode.Switch(
                        ReferenceJson.readRef(JsonFields.required(body, "select", "switch")),
                        JsonFields.list(body.get("cases"), "cases", OutputJson::readCase),
                        JsonFields.list(body.get("default"), "default", OutputJson::readOutput));
            }
            case "apply-templates" -> new OutputNode.ApplyTemplates(readApply(body));
            case "emit-error" -> {
                JsonFields.checkFields(body, "emit-error", "severity", "message");
                final Severity level = JsonFields.lowercase(
                        Severity.class, JsonFields.text(body, "severity", "emit-error"), "emit-error severity");
                yield new OutputNode.EmitError(level, ReferenceJson.readRef(JsonFields.required(body, "message",
                        "emit-error")));
            }
            case "call-template" -> {
                JsonFields.checkFields(body, "call-template", "name", "with-param");
                yield new OutputNode.CallTemplate(
                        JsonFields.text(body, "name", "call-template"),
                        JsonFields.list(body.get("with-param"), "with-param", OutputJson::readParam));
            }
            case "variable" -> {
                JsonFields.checkFields(body, "variable", "name", "body");
                yield new OutputNode.Variable(
                        JsonFields.text(body, "name", "variable"), JsonFields.list(body.get("body"), "body",
                        OutputJson::readOutput));
            }
            case "element" -> {
                JsonFields.checkFields(body, "element", "name", "namespace", "omit-if-empty", "body");
                yield new OutputNode.Element(
                        JsonFields.text(body, "name", "element"),
                        JsonFields.optionalText(body, "namespace"),
                        body.path("omit-if-empty").asBoolean(false),
                        JsonFields.list(body.get("body"), "body", OutputJson::readOutput));
            }
            case "attribute" -> {
                JsonFields.checkFields(body, "attribute", "name", "omit-if-empty", "body");
                yield new OutputNode.Attribute(
                        JsonFields.text(body, "name", "attribute"),
                        body.path("omit-if-empty").asBoolean(false),
                        JsonFields.list(body.get("body"), "body", OutputJson::readOutput));
            }
            case "namespace" -> {
                JsonFields.checkFields(body, "namespace", "prefix", "uri");
                yield new OutputNode.Namespace(JsonFields.optionalText(body, "prefix"), JsonFields.text(body,
                        "uri", "namespace"));
            }
            case "translate" -> {
                JsonFields.checkFields(body, "translate", "select", "from", "to", "name");
                yield new OutputNode.Translate(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.list(body.get("from"), "from", JsonNode::asString),
                        JsonFields.list(body.get("to"), "to", JsonNode::asString),
                        JsonFields.optionalText(body, "name"));
            }
            case "string-join" -> {
                JsonFields.checkFields(body, "string-join", "select", "separator", "name");
                yield new OutputNode.StringJoin(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.optionalText(body, "separator"),
                        JsonFields.optionalText(body, "name"));
            }
            case "replace" -> {
                JsonFields.checkFields(body, "replace", "select", "pattern", "replacement", "is_regex", "name");
                yield new OutputNode.Replace(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.text(body, "pattern", "replace"),
                        JsonFields.text(body, "replacement", "replace"),
                        body.path("is_regex").asBoolean(false),
                        JsonFields.optionalText(body, "name"));
            }
            case "lower-case" -> new OutputNode.LowerCase(
                    selectList(body, "lower-case"), JsonFields.optionalText(body, "name"));
            case "decode" -> {
                JsonFields.checkFields(body, "decode", "select", "codec", "name");
                yield new OutputNode.Decode(JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.lowercase(Codec.class, JsonFields.text(body, "codec", "decode"), "codec"),
                        JsonFields.optionalText(body, "name"));
            }
            case "upper-case" -> new OutputNode.UpperCase(
                    selectList(body, "upper-case"), JsonFields.optionalText(body, "name"));
            case "normalize-space" -> new OutputNode.NormalizeSpace(
                    selectList(body, "normalize-space"), JsonFields.optionalText(body, "name"));
            case "trim" -> new OutputNode.Trim(selectList(body, "trim"), JsonFields.optionalText(body, "name"));
            case "substring" -> {
                JsonFields.checkFields(body, "substring", "select", "start", "length", "name");
                final JsonNode start = JsonFields.optional(body, "start");
                final JsonNode length = JsonFields.optional(body, "length");
                yield new OutputNode.Substring(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        start == null ? null : JsonFields.integer(start, "substring start"),
                        length == null ? null : JsonFields.integer(length, "substring length"),
                        JsonFields.optionalText(body, "name"));
            }
            case "tokenize" -> {
                JsonFields.checkFields(body, "tokenize", "select", "delimiter", "name");
                yield new OutputNode.Tokenize(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.text(body, "delimiter", "tokenize"),
                        JsonFields.optionalText(body, "name"));
            }
            case "number" -> new OutputNode.Number(selectList(body, "number"), JsonFields.optionalText(body, "name"));
            case "add" -> new OutputNode.Add(selectList(body, "add"), JsonFields.optionalText(body, "name"));
            case "subtract" -> new OutputNode.Subtract(
                    selectList(body, "subtract"), JsonFields.optionalText(body, "name"));
            case "multiply" -> new OutputNode.Multiply(
                    selectList(body, "multiply"), JsonFields.optionalText(body, "name"));
            case "divide" -> new OutputNode.Divide(selectList(body, "divide"), JsonFields.optionalText(body, "name"));
            case "mod" -> new OutputNode.Mod(selectList(body, "mod"), JsonFields.optionalText(body, "name"));
            case "round" -> new OutputNode.Round(selectList(body, "round"), JsonFields.optionalText(body, "name"));
            case "floor" -> new OutputNode.Floor(selectList(body, "floor"), JsonFields.optionalText(body, "name"));
            case "ceiling" -> new OutputNode.Ceiling(
                    selectList(body, "ceiling"), JsonFields.optionalText(body, "name"));
            case "abs" -> new OutputNode.Abs(selectList(body, "abs"), JsonFields.optionalText(body, "name"));
            case "string-length" -> new OutputNode.StringLength(
                    selectList(body, "string-length"), JsonFields.optionalText(body, "name"));
            case "substring-before" -> {
                JsonFields.checkFields(body, "substring-before", "select", "marker", "name");
                yield new OutputNode.SubstringBefore(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.text(body, "marker", "substring-before"),
                        JsonFields.optionalText(body, "name"));
            }
            case "substring-after" -> {
                JsonFields.checkFields(body, "substring-after", "select", "marker", "name");
                yield new OutputNode.SubstringAfter(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.text(body, "marker", "substring-after"),
                        JsonFields.optionalText(body, "name"));
            }
            case "starts-with" -> {
                JsonFields.checkFields(body, "starts-with", "select", "prefix", "name");
                yield new OutputNode.StartsWith(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.text(body, "prefix", "starts-with"),
                        JsonFields.optionalText(body, "name"));
            }
            case "ends-with" -> {
                JsonFields.checkFields(body, "ends-with", "select", "suffix", "name");
                yield new OutputNode.EndsWith(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.text(body, "suffix", "ends-with"),
                        JsonFields.optionalText(body, "name"));
            }
            case "contains" -> {
                JsonFields.checkFields(body, "contains", "select", "substring", "name");
                yield new OutputNode.Contains(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.text(body, "substring", "contains"),
                        JsonFields.optionalText(body, "name"));
            }
            case "format-number" -> {
                JsonFields.checkFields(body, "format-number", "select", "picture", "name");
                yield new OutputNode.FormatNumber(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.text(body, "picture", "format-number"),
                        JsonFields.optionalText(body, "name"));
            }
            case "append" -> {
                JsonFields.checkFields(body, "append", "name", "target", "select");
                yield new OutputNode.Append(target(body, "append"),
                        ReferenceJson.readRef(JsonFields.required(body, "select", "append")));
            }
            case "insert" -> {
                JsonFields.checkFields(body, "insert", "name", "target", "position", "select");
                yield new OutputNode.Insert(target(body, "insert"),
                        ReferenceJson.readRefOrText(JsonFields.required(body, "position", "insert")),
                        ReferenceJson.readRef(JsonFields.required(body, "select", "insert")));
            }
            case "put" -> {
                JsonFields.checkFields(body, "put", "name", "target", "key", "select");
                final JsonNode key = JsonFields.optional(body, "key");
                yield new OutputNode.Put(target(body, "put"),
                        key == null ? null : ReferenceJson.readRefOrText(key),
                        ReferenceJson.readRef(JsonFields.required(body, "select", "put")));
            }
            case "remove" -> {
                JsonFields.checkFields(body, "remove", "name", "target", "key");
                yield new OutputNode.Remove(target(body, "remove"),
                        ReferenceJson.readRefOrText(JsonFields.required(body, "key", "remove")));
            }
            case "clear" -> {
                JsonFields.checkFields(body, "clear", "name", "target");
                yield new OutputNode.Clear(target(body, "clear"));
            }
            case "for-each-group" -> {
                JsonFields.checkFields(body, "for-each-group", "select", "group_by", "body");
                yield new OutputNode.ForEachGroup(
                        ReferenceJson.readRefOrName(JsonFields.required(body, "select", "for-each-group")),
                        ReferenceJson.optionalRef(body, "group_by"),
                        JsonFields.list(body.get("body"), "body", OutputJson::readOutput));
            }
            case "for-each" -> {
                JsonFields.checkFields(body, "for-each", "select", "as", "as_key", "sort", "body");
                yield new OutputNode.ForEach(
                        ReferenceJson.readRefOrName(JsonFields.required(body, "select", "for-each")),
                        JsonFields.optionalText(body, "as"),
                        JsonFields.optionalText(body, "as_key"),
                        JsonFields.list(body.get("sort"), "sort", OutputJson::readSort),
                        JsonFields.list(body.get("body"), "body", OutputJson::readOutput));
            }
            case "parse-date" -> {
                JsonFields.checkFields(body, "parse-date", "select", "pattern", "timezone", "reference", "name");
                yield new OutputNode.ParseDate(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.text(body, "pattern", "parse-date"),
                        JsonFields.optionalText(body, "timezone"),
                        ReferenceJson.optionalRef(body, "reference"),
                        JsonFields.optionalText(body, "name"));
            }
            case "format-date" -> {
                JsonFields.checkFields(body, "format-date", "select", "pattern", "timezone", "name");
                yield new OutputNode.FormatDate(
                        JsonFields.list(body.get("select"), "select", ReferenceJson::readRef),
                        JsonFields.text(body, "pattern", "format-date"),
                        JsonFields.optionalText(body, "timezone"),
                        JsonFields.optionalText(body, "name"));
            }
            default -> throw new ConfigException("Unknown output node: " + tagged.name());
        };
    }

    /**
     * The select list of a one-input instruction, having checked the owner's fields: every such
     * instruction has {@code select} and {@code name} and nothing else.
     */
    private static List<RefExpression> selectList(final JsonNode body, final String owner) {
        JsonFields.checkFields(body, owner, "select", "name");
        return JsonFields.list(body.get("select"), "select", ReferenceJson::readRef);
    }

    static JsonNode writeOutput(final OutputNode output) {
        return switch (output) {
            case OutputNode.Text value -> JsonFields.wrap("text", JsonFields.NODES.stringNode(value.value()));
            case OutputNode.ValueOf value -> JsonFields.wrap("value-of", ReferenceJson.writeRef(value.select()));
            case OutputNode.Call value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("function", value.function());
                body.set("select", JsonFields.array(value.select(), ReferenceJson::writeRef));
                JsonFields.putIfPresent(body, "name", value.name());
                yield JsonFields.wrap("call", body);
            }
            case OutputNode.If value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("test", ConditionJson.writeCondition(value.test()));
                body.set("then", JsonFields.array(value.then(), OutputJson::writeOutput));
                yield JsonFields.wrap("if", body);
            }
            case OutputNode.Choose value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("when", JsonFields.array(value.when(), OutputJson::writeWhen));
                if (!value.otherwise().isEmpty()) {
                    body.set("otherwise", JsonFields.array(value.otherwise(), OutputJson::writeOutput));
                }
                yield JsonFields.wrap("choose", body);
            }
            case OutputNode.Switch value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("select", ReferenceJson.writeRef(value.select()));
                body.set("cases", JsonFields.array(value.cases(), OutputJson::writeCase));
                if (!value.defaultBody().isEmpty()) {
                    body.set("default", JsonFields.array(value.defaultBody(), OutputJson::writeOutput));
                }
                yield JsonFields.wrap("switch", body);
            }
            case OutputNode.ApplyTemplates value -> JsonFields.wrap("apply-templates", writeApply(value.directive()));
            case OutputNode.EmitError value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("severity", JsonFields.label(value.severity()));
                body.set("message", ReferenceJson.writeRef(value.message()));
                yield JsonFields.wrap("emit-error", body);
            }
            case OutputNode.CallTemplate value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("name", value.name());
                if (!value.withParam().isEmpty()) {
                    body.set("with-param", JsonFields.array(value.withParam(), OutputJson::writeParam));
                }
                yield JsonFields.wrap("call-template", body);
            }
            case OutputNode.Variable value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("name", value.name());
                body.set("body", JsonFields.array(value.body(), OutputJson::writeOutput));
                yield JsonFields.wrap("variable", body);
            }
            case OutputNode.Element value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("name", value.name());
                JsonFields.putIfPresent(body, "namespace", value.namespace());
                if (value.omitIfEmpty()) {
                    body.put("omit-if-empty", true);
                }
                body.set("body", JsonFields.array(value.body(), OutputJson::writeOutput));
                yield JsonFields.wrap("element", body);
            }
            case OutputNode.Attribute value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("name", value.name());
                if (value.omitIfEmpty()) {
                    body.put("omit-if-empty", true);
                }
                body.set("body", JsonFields.array(value.body(), OutputJson::writeOutput));
                yield JsonFields.wrap("attribute", body);
            }
            case OutputNode.Namespace value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                JsonFields.putIfPresent(body, "prefix", value.prefix());
                body.put("uri", value.uri());
                yield JsonFields.wrap("namespace", body);
            }
            case OutputNode.Translate value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("select", JsonFields.array(value.select(), ReferenceJson::writeRef));
                final ArrayNode from = body.putArray("from");
                value.from().forEach(from::add);
                final ArrayNode to = body.putArray("to");
                value.to().forEach(to::add);
                JsonFields.putIfPresent(body, "name", value.name());
                yield JsonFields.wrap("translate", body);
            }
            case OutputNode.StringJoin value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("select", JsonFields.array(value.select(), ReferenceJson::writeRef));
                JsonFields.putIfPresent(body, "separator", value.separator());
                JsonFields.putIfPresent(body, "name", value.name());
                yield JsonFields.wrap("string-join", body);
            }
            case OutputNode.Replace value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("select", JsonFields.array(value.select(), ReferenceJson::writeRef));
                body.put("pattern", value.pattern());
                body.put("replacement", value.replacement());
                body.put("is_regex", value.isRegex());
                JsonFields.putIfPresent(body, "name", value.name());
                yield JsonFields.wrap("replace", body);
            }
            case OutputNode.Decode value -> {
                final ObjectNode body = selectAndName(value.select(), value.name());
                body.put("codec", JsonFields.label(value.codec()));
                yield JsonFields.wrap("decode", body);
            }
            case OutputNode.LowerCase value -> JsonFields.wrap("lower-case", selectAndName(value.select(),
                    value.name()));
            case OutputNode.UpperCase value -> JsonFields.wrap("upper-case", selectAndName(value.select(),
                    value.name()));
            case OutputNode.NormalizeSpace value ->
                    JsonFields.wrap("normalize-space", selectAndName(value.select(), value.name()));
            case OutputNode.Trim value -> JsonFields.wrap("trim", selectAndName(value.select(), value.name()));
            case OutputNode.Substring value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("select", JsonFields.array(value.select(), ReferenceJson::writeRef));
                if (value.start() != null) {
                    body.put("start", value.start());
                }
                if (value.length() != null) {
                    body.put("length", value.length());
                }
                JsonFields.putIfPresent(body, "name", value.name());
                yield JsonFields.wrap("substring", body);
            }
            case OutputNode.Tokenize value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("select", JsonFields.array(value.select(), ReferenceJson::writeRef));
                body.put("delimiter", value.delimiter());
                JsonFields.putIfPresent(body, "name", value.name());
                yield JsonFields.wrap("tokenize", body);
            }
            case OutputNode.Number value -> JsonFields.wrap("number", selectAndName(value.select(), value.name()));
            case OutputNode.Add value -> JsonFields.wrap("add", selectAndName(value.select(), value.name()));
            case OutputNode.Subtract value ->
                    JsonFields.wrap("subtract", selectAndName(value.select(), value.name()));
            case OutputNode.Multiply value ->
                    JsonFields.wrap("multiply", selectAndName(value.select(), value.name()));
            case OutputNode.Divide value -> JsonFields.wrap("divide", selectAndName(value.select(), value.name()));
            case OutputNode.Mod value -> JsonFields.wrap("mod", selectAndName(value.select(), value.name()));
            case OutputNode.Round value -> JsonFields.wrap("round", selectAndName(value.select(), value.name()));
            case OutputNode.Floor value -> JsonFields.wrap("floor", selectAndName(value.select(), value.name()));
            case OutputNode.Ceiling value ->
                    JsonFields.wrap("ceiling", selectAndName(value.select(), value.name()));
            case OutputNode.Abs value -> JsonFields.wrap("abs", selectAndName(value.select(), value.name()));
            case OutputNode.StringLength value ->
                    JsonFields.wrap("string-length", selectAndName(value.select(), value.name()));
            case OutputNode.SubstringBefore value ->
                    JsonFields.wrap("substring-before", selectAndMarker(value.select(), "marker",
                            value.marker(), value.name()));
            case OutputNode.SubstringAfter value ->
                    JsonFields.wrap("substring-after", selectAndMarker(value.select(), "marker",
                            value.marker(), value.name()));
            case OutputNode.StartsWith value ->
                    JsonFields.wrap("starts-with", selectAndMarker(value.select(), "prefix",
                            value.prefix(), value.name()));
            case OutputNode.EndsWith value ->
                    JsonFields.wrap("ends-with", selectAndMarker(value.select(), "suffix",
                            value.suffix(), value.name()));
            case OutputNode.Contains value ->
                    JsonFields.wrap("contains", selectAndMarker(value.select(), "substring",
                            value.substring(), value.name()));
            case OutputNode.FormatNumber value ->
                    JsonFields.wrap("format-number", selectAndMarker(value.select(), "picture",
                            value.picture(), value.name()));
            case OutputNode.Append value -> {
                final ObjectNode body = target(value.target());
                body.set("select", ReferenceJson.writeRef(value.select()));
                yield JsonFields.wrap("append", body);
            }
            case OutputNode.Insert value -> {
                final ObjectNode body = target(value.target());
                body.set("position", ReferenceJson.writeRefOrText(value.position()));
                body.set("select", ReferenceJson.writeRef(value.select()));
                yield JsonFields.wrap("insert", body);
            }
            case OutputNode.Put value -> {
                final ObjectNode body = target(value.target());
                if (value.key() != null) {
                    body.set("key", ReferenceJson.writeRefOrText(value.key()));
                }
                body.set("select", ReferenceJson.writeRef(value.select()));
                yield JsonFields.wrap("put", body);
            }
            case OutputNode.Remove value -> {
                final ObjectNode body = target(value.target());
                body.set("key", ReferenceJson.writeRefOrText(value.key()));
                yield JsonFields.wrap("remove", body);
            }
            case OutputNode.Clear value -> JsonFields.wrap("clear", target(value.target()));
            case OutputNode.ForEachGroup value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("select", ReferenceJson.writeRefOrName(value.select()));
                if (value.groupBy() != null) {
                    body.set("group_by", ReferenceJson.writeRef(value.groupBy()));
                }
                body.set("body", JsonFields.array(value.body(), OutputJson::writeOutput));
                yield JsonFields.wrap("for-each-group", body);
            }
            case OutputNode.ForEach value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("select", ReferenceJson.writeRefOrName(value.select()));
                JsonFields.putIfPresent(body, "as", value.as());
                JsonFields.putIfPresent(body, "as_key", value.asKey());
                if (!value.sort().isEmpty()) {
                    body.set("sort", JsonFields.array(value.sort(), OutputJson::writeSort));
                }
                body.set("body", JsonFields.array(value.body(), OutputJson::writeOutput));
                yield JsonFields.wrap("for-each", body);
            }
            case OutputNode.ParseDate value -> {
                final ObjectNode body = selectAndMarker(value.select(), "pattern",
                        value.pattern(), value.name());
                JsonFields.putIfPresent(body, "timezone", value.timezone());
                if (value.reference() != null) {
                    body.set("reference", ReferenceJson.writeRef(value.reference()));
                }
                yield JsonFields.wrap("parse-date", body);
            }
            case OutputNode.FormatDate value -> {
                final ObjectNode body = selectAndMarker(value.select(), "pattern",
                        value.pattern(), value.name());
                JsonFields.putIfPresent(body, "timezone", value.timezone());
                yield JsonFields.wrap("format-date", body);
            }
        };
    }

    private static OutputNode.Sort readSort(final JsonNode node) {
        JsonFields.checkFields(node, "sort", "by", "order", "as");
        final String spelling = JsonFields.optionalText(node, "order");
        final OutputNode.Order order = spelling == null
                ? OutputNode.Order.ASCENDING
                : JsonFields.lowercase(OutputNode.Order.class, spelling, "sort order");
        return new OutputNode.Sort(ReferenceJson.readRef(JsonFields.required(node, "by", "sort")), order,
                JsonFields.readCast(node));
    }

    private static ObjectNode writeSort(final OutputNode.Sort sort) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.set("by", ReferenceJson.writeRef(sort.by()));
        if (sort.order() != OutputNode.Order.ASCENDING) {
            node.put("order", JsonFields.label(sort.order()));
        }
        JsonFields.writeCast(node, sort.as());
        return node;
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
        final ObjectNode body = JsonFields.NODES.objectNode();
        body.set("select", JsonFields.array(select, ReferenceJson::writeRef));
        JsonFields.putIfPresent(body, "name", name);
        return body;
    }

    private static WhenBranch readWhen(final JsonNode node) {
        JsonFields.checkFields(node, "when", "test", "body");
        return new WhenBranch(
                ConditionJson.readCondition(JsonFields.required(node, "test", "when")),
                JsonFields.list(node.get("body"), "body", OutputJson::readOutput));
    }

    private static ObjectNode writeWhen(final WhenBranch branch) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.set("test", ConditionJson.writeCondition(branch.test()));
        node.set("body", JsonFields.array(branch.body(), OutputJson::writeOutput));
        return node;
    }

    private static SwitchCase readCase(final JsonNode node) {
        JsonFields.checkFields(node, "case", "value", "body");
        return new SwitchCase(
                JsonFields.text(node, "value", "case"), JsonFields.list(node.get("body"), "body",
                        OutputJson::readOutput));
    }

    private static ObjectNode writeCase(final SwitchCase switchCase) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("value", switchCase.value());
        node.set("body", JsonFields.array(switchCase.body(), OutputJson::writeOutput));
        return node;
    }

    /**
     * A mutation's target: {@code "name"} is the sugar for a declared name, {@code "target"} a
     * reference reaching a nested collection. One or the other.
     */
    private static RefExpression target(final JsonNode body, final String what) {
        final String name = JsonFields.optionalText(body, "name");
        final JsonNode target = JsonFields.optional(body, "target");
        if ((name == null) == (target == null)) {
            throw new ConfigException("A " + what + " names its collection with name or target, one of them");
        }
        return name != null ? ReferenceJson.nameRef(name) : ReferenceJson.readRef(target);
    }

    private static ObjectNode target(final RefExpression target) {
        final ObjectNode body = JsonFields.NODES.objectNode();
        final String name = target.bareName();
        if (name != null) {
            body.put("name", name);
        } else {
            body.set("target", ReferenceJson.writeRef(target));
        }
        return body;
    }

    /** A parameter is a two-element array: the wire format spells a tuple as a {@code [name, value]} pair. */
    private static Param readParam(final JsonNode node) {
        if (!node.isArray() || node.size() != 2) {
            throw new ConfigException("A parameter must be a [name, value] pair");
        }
        return new Param(JsonFields.text(node.get(0), "parameter name"),
                ReferenceJson.readRef(node.get(1)));
    }

    private static ArrayNode writeParam(final Param param) {
        final ArrayNode node = JsonFields.NODES.arrayNode();
        node.add(param.name());
        node.add(ReferenceJson.writeRef(param.value()));
        return node;
    }

    private static ApplyDirective readApply(final JsonNode node) {
        JsonFields.checkFields(node, "apply-templates", "select", "mode", "with-param", "max_depth",
                "ignore_errors", "dispatch");
        return new ApplyDirective(
                ReferenceJson.readRef(JsonFields.required(node, "select", "apply-templates")),
                JsonFields.optionalText(node, "mode"),
                JsonFields.list(node.get("with-param"), "with-param", OutputJson::readParam),
                JsonFields.integer(node, "max_depth", "apply-templates",
                        ApplyDirective.DEFAULT_MAX_DEPTH),
                node.path("ignore_errors").asBoolean(false),
                JsonFields.readDispatch(node));
    }

    private static ObjectNode writeApply(final ApplyDirective directive) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.set("select", ReferenceJson.writeRef(directive.select()));
        JsonFields.putIfPresent(node, "mode", directive.mode());
        if (!directive.withParam().isEmpty()) {
            node.set("with-param", JsonFields.array(directive.withParam(), OutputJson::writeParam));
        }
        node.put("max_depth", directive.maxDepth());
        if (directive.ignoreErrors()) {
            node.put("ignore_errors", true);
        }
        JsonFields.writeDispatch(node, directive.dispatch());
        return node;
    }
}
