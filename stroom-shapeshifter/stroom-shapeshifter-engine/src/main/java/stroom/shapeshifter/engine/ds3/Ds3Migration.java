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

package stroom.shapeshifter.engine.ds3;

import stroom.shapeshifter.engine.config.CaptureBinding;
import stroom.shapeshifter.engine.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.Project.SourceConfig;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.config.Template.MatchLimits;
import stroom.shapeshifter.engine.config.Template.RegexFlags;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a DS3 configuration into a template configuration.
 *
 * <p>The two models disagree about what a configuration <i>is</i>. DS3 nests: an expression
 * contains groups, a group contains expressions and data, and the nesting is the control flow.
 * The template model dispatches: templates are flat, and a body hands content to whichever
 * templates carry the right mode. So converting is mostly flattening — every nested expression
 * becomes a template of its own with a generated mode, and its parent gets an
 * {@code apply-templates} naming that mode.
 *
 * <p>The other half is that DS3 has a fixed output format. It always produces {@code records:2}
 * XML with {@code <data>} elements, so the conversion also has to say what the output <i>is</i>:
 * the envelope, the record, the data element and its attributes, as the structural instructions
 * of design 20. It says nothing about how they look — indentation, escaping and wrapping are
 * the sink's (D41, Saxon's bytes) — and the rule that a record only appears when it has content
 * is the element's {@code omit-if-empty}. The result runs on the same engine as everything else
 * rather than needing a DS3 mode.
 *
 * <p>No configuration has to be migrated — D4 kept DS3 running untouched — but its fixtures are
 * the only external oracle this port has, so the conversion has to be exact.
 */
public final class Ds3Migration {

    /** The declaration is document-level text; the sink writes everything from the root down. */
    private static final String XML_DECLARATION = "<?xml version=\"1.1\" encoding=\"UTF-8\"?>\n";
    private static final String RECORDS_NAMESPACE = "records:2";
    private static final String XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String SCHEMA_LOCATION = "records:2 file://records-v2.0.xsd";
    private static final String RECORDS_VERSION = "2.0";

    /** The mode the envelope dispatches to, and therefore the one root expressions carry. */
    private static final String ROOT_MODE = "__root";

    private final List<Template> templates = new ArrayList<>();
    private int modeCounter;

    private Ds3Migration() {
    }

    /** Read a DS3 XML configuration and convert it. */
    public static Project importXml(final String xml) {
        return convert(Ds3Parser.parse(xml));
    }

    /** Convert a parsed DS3 configuration. */
    public static Project convert(final Ds3Config root) {
        final Ds3Migration migration = new Ds3Migration();
        return migration.run(root);
    }

    private Project run(final Ds3Config root) {
        final SourceConfig source = root instanceof Ds3Config.Root document
                ? new SourceConfig(document.bufferSize(), document.ignoreErrors(), SourceConfig.AUTO,
                        null, false, SourceConfig.DEFAULT_MAX_SEQUENCE_ENTRIES)
                : SourceConfig.defaults();

        final List<Ds3Config> rootChildren = root.children();
        for (int i = 0; i < rootChildren.size(); i++) {
            final Ds3Config child = rootChildren.get(i);
            if (child.isExpression()) {
                expression(child, null, true, "dataSplitter/" + kind(child) + "[" + i + "]");
            } else if (root instanceof Ds3Config.Root) {
                // Anything else at the root would be dropped, and a dropped element is the
                // quietly-wrong import this package refuses to be.
                throw new ConfigException(
                        "<" + kind(child) + "> is not allowed directly under <dataSplitter>; "
                        + "only <split>, <regex> and <all> are");
            }
        }

        // Every root expression shares one mode, so the engine dispatches them in order against a
        // shared cursor — which is DS3's own semantics, where a header split consumes the first
        // line and leaves the rest to the split after it.
        final List<Template> withModes = templates.stream()
                .map(template -> template.mode() != null
                        ? template
                        : new Template(template.id(), template.name(), ROOT_MODE, template.consume(),
                        template.guard(), template.param(), template.match(), template.matchLimits(),
                        template.captures(), template.body(), template.encoding(), template.ignoreErrors()))
                .toList();

        final List<Template> all = new ArrayList<>();
        all.add(envelope());
        all.addAll(withModes);
        return new Project("", 3, source, all, List.of());
    }

