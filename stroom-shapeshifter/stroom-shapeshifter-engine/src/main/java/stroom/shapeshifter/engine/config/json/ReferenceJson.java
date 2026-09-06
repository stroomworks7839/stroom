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

import stroom.shapeshifter.engine.config.CaptureBinding;
import stroom.shapeshifter.engine.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reference family of the wire format: captures, capture sources, reference expressions,
 * their parts and match indexes — read and written together.
 */
final class ReferenceJson {

    private ReferenceJson() {
    }

    static CaptureBinding readCapture(final JsonNode node) {
        JsonFields.checkFields(node, "capture", "name", "select");
        return new CaptureBinding(
                JsonFields.text(node, "name", "capture"), readCaptureSource(JsonFields.required(node, "select",
                        "capture")));
    }

    static ObjectNode writeCapture(final CaptureBinding capture) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("name", capture.name());
        node.set("select", writeCaptureSource(capture.select()));
        return node;
    }

    private static CaptureSource readCaptureSource(final JsonNode node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "capture source");
        final JsonNode body = tagged.body();
        return switch (tagged.name()) {
            case "group" -> new CaptureSource.Group(JsonFields.integer(body, "group"));
            case "step" -> new CaptureSource.Step(JsonFields.integer(body, "step"));
            case "field" -> new CaptureSource.Field(JsonFields.text(body, "field"));
            case "select" -> new CaptureSource.Select(readRef(body));
            case "key-value" -> {
                JsonFields.checkFields(body, "key-value", "key_ref", "value_ref");
                yield new CaptureSource.KeyValue(
                        readRef(JsonFields.required(body, "key_ref", "key-value")),
                        readRef(JsonFields.required(body, "value_ref", "key-value")));
            }
            default -> throw new ConfigException("Unknown capture source: " + tagged.name());
        };
    }

    private static JsonNode writeCaptureSource(final CaptureSource source) {
        return switch (source) {
            case CaptureSource.Group group -> JsonFields.wrap("group", JsonFields.NODES.numberNode(group.group()));
            case CaptureSource.Step step -> JsonFields.wrap("step", JsonFields.NODES.numberNode(step.index()));
            case CaptureSource.Field field -> JsonFields.wrap("field", JsonFields.NODES.stringNode(field.name()));
            case CaptureSource.Select select -> JsonFields.wrap("select", writeRef(select.select()));
            case CaptureSource.KeyValue keyValue -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.set("key_ref", writeRef(keyValue.keyRef()));
                body.set("value_ref", writeRef(keyValue.valueRef()));
                yield JsonFields.wrap("key-value", body);
            }
        };
    }

    static RefExpression readRef(final JsonNode node) {
        JsonFields.checkFields(node, "reference", "parts");
        return new RefExpression(JsonFields.list(node.get("parts"), "parts", ReferenceJson::readRefPart));
    }

    static ObjectNode writeRef(final RefExpression ref) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.set("parts", JsonFields.array(ref.parts(), ReferenceJson::writeRefPart));
        return node;
    }

    /** A reference field, or null where it is absent or JSON null. */
    static RefExpression optionalRef(final JsonNode node, final String field) {
        final JsonNode value = JsonFields.optional(node, field);
        return value == null ? null : readRef(value);
    }

    private static RefPart readRefPart(final JsonNode node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "reference part");
        final JsonNode body = tagged.body();
        return switch (tagged.name()) {
            // "Store" is the corpus's older spelling of "capture": read for ever, never written,
            // because a thousand of the fixtures' parts still use it and they are the measure.
            case "capture", "Store" -> {
                JsonFields.checkFields(body, "capture", "var_id", "group", "match_index");
                final JsonNode matchIndex = JsonFields.optional(body, "match_index");
                yield new RefPart.Capture(
                        JsonFields.optionalText(body, "var_id"),
                        JsonFields.integer(body, "group", "capture", 0),
                        matchIndex == null ? null : readMatchIndex(matchIndex));
            }
            case "text" -> new RefPart.Text(JsonFields.text(body, "text"));
            default -> throw new ConfigException("Unknown reference part: " + tagged.name());
        };
    }

    private static JsonNode writeRefPart(final RefPart part) {
        return switch (part) {
            case RefPart.Capture capture -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                JsonFields.putIfPresent(body, "var_id", capture.varId());
                body.put("group", capture.group());
                if (capture.matchIndex() != null) {
                    body.set("match_index", writeMatchIndex(capture.matchIndex()));
                }
                yield JsonFields.wrap("capture", body);
            }
            case RefPart.Text value -> JsonFields.wrap("text", JsonFields.NODES.stringNode(value.value()));
        };
    }

    private static MatchIndex readMatchIndex(final JsonNode node) {
        JsonFields.checkFields(node, "match index", "index", "is_offset", "is_last", "var_ref");
        return new MatchIndex(
                JsonFields.integer(node, "index", "match index", 0),
                node.path("is_offset").asBoolean(false),
                node.path("is_last").asBoolean(false),
                JsonFields.optionalText(node, "var_ref"));
    }

    private static ObjectNode writeMatchIndex(final MatchIndex index) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("index", index.index());
        node.put("is_offset", index.isOffset());
        node.put("is_last", index.isLast());
        JsonFields.putIfPresent(node, "var_ref", index.varRef());
        return node;
    }
}
