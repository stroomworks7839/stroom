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

import stroom.shapeshifter.config.Codec;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.config.OutputNode.Param;
import stroom.shapeshifter.config.OutputNode.SwitchCase;
import stroom.shapeshifter.config.OutputNode.WhenBranch;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.Severity;

import java.util.List;

/**
 * The output family of the wire format: every output instruction, sorts, branches, cases,
 * entries, {@code with-param} parameters and apply directives — read and written together.
 */
final class OutputJson {

    private OutputJson() {
    }

    static OutputNode readOutput(final JsonValue node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "output node");
        final JsonValue body = tagged.body();
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
                        JsonFields.flag(body, "omit-if-empty"),
                        JsonFields.list(body.get("body"), "body", OutputJson::readOutput));
            }
            case "attribute" -> {
                JsonFields.checkFields(body, "attribute", "name", "omit-if-empty", "body");
                yield new OutputNode.Attribute(
                        JsonFields.text(body, "name", "attribute"),
                        JsonFields.flag(body, "omit-if-empty"),
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
                        JsonFields.list(body.get("from"), "from", JsonValue::asString),
                        JsonFields.list(body.get("to"), "to", JsonValue::asString),
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
                        JsonFields.flag(body, "is_regex"),
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
                final JsonValue start = JsonFields.optional(body, "start");
                final JsonValue length = JsonFields.optional(body, "length");
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
                final JsonValue key = JsonFields.optional(body, "key");
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
    private static List<RefExpression> selectList(final JsonValue body, final String owner) {
        JsonFields.checkFields(body, owner, "select", "name");
        return JsonFields.list(body.get("select"), "select", ReferenceJson::readRef);
    }

    static JsonValue writeOutput(final OutputNode output) {
        if (output instanceof OutputNode.Text value) {
            return JsonFields.wrap("text", new JsonString(value.value()));
        } else if (output instanceof OutputNode.ValueOf value) {
            return JsonFields.wrap("value-of", ReferenceJson.writeRef(value.select()));
        } else if (output instanceof OutputNode.Call value) {
            final JsonObject body = new JsonObject();
            body.put("function", value.function());
            body.put("select", JsonFields.array(value.select(), ReferenceJson::writeRef));
            JsonFields.putIfPresent(body, "name", value.name());
            return JsonFields.wrap("call", body);
        } else if (output instanceof OutputNode.If value) {
            final JsonObject body = new JsonObject();
            body.put("test", ConditionJson.writeCondition(value.test()));
            body.put("then", JsonFields.array(value.then(), OutputJson::writeOutput));
            return JsonFields.wrap("if", body);
        } else if (output instanceof OutputNode.Choose value) {
            final JsonObject body = new JsonObject();
            body.put("when", JsonFields.array(value.when(), OutputJson::writeWhen));
            if (!value.otherwise().isEmpty()) {
                body.put("otherwise", JsonFields.array(value.otherwise(), OutputJson::writeOutput));
            }
            return JsonFields.wrap("choose", body);
        } else if (output instanceof OutputNode.Switch value) {
            final JsonObject body = new JsonObject();
            body.put("select", ReferenceJson.writeRef(value.select()));
            body.put("cases", JsonFields.array(value.cases(), OutputJson::writeCase));
            if (!value.defaultBody().isEmpty()) {
                body.put("default", JsonFields.array(value.defaultBody(), OutputJson::writeOutput));
            }
            return JsonFields.wrap("switch", body);
        } else if (output instanceof OutputNode.ApplyTemplates value) {
            return JsonFields.wrap("apply-templates", writeApply(value.directive()));
        } else if (output instanceof OutputNode.EmitError value) {
            final JsonObject body = new JsonObject();
            body.put("severity", JsonFields.label(value.severity()));
            body.put("message", ReferenceJson.writeRef(value.message()));
            return JsonFields.wrap("emit-error", body);
        } else if (output instanceof OutputNode.CallTemplate value) {
            final JsonObject body = new JsonObject();
            body.put("name", value.name());
            if (!value.withParam().isEmpty()) {
                body.put("with-param", JsonFields.array(value.withParam(), OutputJson::writeParam));
            }
            return JsonFields.wrap("call-template", body);
        } else if (output instanceof OutputNode.Variable value) {
            final JsonObject body = new JsonObject();
            body.put("name", value.name());
            body.put("body", JsonFields.array(value.body(), OutputJson::writeOutput));
            return JsonFields.wrap("variable", body);
        } else if (output instanceof OutputNode.Element value) {
            final JsonObject body = new JsonObject();
            body.put("name", value.name());
            JsonFields.putIfPresent(body, "namespace", value.namespace());
            if (value.omitIfEmpty()) {
                body.put("omit-if-empty", true);
            }
            body.put("body", JsonFields.array(value.body(), OutputJson::writeOutput));
            return JsonFields.wrap("element", body);
        } else if (output instanceof OutputNode.Attribute value) {
            final JsonObject body = new JsonObject();
            body.put("name", value.name());
            if (value.omitIfEmpty()) {
                body.put("omit-if-empty", true);
            }
            body.put("body", JsonFields.array(value.body(), OutputJson::writeOutput));
            return JsonFields.wrap("attribute", body);
        } else if (output instanceof OutputNode.Namespace value) {
            final JsonObject body = new JsonObject();
            JsonFields.putIfPresent(body, "prefix", value.prefix());
            body.put("uri", value.uri());
            return JsonFields.wrap("namespace", body);
        } else if (output instanceof OutputNode.Translate value) {
            final JsonObject body = new JsonObject();
            body.put("select", JsonFields.array(value.select(), ReferenceJson::writeRef));
            final JsonArray from = body.putArray("from");
            value.from().forEach(from::add);
            final JsonArray to = body.putArray("to");
            value.to().forEach(to::add);
            JsonFields.putIfPresent(body, "name", value.name());
            return JsonFields.wrap("translate", body);
        } else if (output instanceof OutputNode.StringJoin value) {
            final JsonObject body = new JsonObject();
            body.put("select", JsonFields.array(value.select(), ReferenceJson::writeRef));
            JsonFields.putIfPresent(body, "separator", value.separator());
            JsonFields.putIfPresent(body, "name", value.name());
            return JsonFields.wrap("string-join", body);
        } else if (output instanceof OutputNode.Replace value) {
            final JsonObject body = new JsonObject();
            body.put("select", JsonFields.array(value.select(), ReferenceJson::writeRef));
            body.put("pattern", value.pattern());
            body.put("replacement", value.replacement());
            body.put("is_regex", value.isRegex());
            JsonFields.putIfPresent(body, "name", value.name());
            return JsonFields.wrap("replace", body);
        } else if (output instanceof OutputNode.Decode value) {
            final JsonObject body = selectAndName(value.select(), value.name());
            body.put("codec", JsonFields.label(value.codec()));
            return JsonFields.wrap("decode", body);
        } else if (output instanceof OutputNode.LowerCase value) {
            return JsonFields.wrap("lower-case", selectAndName(value.select(),
                value.name()));
        } else if (output instanceof OutputNode.UpperCase value) {
            return JsonFields.wrap("upper-case", selectAndName(value.select(),
                value.name()));
        } else if (output instanceof OutputNode.NormalizeSpace value) {
            return JsonFields.wrap("normalize-space", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.Trim value) {
            return JsonFields.wrap("trim", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.Substring value) {
            final JsonObject body = new JsonObject();
            body.put("select", JsonFields.array(value.select(), ReferenceJson::writeRef));
            if (value.start() != null) {
                body.put("start", value.start());
            }
            if (value.length() != null) {
                body.put("length", value.length());
            }
            JsonFields.putIfPresent(body, "name", value.name());
            return JsonFields.wrap("substring", body);
        } else if (output instanceof OutputNode.Tokenize value) {
            final JsonObject body = new JsonObject();
            body.put("select", JsonFields.array(value.select(), ReferenceJson::writeRef));
            body.put("delimiter", value.delimiter());
            JsonFields.putIfPresent(body, "name", value.name());
            return JsonFields.wrap("tokenize", body);
        } else if (output instanceof OutputNode.Number value) {
            return JsonFields.wrap("number", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.Add value) {
            return JsonFields.wrap("add", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.Subtract value) {
            return JsonFields.wrap("subtract", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.Multiply value) {
            return JsonFields.wrap("multiply", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.Divide value) {
            return JsonFields.wrap("divide", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.Mod value) {
            return JsonFields.wrap("mod", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.Round value) {
            return JsonFields.wrap("round", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.Floor value) {
            return JsonFields.wrap("floor", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.Ceiling value) {
            return JsonFields.wrap("ceiling", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.Abs value) {
            return JsonFields.wrap("abs", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.StringLength value) {
            return JsonFields.wrap("string-length", selectAndName(value.select(), value.name()));
        } else if (output instanceof OutputNode.SubstringBefore value) {
            return JsonFields.wrap("substring-before", selectAndMarker(value.select(), "marker",
                value.marker(), value.name()));
        } else if (output instanceof OutputNode.SubstringAfter value) {
            return JsonFields.wrap("substring-after", selectAndMarker(value.select(), "marker",
                value.marker(), value.name()));
        } else if (output instanceof OutputNode.StartsWith value) {
            return JsonFields.wrap("starts-with", selectAndMarker(value.select(), "prefix",
                value.prefix(), value.name()));
        } else if (output instanceof OutputNode.EndsWith value) {
            return JsonFields.wrap("ends-with", selectAndMarker(value.select(), "suffix",
                value.suffix(), value.name()));
        } else if (output instanceof OutputNode.Contains value) {
            return JsonFields.wrap("contains", selectAndMarker(value.select(), "substring",
                value.substring(), value.name()));
        } else if (output instanceof OutputNode.FormatNumber value) {
            return JsonFields.wrap("format-number", selectAndMarker(value.select(), "picture",
                value.picture(), value.name()));
        } else if (output instanceof OutputNode.Append value) {
            final JsonObject body = target(value.target());
            body.put("select", ReferenceJson.writeRef(value.select()));
            return JsonFields.wrap("append", body);
        } else if (output instanceof OutputNode.Insert value) {
            final JsonObject body = target(value.target());
            body.put("position", ReferenceJson.writeRefOrText(value.position()));
            body.put("select", ReferenceJson.writeRef(value.select()));
            return JsonFields.wrap("insert", body);
        } else if (output instanceof OutputNode.Put value) {
            final JsonObject body = target(value.target());
            if (value.key() != null) {
                body.put("key", ReferenceJson.writeRefOrText(value.key()));
            }
            body.put("select", ReferenceJson.writeRef(value.select()));
            return JsonFields.wrap("put", body);
        } else if (output instanceof OutputNode.Remove value) {
            final JsonObject body = target(value.target());
            body.put("key", ReferenceJson.writeRefOrText(value.key()));
            return JsonFields.wrap("remove", body);
        } else if (output instanceof OutputNode.Clear value) {
            return JsonFields.wrap("clear", target(value.target()));
        } else if (output instanceof OutputNode.ForEachGroup value) {
            final JsonObject body = new JsonObject();
            body.put("select", ReferenceJson.writeRefOrName(value.select()));
            if (value.groupBy() != null) {
                body.put("group_by", ReferenceJson.writeRef(value.groupBy()));
            }
            body.put("body", JsonFields.array(value.body(), OutputJson::writeOutput));
            return JsonFields.wrap("for-each-group", body);
        } else if (output instanceof OutputNode.ForEach value) {
            final JsonObject body = new JsonObject();
            body.put("select", ReferenceJson.writeRefOrName(value.select()));
            JsonFields.putIfPresent(body, "as", value.as());
            JsonFields.putIfPresent(body, "as_key", value.asKey());
            if (!value.sort().isEmpty()) {
                body.put("sort", JsonFields.array(value.sort(), OutputJson::writeSort));
            }
            body.put("body", JsonFields.array(value.body(), OutputJson::writeOutput));
            return JsonFields.wrap("for-each", body);
        } else if (output instanceof OutputNode.ParseDate value) {
            final JsonObject body = selectAndMarker(value.select(), "pattern",
                    value.pattern(), value.name());
            JsonFields.putIfPresent(body, "timezone", value.timezone());
            if (value.reference() != null) {
                body.put("reference", ReferenceJson.writeRef(value.reference()));
            }
            return JsonFields.wrap("parse-date", body);
        } else if (output instanceof OutputNode.FormatDate value) {
            final JsonObject body = selectAndMarker(value.select(), "pattern",
                    value.pattern(), value.name());
            JsonFields.putIfPresent(body, "timezone", value.timezone());
            return JsonFields.wrap("format-date", body);
        } else {
            throw new IllegalStateException("Unknown variant: " + output);
        }

    }

    private static OutputNode.Sort readSort(final JsonValue node) {
        JsonFields.checkFields(node, "sort", "by", "order", "as");
        final String spelling = JsonFields.optionalText(node, "order");
        final OutputNode.Order order = spelling == null
                ? OutputNode.Order.ASCENDING
                : JsonFields.lowercase(OutputNode.Order.class, spelling, "sort order");
        return new OutputNode.Sort(ReferenceJson.readRef(JsonFields.required(node, "by", "sort")), order,
                JsonFields.readCast(node));
    }

    private static JsonObject writeSort(final OutputNode.Sort sort) {
        final JsonObject node = new JsonObject();
        node.put("by", ReferenceJson.writeRef(sort.by()));
        if (sort.order() != OutputNode.Order.ASCENDING) {
            node.put("order", JsonFields.label(sort.order()));
        }
        JsonFields.writeCast(node, sort.as());
        return node;
    }

    /** A one-input transform with one string parameter beside its select. */
    private static JsonObject selectAndMarker(final List<RefExpression> select,
                                              final String field,
                                              final String parameter,
                                              final String name) {
        final JsonObject body = selectAndName(select, name);
        body.put(field, parameter);
        return body;
    }

    private static JsonObject selectAndName(final List<RefExpression> select, final String name) {
        final JsonObject body = new JsonObject();
        body.put("select", JsonFields.array(select, ReferenceJson::writeRef));
        JsonFields.putIfPresent(body, "name", name);
        return body;
    }

    private static WhenBranch readWhen(final JsonValue node) {
        JsonFields.checkFields(node, "when", "test", "body");
        return new WhenBranch(
                ConditionJson.readCondition(JsonFields.required(node, "test", "when")),
                JsonFields.list(node.get("body"), "body", OutputJson::readOutput));
    }

    private static JsonObject writeWhen(final WhenBranch branch) {
        final JsonObject node = new JsonObject();
        node.put("test", ConditionJson.writeCondition(branch.test()));
        node.put("body", JsonFields.array(branch.body(), OutputJson::writeOutput));
        return node;
    }

    private static SwitchCase readCase(final JsonValue node) {
        JsonFields.checkFields(node, "case", "value", "body");
        return new SwitchCase(
                JsonFields.text(node, "value", "case"), JsonFields.list(node.get("body"), "body",
                        OutputJson::readOutput));
    }

    private static JsonObject writeCase(final SwitchCase switchCase) {
        final JsonObject node = new JsonObject();
        node.put("value", switchCase.value());
        node.put("body", JsonFields.array(switchCase.body(), OutputJson::writeOutput));
        return node;
    }

    /**
     * A mutation's target: {@code "name"} is the sugar for a declared name, {@code "target"} a
     * reference reaching a nested collection. One or the other.
     */
    private static RefExpression target(final JsonValue body, final String what) {
        final String name = JsonFields.optionalText(body, "name");
        final JsonValue target = JsonFields.optional(body, "target");
        if ((name == null) == (target == null)) {
            throw new ConfigException("A " + what + " names its collection with name or target, one of them");
        }
        return name != null ? ReferenceJson.nameRef(name) : ReferenceJson.readRef(target);
    }

    private static JsonObject target(final RefExpression target) {
        final JsonObject body = new JsonObject();
        final String name = target.bareName();
        if (name != null) {
            body.put("name", name);
        } else {
            body.put("target", ReferenceJson.writeRef(target));
        }
        return body;
    }

    /** A parameter is a two-element array: the wire format spells a tuple as a {@code [name, value]} pair. */
    private static Param readParam(final JsonValue node) {
        if (!node.isArray() || node.size() != 2) {
            throw new ConfigException("A parameter must be a [name, value] pair");
        }
        final JsonArray pair = (JsonArray) node;
        return new Param(JsonFields.text(pair.get(0), "parameter name"),
                ReferenceJson.readRef(pair.get(1)));
    }

    private static JsonArray writeParam(final Param param) {
        final JsonArray node = new JsonArray();
        node.add(param.name());
        node.add(ReferenceJson.writeRef(param.value()));
        return node;
    }

    private static ApplyDirective readApply(final JsonValue node) {
        JsonFields.checkFields(node, "apply-templates", "select", "mode", "with-param", "max_depth",
                "ignore_errors", "dispatch");
        return new ApplyDirective(
                ReferenceJson.readRef(JsonFields.required(node, "select", "apply-templates")),
                JsonFields.optionalText(node, "mode"),
                JsonFields.list(node.get("with-param"), "with-param", OutputJson::readParam),
                JsonFields.integer(node, "max_depth", "apply-templates",
                        ApplyDirective.DEFAULT_MAX_DEPTH),
                JsonFields.flag(node, "ignore_errors"),
                JsonFields.readDispatch(node));
    }

    private static JsonObject writeApply(final ApplyDirective directive) {
        final JsonObject node = new JsonObject();
        node.put("select", ReferenceJson.writeRef(directive.select()));
        JsonFields.putIfPresent(node, "mode", directive.mode());
        if (!directive.withParam().isEmpty()) {
            node.put("with-param", JsonFields.array(directive.withParam(), OutputJson::writeParam));
        }
        node.put("max_depth", directive.maxDepth());
        if (directive.ignoreErrors()) {
            node.put("ignore_errors", true);
        }
        JsonFields.writeDispatch(node, directive.dispatch());
        return node;
    }
}