    /** The document template: the {@code records:2} wrapper, written once around everything. */
    private static Template envelope() {
        return new Template(
                UUID.nameUUIDFromBytes("ds3:envelope".getBytes(StandardCharsets.UTF_8)),
                "envelope",
                null,
                false,
                null,
                List.of(),
                new MatchExpression.Source(),
                MatchLimits.unlimited(),
                List.of(),
                List.of(
                        new OutputNode.Text(XML_DECLARATION),
                        // DS3's fixed root, as DS3Parser.startDocument declares it: the default
                        // prefix and xsi, then the schema location and version.
                        new OutputNode.Element("records", null, false, List.of(
                                new OutputNode.Namespace("", RECORDS_NAMESPACE),
                                new OutputNode.Namespace("xsi", XSI_NAMESPACE),
                                literalAttribute("xsi:schemaLocation", SCHEMA_LOCATION),
                                literalAttribute("version", RECORDS_VERSION),
                                new OutputNode.ApplyTemplates(new ApplyDirective(
                                        RefExpression.group(0), ROOT_MODE, List.of(),
                                        ApplyDirective.DEFAULT_MAX_DEPTH, null, false, null))))),
                null,
                false);
    }

    // -----------------------------------------------------------------------------------
    // Expressions become templates
    // -----------------------------------------------------------------------------------

    /**
     * Convert one expression, and everything under it, into templates.
     *
     * @param mode        the mode this template answers to, or null for a root expression
     * @param recordWrap  whether a match here is a record, and so needs the {@code <record>}
     *                    wrapper. True at the root and false below it — records do not nest
     * @param path        where this node sits in the DS3 tree, for deriving a stable identifier
     */
    private void expression(final Ds3Config node,
                            final String mode,
                            final boolean recordWrap,
                            final String path) {
        final List<CaptureBinding> captures = new ArrayList<>();
        final List<OutputNode> body = new ArrayList<>();

        // A single unnamed group under an expression adds nothing but a level of nesting, so its
        // contents are treated as the expression's own — unless it carries ignoreErrors, which
        // only survives if the group does.
        List<Ds3Config> children = node.children();
        String childPath = path;
        if (children.size() == 1
            && children.getFirst() instanceof Ds3Config.Group group
            && group.value() == null
            && !group.ignoreErrors()) {
            children = group.children();
            childPath = path + "/group[0]";
        }

        // The expression owns the record wrapper when it has one, so its children never add a
        // second: a group inside a wrapped expression is part of the record, not another record.
        children(children, captures, body, false, childPath);

        if (recordWrap) {
            wrapAsRecord(body);
        }

        // A DS3 <var> stores the container-stripped content, which is group 1 of a split rather
        // than group 0 — group 0 still carries the quotes.
        final List<CaptureBinding> adjusted = captures.stream()
                .map(capture -> capture.select() instanceof CaptureSource.Group group && group.group() == 0
                        ? new CaptureBinding(capture.name(), new CaptureSource.Group(1))
                        : capture)
                .toList();

        templates.add(new Template(
                identifier(node, path),
                name(node),
                mode,
                false,
                onlyMatchGuard(node),
                List.of(),
                matchExpression(node),
                matchLimits(node),
                adjusted,
                body,
                null,
                false));
    }

    /**
     * Wrap a body in a {@code <record>} that is written only if the body wrote something.
     *
     * <p>Java's DS3 starts a record lazily, at its first {@code <data>}, so a line that matched but
     * yielded no data leaves no trace. {@code omit-if-empty} is that laziness as a property of
     * the element: the sink defers the start tag anyway, and drops it if nothing ever arrives.
     */
    private static void wrapAsRecord(final List<OutputNode> body) {
        final List<OutputNode> inner = List.copyOf(body);
        body.clear();
        body.add(record(inner));
    }

