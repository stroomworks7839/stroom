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
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.OutputNode.Entry;
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
            case "text" -> new OutputNode.Text(body.asString());
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
            case "value-map" -> {
                JsonFields.checkFields(body, "value-map", "select", "entries", "default", "name");
                yield new OutputNode.ValueMap(
                        ReferenceJson.readRef(JsonFields.required(body, "select", "value-map")),
                        JsonFields.list(body.get("entries"), "entries", OutputJson::readEntry),
                        JsonFields.optionalText(body, "default"),
                        JsonFields.optionalText(body, "name"));
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
                        start == null ? null : start.asInt(),
                        length == null ? null : length.asInt(),
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
            case "count" -> {
                JsonFields.checkFields(body, "count", "select", "name");
                yield new OutputNode.Count(JsonFields.text(body, "select", "count"),
                        JsonFields.optionalText(body, "name"));
            }
            case "sum" -> {
                JsonFields.checkFields(body, "sum", "select", "name");
                yield new OutputNode.Sum(JsonFields.text(body, "select", "sum"), JsonFields.optionalText(body, "name"));
            }
            case "avg" -> {
                JsonFields.checkFields(body, "avg", "select", "name");
                yield new OutputNode.Avg(JsonFields.text(body, "select", "avg"), JsonFields.optionalText(body, "name"));
            }
            case "min" -> {
                JsonFields.checkFields(body, "min", "select", "as", "name");
                yield new OutputNode.Min(JsonFields.text(body, "select", "min"),
                        JsonFields.readCast(body), JsonFields.optionalText(body, "name"));
            }
            case "max" -> {
                JsonFields.checkFields(body, "max", "select", "as", "name");
                yield new OutputNode.Max(JsonFields.text(body, "select", "max"),
                        JsonFields.readCast(body), JsonFields.optionalText(body, "name"));
            }
            case "distinct-values" -> {
                JsonFields.checkFields(body, "distinct-values", "select", "name");
                yield new OutputNode.DistinctValues(
                        JsonFields.text(body, "select", "distinct-values"), JsonFields.text(body, "name",
                        "distinct-values"));
            }
            case "sequence" -> {
                JsonFields.checkFields(body, "sequence", "name");
                yield new OutputNode.Sequence(JsonFields.text(body, "name", "sequence"));
            }
            case "append" -> {
                JsonFields.checkFields(body, "append", "name", "select");
                yield new OutputNode.Append(
                        JsonFields.text(body, "name", "append"),
                        ReferenceJson.readRef(JsonFields.required(body, "select", "append")));
            }
            case "key" -> {
                JsonFields.checkFields(body, "key", "name", "select", "group_by");
                yield new OutputNode.Key(
                        JsonFields.text(body, "name", "key"),
                        JsonFields.text(body, "select", "key"),
                        ReferenceJson.optionalRef(body, "group_by"));
            }
            case "key-get" -> {
                JsonFields.checkFields(body, "key-get", "key", "select", "name");
                yield new OutputNode.KeyGet(
                        JsonFields.text(body, "key", "key-get"),
                        ReferenceJson.readRef(JsonFields.required(body, "select", "key-get")),
                        JsonFields.text(body, "name", "key-get"));
            }
            case "for-each-group" -> {
                JsonFields.checkFields(body, "for-each-group", "select", "group_by", "body");
                yield new OutputNode.ForEachGroup(
                        JsonFields.text(body, "select", "for-each-group"),
                        ReferenceJson.optionalRef(body, "group_by"),
                        JsonFields.list(body.get("body"), "body", OutputJson::readOutput));
            }
            case "for-each" -> {
                JsonFields.checkFields(body, "for-each", "select", "as", "sort", "body");
                yield new OutputNode.ForEach(
                        JsonFields.text(body, "select", "for-each"),
                        JsonFields.optionalText(body, "as"),
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
            case OutputNode.ValueMap value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("select", ReferenceJson.writeRef(value.select()));
                body.set("entries", JsonFields.array(value.entries(), OutputJson::writeEntry));
                JsonFields.putIfPresent(body, "default", value.defaultValue());
                JsonFields.putIfPresent(body, "name", value.name());
                yield JsonFields.wrap("value-map", body);
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
            case OutputNode.Count value -> JsonFields.wrap("count", sequenceAndName(value.select(), value.name()));
            case OutputNode.Sum value -> JsonFields.wrap("sum", sequenceAndName(value.select(), value.name()));
            case OutputNode.Avg value -> JsonFields.wrap("avg", sequenceAndName(value.select(), value.name()));
            case OutputNode.Min value -> {
                final ObjectNode body = sequenceAndName(value.select(), value.name());
                JsonFields.writeCast(body, value.as());
                yield JsonFields.wrap("min", body);
            }
            case OutputNode.Max value -> {
                final ObjectNode body = sequenceAndName(value.select(), value.name());
                JsonFields.writeCast(body, value.as());
                yield JsonFields.wrap("max", body);
            }
            case OutputNode.DistinctValues value ->
                    JsonFields.wrap("distinct-values", sequenceAndName(value.select(), value.name()));
            case OutputNode.Sequence value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("name", value.name());
                yield JsonFields.wrap("sequence", body);
            }
            case OutputNode.Append value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("name", value.name());
                body.set("select", ReferenceJson.writeRef(value.select()));
                yield JsonFields.wrap("append", body);
            }
            case OutputNode.Key value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("name", value.name());
                body.put("select", value.select());
                if (value.groupBy() != null) {
                    body.set("group_by", ReferenceJson.writeRef(value.groupBy()));
                }
                yield JsonFields.wrap("key", body);
            }
            case OutputNode.KeyGet value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("key", value.key());
                body.set("select", ReferenceJson.writeRef(value.select()));
                body.put("name", value.name());
                yield JsonFields.wrap("key-get", body);
            }
            case OutputNode.ForEachGroup value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("select", value.select());
                if (value.groupBy() != null) {
                    body.set("group_by", ReferenceJson.writeRef(value.groupBy()));
                }
                body.set("body", JsonFields.array(value.body(), OutputJson::writeOutput));
                yield JsonFields.wrap("for-each-group", body);
            }
            case OutputNode.ForEach value -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("select", value.select());
                JsonFields.putIfPresent(body, "as", value.as());
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

    /** A sequence instruction's body: the store it reads and, where it binds, its name. */
    private static ObjectNode sequenceAndName(final String select, final String name) {
        final ObjectNode body = JsonFields.NODES.objectNode();
        body.put("select", select);
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

    private static Entry readEntry(final JsonNode node) {
        JsonFields.checkFields(node, "entry", "from", "to");
        return new Entry(JsonFields.text(node, "from", "entry"), JsonFields.text(node, "to", "entry"));
    }

    private static ObjectNode writeEntry(final Entry entry) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("from", entry.from());
        node.put("to", entry.to());
        return node;
    }

    /** A parameter is a two-element array: the wire format spells a tuple as a {@code [name, value]} pair. */
    private static Param readParam(final JsonNode node) {
        if (!node.isArray() || node.size() != 2) {
            throw new ConfigException("A parameter must be a [name, value] pair");
        }
        return new Param(node.get(0).asString(), ReferenceJson.readRef(node.get(1)));
    }

    private static ArrayNode writeParam(final Param param) {
        final ArrayNode node = JsonFields.NODES.arrayNode();
        node.add(param.name());
        node.add(ReferenceJson.writeRef(param.value()));
        return node;
    }

    private static ApplyDirective readApply(final JsonNode node) {
        JsonFields.checkFields(node, "apply-templates", "select", "mode", "with-param", "max_depth", "template_ref",
                "ignore_errors", "dispatch");
        return new ApplyDirective(
                ReferenceJson.readRef(JsonFields.required(node, "select", "apply-templates")),
                JsonFields.optionalText(node, "mode"),
                JsonFields.list(node.get("with-param"), "with-param", OutputJson::readParam),
                node.path("max_depth").asInt(ApplyDirective.DEFAULT_MAX_DEPTH),
                JsonFields.optionalText(node, "template_ref"),
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
        JsonFields.putIfPresent(node, "template_ref", directive.templateRef());
        if (directive.ignoreErrors()) {
            node.put("ignore_errors", true);
        }
        JsonFields.writeDispatch(node, directive.dispatch());
        return node;
    }
}
