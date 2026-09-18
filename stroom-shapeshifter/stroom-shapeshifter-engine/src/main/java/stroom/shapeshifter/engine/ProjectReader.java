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

package stroom.shapeshifter.engine;

import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.json.JsonArray;
import stroom.shapeshifter.config.json.JsonBoolean;
import stroom.shapeshifter.config.json.JsonNull;
import stroom.shapeshifter.config.json.JsonNumber;
import stroom.shapeshifter.config.json.JsonObject;
import stroom.shapeshifter.config.json.JsonString;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.JsonValue;
import stroom.shapeshifter.config.json.ProjectJson;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Turns configuration documents into {@link Project}s, and back.
 *
 * <p>Everything that reads a configuration goes through here, so that what a configuration is
 * stored as stays one decision in one place. Today that is JSON text (D33). The mapping is
 * the config module's, written against its own {@link JsonValue} tree so that the GWT client
 * can share it (design 43 §2); this class parses with Jackson and adapts its tree, and prints
 * with the config module's {@link JsonText} so that a project printed here and one printed in
 * the client read the same.
 */
public final class ProjectReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProjectReader() {
    }

    /**
     * Read a configuration.
     *
     * @throws ConfigException if the document is not valid JSON, or is not a valid configuration
     */
    public static Project read(final String json) {
        try {
            return ProjectJson.readProject(toValue(MAPPER.readTree(json)));
        } catch (final JacksonException e) {
            throw new ConfigException("Configuration is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Read a configuration from UTF-8 bytes.
     *
     * @throws ConfigException if the document is not valid JSON, or is not a valid configuration
     */
    public static Project read(final byte[] json) {
        return read(new String(json, StandardCharsets.UTF_8));
    }

    /** Write a configuration as compact JSON. */
    public static String write(final Project project) {
        return JsonText.print(ProjectJson.writeProject(project));
    }

    /** Write a configuration as indented JSON, for a human or a diff. */
    public static String writePretty(final Project project) {
        return JsonText.printPretty(ProjectJson.writeProject(project));
    }

    /** Jackson's tree as the config module's. Numbers keep their literal text, so whole stays whole. */
    static JsonValue toValue(final JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return JsonNull.NULL;
        }
        if (node.isObject()) {
            final JsonObject object = new JsonObject();
            for (final Map.Entry<String, JsonNode> entry : node.properties()) {
                object.put(entry.getKey(), toValue(entry.getValue()));
            }
            return object;
        }
        if (node.isArray()) {
            final JsonArray array = new JsonArray();
            node.forEach(child -> array.add(toValue(child)));
            return array;
        }
        if (node.isString()) {
            return new JsonString(node.asString());
        }
        if (node.isNumber()) {
            return new JsonNumber(node.numberValue().toString());
        }
        if (node.isBoolean()) {
            return JsonBoolean.of(node.asBoolean());
        }
        throw new ConfigException("Unexpected JSON node type: " + node.getNodeType());
    }
}