    private static OutputNode record(final List<OutputNode> body) {
        return new OutputNode.Element("record", null, true, body);
    }

    // -----------------------------------------------------------------------------------
    // Children become captures and output
    // -----------------------------------------------------------------------------------

    private void children(final List<Ds3Config> children,
                          final List<CaptureBinding> captures,
                          final List<OutputNode> body,
                          final boolean recordWrap,
                          final String path) {
        // Sibling expressions share one mode and one dispatch, as they do at the root and in a
        // group: DS3 runs them in order against a single cursor over the parent's content, so a
        // split that consumes the first line leaves the rest to the expression after it. Giving
        // each sibling its own level would instead rescan the whole content once per sibling,
        // and every sibling but the first would then accuse the content its predecessor ate.
        final boolean hasExpression = children.stream().anyMatch(Ds3Config::isExpression);
        final String subMode = hasExpression ? nextMode() : null;
        boolean dispatched = false;

        for (int i = 0; i < children.size(); i++) {
            final Ds3Config child = children.get(i);
            final String childPath = path + "/" + kind(child) + "[" + i + "]";
            switch (child) {
                case Ds3Config.Var var -> variable(var, captures, body);
                case Ds3Config.Data data -> data(data, captures, body, childPath);
                case Ds3Config.Group group ->
                        group(group, captures, body, recordWrap, childPath);
                default -> {
                    if (child.isExpression()) {
                        expression(child, subMode, false, childPath);
                        if (!dispatched) {
                            // One dispatch, at the first sibling's position: the level it opens
                            // is where all of them live.
                            body.add(new OutputNode.ApplyTemplates(new ApplyDirective(
                                    RefExpression.group(0), subMode, List.of(),
                                    ApplyDirective.DEFAULT_MAX_DEPTH, null, false, null)));
                            dispatched = true;
                        }
                    }
                }
            }
        }
    }

    /**
     * A {@code <var>}: a capture when it names a group, an output instruction when it computes.
     *
     * <p>The distinction is whether the value can be answered from the match alone. If it can,
     * it is a capture, and captures are bound before the body runs — which is what lets a later
     * template read it. If it needs another variable's value, it has to run in order, so it
     * becomes an instruction.
     */
    private static void variable(final Ds3Config.Var var,
                                 final List<CaptureBinding> captures,
                                 final List<OutputNode> body) {
        if (var.value() == null) {
            captures.add(new CaptureBinding(var.id(), new CaptureSource.Group(0)));
            return;
        }
        final RefExpression reference = LegacyRefs.parse(var.value());
        final boolean localOnly = reference.parts().stream().allMatch(part ->
                part instanceof RefPart.Text
                || (part instanceof RefPart.Capture capture && capture.varId() == null));
        if (localOnly) {
            captures.add(new CaptureBinding(var.id(), new CaptureSource.Select(reference)));
        } else {
            body.add(new OutputNode.Variable(var.id(),
                    List.of(new OutputNode.ValueOf(indexed(reference)))));
        }
    }

