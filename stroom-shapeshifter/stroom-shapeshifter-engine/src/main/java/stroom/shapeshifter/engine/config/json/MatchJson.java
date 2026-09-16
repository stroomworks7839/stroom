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
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.Template.RegexFlags;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The match family of the wire format: match expressions and regex flags, read and written
 * together, because the round trip is the property that matters and it is kept most easily
 * where both halves can be seen at once. The pattern tree and the match sequence are
 * {@link PatternJson}.
 */
final class MatchJson {

    private MatchJson() {
    }

    static RegexFlags readFlags(final JsonNode node) {
        if (node == null || node.isNull()) {
            return RegexFlags.none();
        }
        JsonFields.checkFields(node, "flags", "case_insensitive", "dot_all");
        return new RegexFlags(
                node.path("case_insensitive").asBoolean(false), node.path("dot_all").asBoolean(false));
    }

    static ObjectNode writeFlags(final RegexFlags flags) {
        final ObjectNode node = JsonFields.NODES.objectNode();
        node.put("case_insensitive", flags.caseInsensitive());
        node.put("dot_all", flags.dotAll());
        return node;
    }

    static MatchExpression readMatch(final JsonNode node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "match expression");
        final JsonNode body = tagged.body();
        return switch (tagged.name()) {
            case "regex" -> {
                JsonFields.checkFields(body, "regex", "pattern", "flags", "advance");
                yield new MatchExpression.Regex(
                        JsonFields.text(body, "pattern", "regex"), readFlags(body.get("flags")),
                        JsonFields.integer(body, "advance", "regex", 0));
            }
            case "delimiter" -> {
                JsonFields.checkFields(body, "delimiter", "delimiter", "escape", "container_start", "container_end");
                yield new MatchExpression.Delimiter(
                        JsonFields.text(body, "delimiter", "delimiter"),
                        JsonFields.optionalText(body, "escape"),
                        JsonFields.optionalText(body, "container_start"),
                        JsonFields.optionalText(body, "container_end"));
            }
            case "pattern" -> new MatchExpression.Pattern(PatternJson.readNode(body));
            case "parts" -> new MatchExpression.Parts(JsonFields.list(body, "parts", PatternJson::readPart));
            case "source" -> {
                JsonFields.checkFields(body, "source");
                yield new MatchExpression.Source();
            }
            case "all" -> {
                JsonFields.checkFields(body, "all");
                yield new MatchExpression.All();
            }
            case "named" -> {
                JsonFields.checkFields(body, "named");
                yield new MatchExpression.Named();
            }
            default -> throw new ConfigException("Unknown match expression: " + tagged.name());
        };
    }

    static JsonNode writeMatch(final MatchExpression match) {
        return switch (match) {
            case MatchExpression.Regex regex -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("pattern", regex.pattern());
                body.set("flags", writeFlags(regex.flags()));
                body.put("advance", regex.advance());
                yield JsonFields.wrap("regex", body);
            }
            case MatchExpression.Delimiter delimiter -> {
                final ObjectNode body = JsonFields.NODES.objectNode();
                body.put("delimiter", delimiter.delimiter());
                JsonFields.putIfPresent(body, "escape", delimiter.escape());
                JsonFields.putIfPresent(body, "container_start", delimiter.containerStart());
                JsonFields.putIfPresent(body, "container_end", delimiter.containerEnd());
                yield JsonFields.wrap("delimiter", body);
            }
            case MatchExpression.Pattern pattern -> JsonFields.wrap("pattern", PatternJson.writeNode(pattern.node()));
            case MatchExpression.Parts parts ->
                    JsonFields.wrap("parts", JsonFields.array(parts.parts(), PatternJson::writePart));
            case MatchExpression.Source ignored -> JsonFields.NODES.stringNode("source");
            case MatchExpression.All ignored -> JsonFields.NODES.stringNode("all");
            case MatchExpression.Named ignored -> JsonFields.NODES.stringNode("named");
        };
    }
}
