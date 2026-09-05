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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.Template;

import java.util.List;

/**
 * The compile-time half of design 20 §4B's ordering rule. Within an element's body, as
 * written, an attribute or namespace may not follow anything that produces content; within
 * an attribute's body nothing structural may appear at all. What the compiler cannot see —
 * content arriving through {@code apply-templates} from another template before an
 * attribute — the sink refuses at run time. An attribute or namespace at a template's top
 * level is therefore allowed here: it may be running inside a caller's element.
 *
 * <p>The same walk answers whether the template writes structure at all, which the graph
 * carries as {@code structured}; one walk, one notion of what is structural.
 */
final class StructureCheck {

    private enum Container { NONE, ELEMENT, ATTRIBUTE }

    private final String templateName;

    /** Whether the walk met an element, attribute or namespace anywhere, containers included. */
    private boolean structured;

    private StructureCheck(final String templateName) {
        this.templateName = templateName;
    }

    /**
     * Check one template's bodies.
     *
     * @return whether the template writes structure — an element, attribute or namespace
     *         anywhere in its body, which decides whether a configuration can run straight into
     *         an event sink (design 20 §7, design 22)
     */
    static boolean check(final Template template) {
        final StructureCheck check = new StructureCheck(template.name());
        check.body(template.body(), Container.NONE, null, new boolean[1]);
        return check.structured;
    }

    private void body(final List<OutputNode> nodes,
                      final Container container,
                      final String containerName,
                      final boolean[] contentSeen) {
        for (final OutputNode node : nodes) {
            switch (node) {
                case OutputNode.Element value -> {
                    structured = true;
                    refuseInAttribute(container, containerName, "element '" + value.name() + "'");
                    contentSeen[0] = true;
                    body(value.body(), Container.ELEMENT, value.name(), new boolean[1]);
                }
                case OutputNode.Attribute value -> {
                    structured = true;
                    refuseInAttribute(container, containerName, "attribute '" + value.name() + "'");
                    refuseAfterContent(container, containerName, contentSeen, "attribute '" + value.name() + "'");
                    body(value.body(), Container.ATTRIBUTE, value.name(), new boolean[1]);
                }
                case OutputNode.Namespace value -> {
                    structured = true;
                    refuseInAttribute(container, containerName, "namespace '" + value.prefix() + "'");
                    refuseAfterContent(container, containerName, contentSeen, "namespace '" + value.prefix() + "'");
                }
                case OutputNode.If value -> body(value.then(), container, containerName, contentSeen);
                case OutputNode.Choose value -> {
                    value.when().forEach(branch -> body(branch.body(), container, containerName, contentSeen));
                    body(value.otherwise(), container, containerName, contentSeen);
                }
                case OutputNode.Switch value -> {
                    value.cases().forEach(c -> body(c.body(), container, containerName, contentSeen));
                    body(value.defaultBody(), container, containerName, contentSeen);
                }
                case OutputNode.ForEach value -> body(value.body(), container, containerName, contentSeen);
                case OutputNode.ForEachGroup value -> body(value.body(), container, containerName, contentSeen);
                // A variable's body writes to its own buffer: a document of its own.
                case OutputNode.Variable value -> body(value.body(), Container.NONE, null, new boolean[1]);
                default -> {
                    if (producesContent(node)) {
                        contentSeen[0] = true;
                    }
                }
            }
        }
    }

    private void refuseInAttribute(final Container container, final String containerName, final String what) {
        if (container == Container.ATTRIBUTE) {
            throw new ConfigException("Template '" + templateName + "': " + what
                                      + " inside the value of attribute '" + containerName
                                      + "' — an attribute's body may write text and values only");
        }
    }

    private void refuseAfterContent(final Container container,
                                    final String containerName,
                                    final boolean[] contentSeen,
                                    final String what) {
        if (container == Container.ELEMENT && contentSeen[0]) {
            throw new ConfigException("Template '" + templateName + "': " + what + " follows content in element '"
                                      + containerName + "' — attributes and namespaces must come before text, "
                                      + "values, child elements and apply-templates");
        }
    }

    /** Whether an instruction writes to the output, as opposed to binding, declaring or reporting. */
    private static boolean producesContent(final OutputNode node) {
        return switch (node) {
            case OutputNode.Text ignored -> true;
            case OutputNode.ValueOf ignored -> true;
            case OutputNode.ApplyTemplates ignored -> true;
            case OutputNode.CallTemplate ignored -> true;
            case OutputNode.EmitError ignored -> false;
            case OutputNode.Sequence ignored -> false;
            case OutputNode.Append ignored -> false;
            case OutputNode.Key ignored -> false;
            case OutputNode.Call value -> value.name() == null;
            case OutputNode.ValueMap value -> value.name() == null;
            case OutputNode.Translate value -> value.name() == null;
            case OutputNode.StringJoin value -> value.name() == null;
            case OutputNode.Replace value -> value.name() == null;
            case OutputNode.LowerCase value -> value.name() == null;
            case OutputNode.UpperCase value -> value.name() == null;
            case OutputNode.NormalizeSpace value -> value.name() == null;
            case OutputNode.Trim value -> value.name() == null;
            case OutputNode.Substring value -> value.name() == null;
            case OutputNode.Tokenize value -> value.name() == null;
            case OutputNode.Number value -> value.name() == null;
            case OutputNode.Add value -> value.name() == null;
            case OutputNode.Subtract value -> value.name() == null;
            case OutputNode.Multiply value -> value.name() == null;
            case OutputNode.Divide value -> value.name() == null;
            case OutputNode.Mod value -> value.name() == null;
            case OutputNode.Round value -> value.name() == null;
            case OutputNode.Floor value -> value.name() == null;
            case OutputNode.Ceiling value -> value.name() == null;
            case OutputNode.Abs value -> value.name() == null;
            case OutputNode.StringLength value -> value.name() == null;
            case OutputNode.SubstringBefore value -> value.name() == null;
            case OutputNode.SubstringAfter value -> value.name() == null;
            case OutputNode.StartsWith value -> value.name() == null;
            case OutputNode.EndsWith value -> value.name() == null;
            case OutputNode.Contains value -> value.name() == null;
            case OutputNode.FormatNumber value -> value.name() == null;
            case OutputNode.KeyGet value -> value.name() == null;
            case OutputNode.Count value -> value.name() == null;
            case OutputNode.Sum value -> value.name() == null;
            case OutputNode.Avg value -> value.name() == null;
            case OutputNode.Min value -> value.name() == null;
            case OutputNode.Max value -> value.name() == null;
            case OutputNode.DistinctValues value -> value.name() == null;
            case OutputNode.ParseDate value -> value.name() == null;
            case OutputNode.FormatDate value -> value.name() == null;
            // The containers are walked, not judged; the structural three are judged above.
            case OutputNode.If ignored -> false;
            case OutputNode.Choose ignored -> false;
            case OutputNode.Switch ignored -> false;
            case OutputNode.ForEach ignored -> false;
            case OutputNode.ForEachGroup ignored -> false;
            case OutputNode.Variable ignored -> false;
            case OutputNode.Element ignored -> true;
            case OutputNode.Attribute ignored -> false;
            case OutputNode.Namespace ignored -> false;
        };
    }
}
