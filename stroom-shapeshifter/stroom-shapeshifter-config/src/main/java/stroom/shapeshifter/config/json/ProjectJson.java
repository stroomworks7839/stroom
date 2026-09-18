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
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Project.SourceConfig;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.MatchLimits;
import stroom.shapeshifter.config.Template.ParamDecl;

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
    public static Project readProject(final JsonValue node) {
        if (node == null || node.isNull()) {
            throw new ConfigException("Expected an object for 'project'");
        }
        JsonFields.checkFields(node, "project", "name", "version", "source", "templates");
        return new Project(
                JsonFields.text(node, "name", "project"),
                JsonFields.integer(node, "version", "project"),
                node.has("source") ? readSource(node.get("source")) : SourceConfig.defaults(),
                JsonFields.list(node.get("templates"), "templates", ProjectJson::readTemplate));
    }

    /** Write a whole configuration. */
    public static JsonObject writeProject(final Project project) {
        final JsonObject node = new JsonObject();
        node.put("name", project.name());
        node.put("version", project.version());
        node.put("source", writeSource(project.source()));
        node.put("templates", JsonFields.array(project.templates(), ProjectJson::writeTemplate));
        return node;
    }

    // The parts of a template the editor exchanges on their own (design 43 §5): a pattern tree
    // as a template's {@code pattern} match holds it, a match expression, a capture binding.
    // Each is the same reading the whole project gets, so the editor and the reader agree.

    public static PatternNode readPatternNode(final JsonValue node) {
        return PatternJson.readNode(node);
    }

    public static JsonObject writePatternNode(final PatternNode node) {
        return PatternJson.writeNode(node);
    }

    public static MatchExpression readMatch(final JsonValue node) {
        return MatchJson.readMatch(node);
    }

    public static JsonValue writeMatch(final MatchExpression match) {
        return MatchJson.writeMatch(match);
    }

    public static CaptureBinding readCapture(final JsonValue node) {
        return ReferenceJson.readCapture(node);
    }

    public static JsonObject writeCapture(final CaptureBinding capture) {
        return ReferenceJson.writeCapture(capture);
    }

    public static Condition readCondition(final JsonValue node) {
        return ConditionJson.readCondition(node);
    }

    public static JsonValue writeCondition(final Condition condition) {
        return ConditionJson.writeCondition(condition);
    }

    /**
     * A reference as the editor spells it in a field: a declared name, or a function such as
     * {@code index()} - the sugar every collection site accepts - read by the one reader.
     */
    public static RefExpression readRefOrName(final String text) {
        return ReferenceJson.readRefOrName(new JsonString(text));
    }

    /**
     * The field spelling of a reference, or null when it has none - a path, an accessor - and
     * only its wire form will do.
     */
    public static String refOrName(final RefExpression ref) {
        final JsonValue wire = ReferenceJson.writeRefOrName(ref);
        return wire.isString()
                ? wire.asString()
                : null;
    }

    private static SourceConfig readSource(final JsonValue node) {
        JsonFields.checkFields(node, "source", "buffer_size", "ignore_errors", "encoding", "dispatch",
                "strict_values", "max_sequence_entries");
        final String encoding = JsonFields.optionalText(node, "encoding");
        return new SourceConfig(
                JsonFields.integer(node, "buffer_size", "source", SourceConfig.DEFAULT_BUFFER_SIZE),
                JsonFields.flag(node, "ignore_errors"),
                encoding == null ? SourceConfig.AUTO : encoding,
                JsonFields.readDispatch(node),
                JsonFields.flag(node, "strict_values"),
                JsonFields.integer(node, "max_sequence_entries", "source",
                        SourceConfig.DEFAULT_MAX_SEQUENCE_ENTRIES));
    }

    private static JsonObject writeSource(final SourceConfig source) {
        final JsonObject node = new JsonObject();
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

    private static Template readTemplate(final JsonValue node) {
        JsonFields.checkFields(node, "template", "id", "name", "mode", "guard", "param", "declarations",
                "match", "match_limits", "captures", "body", "encoding", "ignore_errors", "consume");
        return new Template(
                JsonFields.uuid(node, "id", "template"),
                JsonFields.text(node, "name", "template"),
                JsonFields.optionalText(node, "mode"),
                JsonFields.flag(node, "consume"),
                node.has("guard") ? ConditionJson.readCondition(node.get("guard")) : null,
                JsonFields.list(node.get("param"), "param", ProjectJson::readParamDecl),
                JsonFields.list(node.get("declarations"), "declarations", ProjectJson::readDeclaration),
                MatchJson.readMatch(JsonFields.required(node, "match", "template")),
                node.has("match_limits") ? readMatchLimits(node.get("match_limits")) : MatchLimits.unlimited(),
                JsonFields.list(node.get("captures"), "captures", ReferenceJson::readCapture),
                JsonFields.list(node.get("body"), "body", OutputJson::readOutput),
                JsonFields.optionalText(node, "encoding"),
                JsonFields.flag(node, "ignore_errors"));
    }

    private static JsonObject writeTemplate(final Template template) {
        final JsonObject node = new JsonObject();
        node.put("id", template.id().toString());
        node.put("name", template.name());
        JsonFields.putIfPresent(node, "mode", template.mode());
        if (template.consume()) {
            node.put("consume", true);
        }
        if (template.guard() != null) {
            node.put("guard", ConditionJson.writeCondition(template.guard()));
        }
        if (!template.param().isEmpty()) {
            node.put("param", JsonFields.array(template.param(), ProjectJson::writeParamDecl));
        }
        if (!template.declarations().isEmpty()) {
            node.put("declarations", JsonFields.array(template.declarations(), ProjectJson::writeDeclaration));
        }
        node.put("match", MatchJson.writeMatch(template.match()));
        node.put("match_limits", writeMatchLimits(template.matchLimits()));
        if (!template.captures().isEmpty()) {
            node.put("captures", JsonFields.array(template.captures(), ReferenceJson::writeCapture));
        }
        if (!template.body().isEmpty()) {
            node.put("body", JsonFields.array(template.body(), OutputJson::writeOutput));
        }
        JsonFields.putIfPresent(node, "encoding", template.encoding());
        if (template.ignoreErrors()) {
            node.put("ignore_errors", true);
        }
        return node;
    }

    private static Declaration readDeclaration(final JsonValue node) {
        JsonFields.checkFields(node, "declaration", "name", "type", "entries");
        return new Declaration(JsonFields.text(node, "name", "declaration"),
                JsonFields.lowercase(Declaration.Type.class, JsonFields.text(node, "type", "declaration"), "type"),
                JsonFields.list(node.get("entries"), "entries", ProjectJson::readEntry));
    }

    private static Declaration.Entry readEntry(final JsonValue node) {
        JsonFields.checkFields(node, "entry", "from", "to");
        return new Declaration.Entry(JsonFields.text(node, "from", "entry"), JsonFields.text(node, "to", "entry"));
    }

    private static JsonObject writeEntry(final Declaration.Entry entry) {
        final JsonObject node = new JsonObject();
        node.put("from", entry.from());
        node.put("to", entry.to());
        return node;
    }

    private static JsonObject writeDeclaration(final Declaration declaration) {
        final JsonObject node = new JsonObject();
        node.put("name", declaration.name());
        node.put("type", JsonFields.label(declaration.type()));
        if (!declaration.entries().isEmpty()) {
            node.put("entries", JsonFields.array(declaration.entries(), ProjectJson::writeEntry));
        }
        return node;
    }

    private static ParamDecl readParamDecl(final JsonValue node) {
        JsonFields.checkFields(node, "param", "name", "default");
        return new ParamDecl(JsonFields.text(node, "name", "param"), JsonFields.optionalText(node, "default"));
    }

    private static JsonObject writeParamDecl(final ParamDecl param) {
        final JsonObject node = new JsonObject();
        node.put("name", param.name());
        JsonFields.putIfPresent(node, "default", param.defaultValue());
        return node;
    }

    private static MatchLimits readMatchLimits(final JsonValue node) {
        JsonFields.checkFields(node, "match_limits", "min_match", "max_match", "only_match");
        final JsonValue onlyMatch = JsonFields.optional(node, "only_match");
        final Set<Integer> only = onlyMatch == null
                ? null
                : new LinkedHashSet<>(JsonFields.list(onlyMatch, "only_match",
                        index -> JsonFields.integer(index, "only_match")));
        return new MatchLimits(
                JsonFields.integer(node, "min_match", "match_limits", 0),
                JsonFields.integer(node, "max_match", "match_limits", MatchLimits.UNLIMITED),
                only);
    }

    private static JsonObject writeMatchLimits(final MatchLimits limits) {
        final JsonObject node = new JsonObject();
        node.put("min_match", limits.minMatch());
        node.put("max_match", limits.maxMatch());
        if (limits.onlyMatch() != null) {
            final JsonArray only = node.putArray("only_match");
            limits.onlyMatch().forEach(only::add);
        }
        return node;
    }
}