    /** A {@code <group>}: a record boundary, a scope, or just a level of nesting. */
    private void group(final Ds3Config.Group group,
                       final List<CaptureBinding> captures,
                       final List<OutputNode> body,
                       final boolean recordWrap,
                       final String path) {
        final List<Ds3Config> members = group.children();
        final boolean hasExpression = members.stream().anyMatch(Ds3Config::isExpression);
        if (!hasExpression) {
            final List<OutputNode> into = recordWrap ? new ArrayList<>() : body;
            children(members, captures, into, false, path);
            if (recordWrap) {
                body.add(record(into));
            }
            return;
        }

        final String subMode = nextMode();
        for (int i = 0; i < members.size(); i++) {
            final Ds3Config child = members.get(i);
            if (child.isExpression()) {
                expression(child, subMode, false, path + "/" + kind(child) + "[" + i + "]");
            }
        }

        final List<OutputNode> into = recordWrap ? new ArrayList<>() : body;
        // The group's ignoreErrors gates the level its content is dispatched to — the container
        // owns the gate in DS3, and the directive is where the container's intent survives
        // flattening.
        into.add(new OutputNode.ApplyTemplates(new ApplyDirective(
                group.value() == null ? RefExpression.group(0) : LegacyRefs.parse(group.value()),
                subMode, List.of(), ApplyDirective.DEFAULT_MAX_DEPTH, null, group.ignoreErrors(), null)));
        for (int i = 0; i < members.size(); i++) {
            final Ds3Config child = members.get(i);
            if (!child.isExpression()) {
                switch (child) {
                    case Ds3Config.Var var -> {
                        if (var.value() == null) {
                            captures.add(new CaptureBinding(var.id(), new CaptureSource.Group(0)));
                        } else {
                            into.add(new OutputNode.Variable(var.id(),
                                    List.of(new OutputNode.ValueOf(
                                            indexed(LegacyRefs.parse(var.value()))))));
                        }
                    }
                    case Ds3Config.Data data -> data(data, captures, into,
                            path + "/" + kind(child) + "[" + i + "]");
                    case Ds3Config.Group nested -> group(nested, captures, into, false,
                            path + "/" + kind(child) + "[" + i + "]");
                    default -> {
                        // Nothing else can appear here.
                    }
                }
            }
        }
        if (recordWrap) {
            body.add(record(into));
        }
    }

    /**
     * A {@code <data>}: one element of the output, exactly as {@code DS3Parser} writes it.
     *
     * <p>The element is always written; each attribute is written only if its value, trimmed,
     * is not empty ({@code normaliseBuffer}, E33); children nest inside it, and the sink
     * self-closes it if they wrote nothing. Nothing here escapes anything — the sink does, by
     * construction — which is what design 20 §1 was for.
     */
    private void data(final Ds3Config.Data data,
                      final List<CaptureBinding> captures,
                      final List<OutputNode> body,
                      final String path) {
        final List<OutputNode> inner = new ArrayList<>();
        if (data.name() != null) {
            inner.add(dataAttribute("name", data.name()));
        }
        if (data.value() != null) {
            inner.add(dataAttribute("value", data.value()));
        }
        children(data.children(), captures, inner, false, path);
        body.add(new OutputNode.Element("data", null, false, inner));
    }

    /** A {@code name} or {@code value} attribute: a literal or a reference, trimmed, dropped if empty. */
    private static OutputNode dataAttribute(final String attribute, final String text) {
        final RefExpression value = isReference(text)
                ? indexed(LegacyRefs.parse(text))
                : RefExpression.text(text);
        return new OutputNode.Attribute(attribute, true, List.of(new OutputNode.Trim(List.of(value), null)));
    }

    /** A fixed attribute of the root, written as it is. */
    private static OutputNode literalAttribute(final String attribute, final String text) {
        return new OutputNode.Attribute(attribute, false, List.of(new OutputNode.Text(text)));
    }

    /** Apply {@link #indexVarReads} across a whole expression. */
    private static RefExpression indexed(final RefExpression expression) {
        return new RefExpression(expression.parts().stream().map(Ds3Migration::indexVarReads).toList());
    }

    /**
     * Make a variable read pick the value belonging to the current match.
     *
     * <p>{@code $heading$1} means "the heading for this column", and columns are counted by the
     * parent's match number — so the read has to be indexed by it rather than taking whatever was
     * stored last, which would give every column the final heading. The group is forced to 0
     * because the {@code $1} in {@code $heading$1} chose which group to <i>store</i>, not which
     * to read back.
     */
    private static RefPart indexVarReads(final RefPart part) {
        if (part instanceof RefPart.Capture capture && capture.varId() != null) {
            return new RefPart.Capture(
                    capture.varId(),
                    0,
                    capture.matchIndex() != null
                            ? capture.matchIndex()
                            : new MatchIndex(0, false, false, "__match_count"));
        }
        return part;
    }

    // -----------------------------------------------------------------------------------
    // Small pieces
    // -----------------------------------------------------------------------------------

    private static boolean isReference(final String text) {
        // The same three sigils LegacyRefs.parse() recognises — anything else is a literal.
        return text.startsWith("$") || text.startsWith("'") || text.startsWith("@");
    }

