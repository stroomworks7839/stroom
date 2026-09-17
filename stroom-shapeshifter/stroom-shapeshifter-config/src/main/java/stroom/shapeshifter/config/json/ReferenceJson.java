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

import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.EngineVars;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.RefExpression.MatchIndex;
import stroom.shapeshifter.config.RefExpression.RefPart;

import java.util.List;

/**
 * The reference family of the wire format: captures, capture sources, reference expressions,
 * their parts and match indexes — read and written together.
 */
final class ReferenceJson {

    private ReferenceJson() {
    }

    static CaptureBinding readCapture(final JsonValue node) {
        JsonFields.checkFields(node, "capture", "name", "select", "as");
        return new CaptureBinding(
                JsonFields.text(node, "name", "capture"),
                readCaptureSource(JsonFields.required(node, "select", "capture")),
                JsonFields.readCast(node));
    }

    static JsonObject writeCapture(final CaptureBinding capture) {
        final JsonObject node = new JsonObject();
        node.put("name", capture.name());
        node.put("select", writeCaptureSource(capture.select()));
        JsonFields.writeCast(node, capture.as());
        return node;
    }

    private static CaptureSource readCaptureSource(final JsonValue node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "capture source");
        final JsonValue body = tagged.body();
        return switch (tagged.name()) {
            case "group" -> new CaptureSource.Group(JsonFields.integer(body, "group"));
            case "label" -> new CaptureSource.Label(JsonFields.text(body, "label"));
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

    private static JsonValue writeCaptureSource(final CaptureSource source) {
        if (source instanceof CaptureSource.Group group) {
            return JsonFields.wrap("group", JsonNumber.of(group.group()));
        } else if (source instanceof CaptureSource.Label label) {
            return JsonFields.wrap("label", new JsonString(label.label()));
        } else if (source instanceof CaptureSource.Select select) {
            return JsonFields.wrap("select", writeRef(select.select()));
        } else if (source instanceof CaptureSource.KeyValue keyValue) {
            final JsonObject body = new JsonObject();
            body.put("key_ref", writeRef(keyValue.keyRef()));
            body.put("value_ref", writeRef(keyValue.valueRef()));
            return JsonFields.wrap("key-value", body);
        } else {
            throw new IllegalStateException("Unknown variant: " + source);
        }

    }

    static RefExpression readRef(final JsonValue node) {
        JsonFields.checkFields(node, "reference", "parts");
        return new RefExpression(JsonFields.list(node.get("parts"), "parts", ReferenceJson::readRefPart));
    }

    static JsonObject writeRef(final RefExpression ref) {
        final JsonObject node = new JsonObject();
        node.put("parts", JsonFields.array(ref.parts(), ReferenceJson::writeRefPart));
        return node;
    }

    /** A reference to a declared name: the sugar every collection site accepts. */
    static RefExpression nameRef(final String name) {
        return new RefExpression(List.of(new RefPart.Capture(name, 0, null)));
    }


    /**
     * A collection: a name as a string, a function's spelling as a string — {@code "group()"} —
     * or a reference reaching one.
     */
    static RefExpression readRefOrName(final JsonValue node) {
        if (!node.isString()) {
            return readRef(node);
        }
        final String text = node.asString();
        if (text.endsWith("()")) {
            final EngineVars function = EngineVars.byName(text.substring(0, text.length() - 2));
            if (function != null) {
                return new RefExpression(List.of(new RefPart.Counter(function, null)));
            }
        }
        return nameRef(text);
    }

    static JsonValue writeRefOrName(final RefExpression ref) {
        final String name = ref.bareName();
        if (name != null) {
            return new JsonString(name);
        }
        if (ref.parts().size() == 1 && ref.parts().get(0) instanceof RefPart.Counter counter
            && counter.matchIndex() == null) {
            return new JsonString(counter.counter().spelling());
        }
        return writeRef(ref);
    }

    /** A key, position, value or default: a literal as a string or a number, or a reference. */
    static RefExpression readRefOrText(final JsonValue node) {
        if (node.isString()) {
            return RefExpression.text(node.asString());
        }
        if (node.isNumber()) {
            return RefExpression.text(node.asString());
        }
        return readRef(node);
    }

    static JsonValue writeRefOrText(final RefExpression ref) {
        return ref.isText()
                ? new JsonString(((RefPart.Text) ref.parts().get(0)).value())
                : writeRef(ref);
    }

    /** A reference field, or null where it is absent or JSON null. */
    static RefExpression optionalRef(final JsonValue node, final String field) {
        final JsonValue value = JsonFields.optional(node, field);
        return value == null ? null : readRef(value);
    }

    private static RefPart readRefPart(final JsonValue node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "reference part");
        final JsonValue body = tagged.body();
        return switch (tagged.name()) {
            // "Store" is the corpus's older spelling of "capture": read for ever, never written,
            // because a thousand of the fixtures' parts still use it and they are the measure.
            case "capture", "Store" -> {
                JsonFields.checkFields(body, "capture", "var_id", "group", "match_index", "label");
                final JsonValue matchIndex = JsonFields.optional(body, "match_index");
                final String label = JsonFields.optionalText(body, "label");
                if (label != null && (body.has("var_id") || body.has("group") || matchIndex != null)) {
                    throw new ConfigException("A capture reference by label names nothing else: no var_id, group "
                                              + "or match_index beside label '" + label + "'");
                }
                yield new RefPart.Capture(
                        JsonFields.optionalText(body, "var_id"),
                        JsonFields.integer(body, "group", "capture", 0),
                        matchIndex == null ? null : readMatchIndex(matchIndex),
                        label);
            }
            case "text" -> new RefPart.Text(JsonFields.text(body, "text"));
            case "get", "size", "contains", "last", "head", "keys", "values", "sum", "avg", "min", "max" -> {
                JsonFields.checkFields(body, tagged.name(), "of", "key", "value", "default", "as");
                final RefPart.Accessor.Kind kind = JsonFields.lowercase(RefPart.Accessor.Kind.class, tagged.name(),
                        "accessor");
                // get takes its position or key as "key"; contains takes what it looks for as "value"
                final JsonValue key = JsonFields.optional(body, kind == RefPart.Accessor.Kind.CONTAINS ? "value"
                        : "key");
                final JsonValue orElse = JsonFields.optional(body, "default");
                yield new RefPart.Accessor(kind,
                        readRefOrName(JsonFields.required(body, "of", tagged.name())),
                        key == null ? null : readRefOrText(key),
                        orElse == null ? null : readRefOrText(orElse),
                        JsonFields.readCast(body));
            }
            case "function" -> {
                JsonFields.checkFields(body, "function", "name", "match_index");
                final JsonValue matchIndex = JsonFields.optional(body, "match_index");
                yield new RefPart.Counter(
                        function(JsonFields.text(body, "name", "function")),
                        matchIndex == null ? null : readMatchIndex(matchIndex));
            }
            default -> throw new ConfigException("Unknown reference part: " + tagged.name());
        };
    }

