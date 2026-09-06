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

import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.Project.SourceConfig;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.config.Template.MatchLimits;
import stroom.shapeshifter.engine.config.Template.ParamDecl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The wire format's reader and writer: the project, its source and its templates, with the
 * families a template is made of read and written by their own classes — {@link MatchJson},
 * {@link ReferenceJson}, {@link ConditionJson}, {@link OutputJson} — over the primitives in
 * {@link JsonFields}, where the format's rules are stated.
 */
public final class ProjectJson {

    private ProjectJson() {
    }

    /** Read a whole configuration. */
    public static Project readProject(final JsonNode node) {
        if (node == null || node.isNull()) {
            throw new ConfigException("Expected an object for 'project'");
        }
        JsonFields.checkFields(node, "project", "name", "version", "source", "templates", "patterns");
        return new Project(
                JsonFields.text(node, "name", "project"),
                JsonFields.required(node, "version", "project").asInt(),
                node.has("source") ? readSource(node.get("source")) : SourceConfig.defaults(),
                JsonFields.list(node.get("templates"), "templates", ProjectJson::readTemplate),
                JsonFields.list(node.get("patterns"), "patterns", MatchJson::readPattern));
    }

    /** Write a whole configuration. */
    public static ObjectNode writeProject(final Project project) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("name", project.name());
        node.put("version", project.version());
        node.set("source", writeSource(project.source()));
        node.set("templates", JsonFields.array(project.templates(), ProjectJson::writeTemplate));
        if (!project.patterns().isEmpty()) {
            node.set("patterns", JsonFields.array(project.patterns(), MatchJson::writePattern));
        }
        return node;
    }

    private static SourceConfig readSource(final JsonNode node) {
        JsonFields.checkFields(node, "source", "buffer_size", "ignore_errors", "encoding", "dispatch",
                "strict_values", "max_sequence_entries");
        return new SourceConfig(
                node.path("buffer_size").asInt(SourceConfig.DEFAULT_BUFFER_SIZE),
                node.path("ignore_errors").asBoolean(false),
                node.has("encoding") ? node.get("encoding").asString() : SourceConfig.AUTO,
                JsonFields.readDispatch(node),
                node.path("strict_values").asBoolean(false),
                node.path("max_sequence_entries")
                        .asInt(SourceConfig.DEFAULT_MAX_SEQUENCE_ENTRIES));
    }

    private static ObjectNode writeSource(final SourceConfig source) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("buffer_size", source.bufferSize());
        node.put("ignore_errors", source.ignoreErrors());
        JsonFields.writeDispatch(node, source.dispatch());
        node.put("encoding", source.encoding());
        if (source.strictValues()) {
            node.put("strict_values", true);
        }
        if (source.maxSequenceEntries() != SourceConfig.DEFAULT_MAX_SEQUENCE_ENTRIES) {
            node.put("max_sequence_entries", source.maxSequenceEntries());
        }
        return node;
    }

    private static Template readTemplate(final JsonNode node) {
        JsonFields.checkFields(node, "template", "id", "name", "mode", "guard", "param", "match",
                "match_limits", "captures", "body", "encoding", "ignore_errors", "consume");
        return new Template(
                JsonFields.uuid(node, "id", "template"),
                JsonFields.text(node, "name", "template"),
                JsonFields.optionalText(node, "mode"),
                node.path("consume").asBoolean(false),
                node.has("guard") ? ConditionJson.readCondition(node.get("guard")) : null,
                JsonFields.list(node.get("param"), "param", ProjectJson::readParamDecl),
                MatchJson.readMatch(JsonFields.required(node, "match", "template")),
                node.has("match_limits") ? readMatchLimits(node.get("match_limits")) : MatchLimits.unlimited(),
                JsonFields.list(node.get("captures"), "captures", ReferenceJson::readCapture),
                JsonFields.list(node.get("body"), "body", OutputJson::readOutput),
                JsonFields.optionalText(node, "encoding"),
                node.path("ignore_errors").asBoolean(false));
    }

    private static ObjectNode writeTemplate(final Template template) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("id", template.id().toString());
        node.put("name", template.name());
        JsonFields.putIfPresent(node, "mode", template.mode());
        if (template.consume()) {
            node.put("consume", true);
        }
        if (template.guard() != null) {
            node.set("guard", ConditionJson.writeCondition(template.guard()));
        }
        if (!template.param().isEmpty()) {
            node.set("param", JsonFields.array(template.param(), ProjectJson::writeParamDecl));
        }
        node.set("match", MatchJson.writeMatch(template.match()));
        node.set("match_limits", writeMatchLimits(template.matchLimits()));
        if (!template.captures().isEmpty()) {
            node.set("captures", JsonFields.array(template.captures(), ReferenceJson::writeCapture));
        }
        if (!template.body().isEmpty()) {
            node.set("body", JsonFields.array(template.body(), OutputJson::writeOutput));
        }
        JsonFields.putIfPresent(node, "encoding", template.encoding());
        if (template.ignoreErrors()) {
            node.put("ignore_errors", true);
        }
        return node;
    }

    private static ParamDecl readParamDecl(final JsonNode node) {
        JsonFields.checkFields(node, "param", "name", "default");
        return new ParamDecl(JsonFields.text(node, "name", "param"), JsonFields.optionalText(node, "default"));
    }

    private static ObjectNode writeParamDecl(final ParamDecl param) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("name", param.name());
        JsonFields.putIfPresent(node, "default", param.defaultValue());
        return node;
    }

    private static MatchLimits readMatchLimits(final JsonNode node) {
        JsonFields.checkFields(node, "match_limits", "min_match", "max_match", "only_match");
        final JsonNode onlyMatch = JsonFields.optional(node, "only_match");
        final Set<Integer> only = onlyMatch == null
                ? null
                : new LinkedHashSet<>(JsonFields.list(onlyMatch, "only_match", JsonNode::asInt));
        return new MatchLimits(
                node.path("min_match").asInt(0),
                node.path("max_match").asInt(MatchLimits.UNLIMITED),
                only);
    }

    private static ObjectNode writeMatchLimits(final MatchLimits limits) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("min_match", limits.minMatch());
        node.put("max_match", limits.maxMatch());
        if (limits.onlyMatch() != null) {
            final ArrayNode only = node.putArray("only_match");
            limits.onlyMatch().forEach(only::add);
        }
        return node;
    }
}
