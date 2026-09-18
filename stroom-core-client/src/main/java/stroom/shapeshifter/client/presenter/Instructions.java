/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.ApplyTemplates;
import stroom.shapeshifter.config.OutputNode.Attribute;
import stroom.shapeshifter.config.OutputNode.CallTemplate;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Element;
import stroom.shapeshifter.config.OutputNode.EmitError;
import stroom.shapeshifter.config.OutputNode.ForEach;
import stroom.shapeshifter.config.OutputNode.ForEachGroup;
import stroom.shapeshifter.config.OutputNode.If;
import stroom.shapeshifter.config.OutputNode.Namespace;
import stroom.shapeshifter.config.OutputNode.Switch;
import stroom.shapeshifter.config.OutputNode.Text;
import stroom.shapeshifter.config.OutputNode.Transform;
import stroom.shapeshifter.config.OutputNode.ValueOf;
import stroom.shapeshifter.config.OutputNode.Variable;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.json.JsonObject;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.JsonValue;
import stroom.shapeshifter.config.json.ProjectJson;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a card says about its instruction: the kind, spelt as the wire format spells it; a
 * one-line summary; and the category the add menu groups it under (design 18 §5.6: output,
 * invoke, control, transform, collection).
 */
public final class Instructions {

    public enum Category {
        OUTPUT("output"),
        INVOKE("invoke"),
        CONTROL("control"),
        TRANSFORM("transform"),
        COLLECTION("collection");

        private final String label;

        Category(final String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The kinds an author writes constantly, each with a form of its own in the card editor. */
    public static final List<String> FORM_KINDS = List.of(
            "text", "value-of", "element", "attribute", "namespace", "emit-error",
            "apply-templates", "call-template",
            "if", "choose", "switch", "variable", "for-each", "for-each-group",
            "append", "insert", "put", "remove", "clear");

    /** The transforms: edited as their wire form, listed for the add menu. */
    public static final List<String> TRANSFORM_KINDS = List.of(
            "call", "translate", "string-join", "replace", "lower-case", "upper-case", "normalize-space", "trim",
            "substring", "tokenize", "number", "add", "subtract", "multiply", "divide", "mod", "round", "floor",
            "ceiling", "abs", "string-length", "substring-before", "substring-after", "starts-with", "ends-with",
            "contains", "format-number", "parse-date", "format-date", "decode");

    private Instructions() {
    }

    /** The kind, from the wire form's own tag: the one reading of the vocabulary. */
    public static String kind(final OutputNode node) {
        final JsonValue wire = ProjectJson.writeOutput(node);
        if (wire.isString()) {
            return wire.asString();
        }
        for (final Map.Entry<String, JsonValue> entry : ((JsonObject) wire).entries()) {
            return entry.getKey();
        }
        return "?";
    }

    public static Category category(final String kind) {
        switch (kind) {
            case "text":
            case "value-of":
            case "element":
            case "attribute":
            case "namespace":
            case "emit-error":
                return Category.OUTPUT;
            case "apply-templates":
            case "call-template":
                return Category.INVOKE;
            case "if":
            case "choose":
            case "switch":
            case "variable":
            case "for-each":
            case "for-each-group":
                return Category.CONTROL;
            case "append":
            case "insert":
            case "put":
            case "remove":
            case "clear":
                return Category.COLLECTION;
            default:
                return Category.TRANSFORM;
        }
    }

    /** The one line a card shows beside its kind. */
    public static String describe(final OutputNode node) {
        if (node instanceof Text t) {
            return quote(t.value());
        } else if (node instanceof ValueOf v) {
            return ref(v.select());
        } else if (node instanceof Element e) {
            return "<" + e.name() + ">" + (e.omitIfEmpty()
                    ? " (omit if empty)"
                    : "");
        } else if (node instanceof Attribute a) {
            return a.name() + "=…" + (a.omitIfEmpty()
                    ? " (omit if empty)"
                    : "");
        } else if (node instanceof Namespace n) {
            return (n.prefix() == null
                    ? "xmlns"
                    : "xmlns:" + n.prefix()) + "=" + quote(n.uri());
        } else if (node instanceof EmitError e) {
            return e.severity().name().toLowerCase(Locale.ROOT) + ": " + ref(e.message());
        } else if (node instanceof ApplyTemplates a) {
            return "select " + ref(a.directive().select()) + (a.directive().mode() == null
                    ? " into the root"
                    : " in mode " + a.directive().mode());
        } else if (node instanceof CallTemplate c) {
            return c.name();
        } else if (node instanceof If i) {
            return GuardClause.describe(i.test());
        } else if (node instanceof Choose c) {
            return c.when().size() + (c.when().size() == 1
                    ? " branch"
                    : " branches") + (c.otherwise().isEmpty()
                    ? ""
                    : " and otherwise");
        } else if (node instanceof Switch s) {
            return "on " + ref(s.select()) + ", " + s.cases().size() + " cases";
        } else if (node instanceof Variable v) {
            return v.name();
        } else if (node instanceof ForEach f) {
            return ref(f.select()) + " as " + f.as();
        } else if (node instanceof ForEachGroup g) {
            return ref(g.select()) + " by " + ref(g.groupBy());
        } else if (node instanceof Transform t) {
            final StringBuilder sb = new StringBuilder();
            for (final RefExpression select : t.select()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(ref(select));
            }
            return sb + (t.name() == null
                    ? ""
                    : " → " + t.name());
        }
        // The collection ops and anything new: the wire form, compact and cut.
        final String wire = JsonText.print(ProjectJson.writeOutput(node));
        return wire.length() > 80
                ? wire.substring(0, 77) + "…"
                : wire;
    }

    /** A reference as a field spells it, or its wire form when it has no spelling. */
    public static String ref(final RefExpression ref) {
        final String text = ProjectJson.refOrName(ref);
        return text != null
                ? text
                : JsonText.print(ProjectJson.writeRefOrNameWire(ref));
    }

    private static String quote(final String text) {
        final String shown = text.length() > 60
                ? text.substring(0, 57) + "…"
                : text;
        return "\"" + shown.replace("\n", "⏎") + "\"";
    }
}
