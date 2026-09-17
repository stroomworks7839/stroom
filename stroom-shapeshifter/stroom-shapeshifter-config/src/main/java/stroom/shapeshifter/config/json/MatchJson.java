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

import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.Template.RegexFlags;


/**
 * The match family of the wire format: match expressions and regex flags, read and written
 * together, because the round trip is the property that matters and it is kept most easily
 * where both halves can be seen at once. The pattern tree and the match sequence are
 * {@link PatternJson}.
 */
final class MatchJson {

    private MatchJson() {
    }

    static RegexFlags readFlags(final JsonValue node) {
        if (node == null || node.isNull()) {
            return RegexFlags.none();
        }
        JsonFields.checkFields(node, "flags", "case_insensitive", "dot_all");
        return new RegexFlags(
                JsonFields.flag(node, "case_insensitive"), JsonFields.flag(node, "dot_all"));
    }

    static JsonObject writeFlags(final RegexFlags flags) {
        final JsonObject node = new JsonObject();
        node.put("case_insensitive", flags.caseInsensitive());
        node.put("dot_all", flags.dotAll());
        return node;
    }

    static MatchExpression readMatch(final JsonValue node) {
        final JsonFields.Tagged tagged = JsonFields.tag(node, "match expression");
        final JsonValue body = tagged.body();
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

    static JsonValue writeMatch(final MatchExpression match) {
        if (match instanceof MatchExpression.Regex regex) {
            final JsonObject body = new JsonObject();
            body.put("pattern", regex.pattern());
            body.put("flags", writeFlags(regex.flags()));
            body.put("advance", regex.advance());
            return JsonFields.wrap("regex", body);
        } else if (match instanceof MatchExpression.Delimiter delimiter) {
            final JsonObject body = new JsonObject();
            body.put("delimiter", delimiter.delimiter());
            JsonFields.putIfPresent(body, "escape", delimiter.escape());
            JsonFields.putIfPresent(body, "container_start", delimiter.containerStart());
            JsonFields.putIfPresent(body, "container_end", delimiter.containerEnd());
            return JsonFields.wrap("delimiter", body);
        } else if (match instanceof MatchExpression.Pattern pattern) {
            return JsonFields.wrap("pattern", PatternJson.writeNode(pattern.node()));
        } else if (match instanceof MatchExpression.Parts parts) {
            return JsonFields.wrap("parts", JsonFields.array(parts.parts(), PatternJson::writePart));
        } else if (match instanceof MatchExpression.Source) {
            return new JsonString("source");
        } else if (match instanceof MatchExpression.All) {
            return new JsonString("all");
        } else if (match instanceof MatchExpression.Named) {
            return new JsonString("named");
        } else {
            throw new IllegalStateException("Unknown variant: " + match);
        }

    }
}