    private String nextMode() {
        return "__auto_" + modeCounter++;
    }

    private static MatchExpression matchExpression(final Ds3Config node) {
        return switch (node) {
            case Ds3Config.Split split -> new MatchExpression.Delimiter(
                    split.delimiter(), split.escape(), split.containerStart(), split.containerEnd());
            case Ds3Config.Regex regex -> new MatchExpression.Regex(
                    regex.pattern(),
                    new RegexFlags(regex.caseInsensitive(), regex.dotAll()),
                    regex.advance());
            default -> new MatchExpression.All();
        };
    }

    private static MatchLimits matchLimits(final Ds3Config node) {
        return switch (node) {
            // onlyMatch is deliberately not a limit here — see onlyMatchGuard.
            case Ds3Config.Split split -> new MatchLimits(split.minMatch(), split.maxMatch(), null);
            case Ds3Config.Regex regex -> new MatchLimits(regex.minMatch(), regex.maxMatch(), null);
            default -> MatchLimits.unlimited();
        };
    }

    /**
     * Turn {@code onlyMatch} into a guard rather than a match limit.
     *
     * <p>They sound like the same thing and are not. A match limit counts <i>this</i> template's
     * matches; DS3's {@code onlyMatch} counts the <i>parent's</i>. So "only the second one" means
     * "only when my parent is on its second match", which is a condition on the enclosing
     * counter — a guard.
     */
    private static Condition onlyMatchGuard(final Ds3Config node) {
        final Set<Integer> onlyMatch = switch (node) {
            case Ds3Config.Split split -> split.onlyMatch();
            case Ds3Config.Regex regex -> regex.onlyMatch();
            default -> null;
        };
        if (onlyMatch == null || onlyMatch.isEmpty()) {
            return null;
        }
        // The guard reads __match_idx, which the engine binds as Int — the exact case that
        // makes legacy equality mean "compare string forms": both sides read as strings
        // (design/17 §8).
        final List<Condition> conditions = onlyMatch.stream()
                .map(index -> (Condition) new Condition.Compare(Condition.Compare.Op.EQ,
                        new Condition.Operand(
                                new RefExpression(List.of(new RefPart.Capture("__match_idx", 0, null))),
                                null, Cast.STRING),
                        new Condition.Operand(null,
                                new Condition.Literal.Text(Integer.toString(index - 1)), Cast.STRING)))
                .toList();
        return conditions.size() == 1 ? conditions.getFirst() : new Condition.Or(conditions);
    }

    private static String name(final Ds3Config node) {
        final String id = identifierText(node);
        return id == null ? "unnamed" : id;
    }

    /**
     * An identifier for a template.
     *
     * <p>DS3 ids are names, not identifiers, so they are hashed into one deterministically —
     * the same configuration produces the same ids every time, which matters for anything that
     * records what a template did. A node with no id gets one from its position in the tree
     * ({@code dataSplitter/split[0]/regex[1]}), which is just as stable.
     */
    private static UUID identifier(final Ds3Config node, final String path) {
        final String id = identifierText(node);
        if (id == null) {
            return UUID.nameUUIDFromBytes(("ds3:" + path).getBytes(StandardCharsets.UTF_8));
        }
        try {
            return UUID.fromString(id);
        } catch (final IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The DS3 element name for a node, for tree paths and messages. */
    private static String kind(final Ds3Config node) {
        return switch (node) {
            case Ds3Config.Root ignored -> "dataSplitter";
            case Ds3Config.Split ignored -> "split";
            case Ds3Config.Regex ignored -> "regex";
            case Ds3Config.All ignored -> "all";
            case Ds3Config.Group ignored -> "group";
            case Ds3Config.Data ignored -> "data";
            case Ds3Config.Var ignored -> "var";
        };
    }

    private static String identifierText(final Ds3Config node) {
        return switch (node) {
            case Ds3Config.Split split -> split.id();
            case Ds3Config.Regex regex -> regex.id();
            case Ds3Config.All all -> all.id();
            default -> null;
        };
    }
}