    private static JsonValue writeRefPart(final RefPart part) {
        if (part instanceof RefPart.Capture capture) {
            final JsonObject body = new JsonObject();
            if (capture.label() != null) {
                body.put("label", capture.label());
                return JsonFields.wrap("capture", body);
            }
            JsonFields.putIfPresent(body, "var_id", capture.varId());
            body.put("group", capture.group());
            if (capture.matchIndex() != null) {
                body.put("match_index", writeMatchIndex(capture.matchIndex()));
            }
            return JsonFields.wrap("capture", body);
        } else if (part instanceof RefPart.Text value) {
            return JsonFields.wrap("text", new JsonString(value.value()));
        } else if (part instanceof RefPart.Accessor accessor) {
            final JsonObject body = new JsonObject();
            body.put("of", writeRefOrName(accessor.of()));
            if (accessor.key() != null) {
                body.put(accessor.kind() == RefPart.Accessor.Kind.CONTAINS ? "value" : "key",
                        writeRefOrText(accessor.key()));
            }
            if (accessor.orElse() != null) {
                body.put("default", writeRefOrText(accessor.orElse()));
            }
            JsonFields.writeCast(body, accessor.as());
            return JsonFields.wrap(accessor.kind().spelling(), body);
        } else if (part instanceof RefPart.Counter counter) {
            final JsonObject body = new JsonObject();
            body.put("name", counter.counter().functionName());
            if (counter.matchIndex() != null) {
                body.put("match_index", writeMatchIndex(counter.matchIndex()));
            }
            return JsonFields.wrap("function", body);
        } else {
            throw new IllegalStateException("Unknown variant: " + part);
        }

    }

    private static MatchIndex readMatchIndex(final JsonValue node) {
        JsonFields.checkFields(node, "match index", "index", "is_offset", "is_last", "var_ref", "function");
        final String function = JsonFields.optionalText(node, "function");
        return new MatchIndex(
                JsonFields.integer(node, "index", "match index", 0),
                JsonFields.flag(node, "is_offset"),
                JsonFields.flag(node, "is_last"),
                JsonFields.optionalText(node, "var_ref"),
                function == null ? null : function(function));
    }

    /** The engine function a name means, refused by name when there is none. */
    private static EngineVars function(final String name) {
        final EngineVars function = EngineVars.byName(name);
        if (function == null) {
            throw new ConfigException("Unknown function '" + name + "': the functions are "
                    + EngineVars.spellings());
        }
        return function;
    }

    private static JsonObject writeMatchIndex(final MatchIndex index) {
        final JsonObject node = new JsonObject();
        node.put("index", index.index());
        node.put("is_offset", index.isOffset());
        node.put("is_last", index.isLast());
        JsonFields.putIfPresent(node, "var_ref", index.varRef());
        if (index.counter() != null) {
            node.put("function", index.counter().functionName());
        }
        return node;
    }
}
