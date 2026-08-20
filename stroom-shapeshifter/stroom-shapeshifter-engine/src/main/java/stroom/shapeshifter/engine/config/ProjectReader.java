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

package stroom.shapeshifter.engine.config;

import stroom.shapeshifter.engine.config.json.ProjectJson;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * Turns configuration documents into {@link Project}s, and back.
 *
 * <p>Everything that reads a configuration goes through here, so that what a configuration is
 * stored as stays one decision in one place. Today that is JSON via Jackson (D33); the model
 * underneath knows nothing about either.
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
            return ProjectJson.readProject(MAPPER.readTree(json));
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
        return MAPPER.writeValueAsString(ProjectJson.writeProject(project));
    }

    /** Write a configuration as indented JSON, for a human or a diff. */
    public static String writePretty(final Project project) {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ProjectJson.writeProject(project));
    }
}
