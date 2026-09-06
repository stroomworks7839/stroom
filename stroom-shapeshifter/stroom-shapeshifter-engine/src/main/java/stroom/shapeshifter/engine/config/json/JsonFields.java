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

import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Dispatch;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * The wire format's rules, and the primitives every family reads and writes with.
 *
 * <p>A sum type is a single-key object whose key is the variant, {@code {"regex": {…}}},
 * except when the variant carries nothing, in which case it collapses to a bare string,
 * {@code "source"}. Variant names are kebab-case in the types designed for people to write and
 * PascalCase in the step and codec vocabulary, and fields inside a variant are snake_case or
 * kebab-case as each type spells them; the spellings come from the format's first
 * implementation and are kept because the fixture corpus is written in them. Absent means
 * default, and default is not always the type's zero.
 *
 * <p>All of that is written out explicitly, rather than expressed as annotations spread across
 * twenty model classes. Two reasons. The model stays a plain tree of records that a different
 * serialiser — or none — could carry. And the format's rules end up in one place a person can
 * read top to bottom when a document does not load, instead of being inferred from the
 * interaction of defaults.
 *
 * <p>Reading is strict. An unrecognised variant or an unrecognised field is a
 * {@link ConfigException}, not a silent default — a configuration that half-loads is worse than
 * one that does not load, because it runs.
 */
public final class JsonFields {

    /** The one node factory every writer builds with. */
    static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JsonFields() {
    }

    static Dispatch readDispatch(final JsonNode node) {
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

    static void writeDispatch(final ObjectNode node, final Dispatch dispatch) {
        if (dispatch != null) {
            node.put("dispatch", dispatch.name().toLowerCase(Locale.ROOT));
        }
    }

    static Cast readCast(final JsonNode body) {
        if (!body.has("as") || body.get("as").isNull()) {
            return null;
        }
        final String label = body.get("as").asString();
        try {
            return Cast.valueOf(label.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new ConfigException("Unknown cast: " + label);
        }
    }

    static void writeCast(final ObjectNode body, final Cast as) {
        if (as != null) {
            body.put("as", as.name().toLowerCase(Locale.ROOT));
        }
    }

    record Tagged(String name, JsonNode body) {

    }

    static Tagged tag(final JsonNode node, final String what) {
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

    static ObjectNode wrap(final String name, final JsonNode body) {
        final ObjectNode node = NODES.objectNode();
        node.set(name, body);
        return node;
    }

    static void checkFields(final JsonNode node, final String what, final String... known) {
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

    static void expectObject(final JsonNode node, final String what) {
        if (node == null || !node.isObject()) {
            throw new ConfigException("Expected an object for " + what);
        }
    }

    static JsonNode required(final JsonNode node, final String field, final String what) {
        final JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            throw new ConfigException("Missing '" + field + "' in " + what);
        }
        return value;
    }

    static String text(final JsonNode node, final String field, final String what) {
        return required(node, field, what).asString();
    }

    static String optionalText(final JsonNode node, final String field) {
        final JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    static UUID uuid(final JsonNode node, final String what) {
        try {
            return UUID.fromString(node.asString());
        } catch (final IllegalArgumentException e) {
            throw new ConfigException("Not a valid id for " + what + ": " + node.asString());
        }
    }

    static UUID uuid(final JsonNode node, final String field, final String what) {
        try {
            return UUID.fromString(text(node, field, what));
        } catch (final IllegalArgumentException e) {
            throw new ConfigException("Not a valid id: " + optionalText(node, field), e);
        }
    }

    static void putIfPresent(final ObjectNode node, final String field, final String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    static <T> List<T> list(final JsonNode node, final String what,
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

    static <T> ArrayNode array(final List<T> values, final Function<T, ? extends JsonNode> writer) {
        final ArrayNode node = NODES.arrayNode(values.size());
        values.forEach(value -> node.add(writer.apply(value)));
        return node;
    }

    static <E extends Enum<E>> E constant(final Class<E> type, final String name) {
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

    static String name(final Enum<?> value) {
        final StringBuilder pascal = new StringBuilder();
        for (final String word : value.name().split("_")) {
            pascal.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return pascal.toString();
    }

}
