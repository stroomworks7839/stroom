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

    private enum Enclosing { NONE, ELEMENT, ATTRIBUTE }

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
        check.body(template.body(), Enclosing.NONE, null, false);
        return check.structured;
    }

    /**
     * Walk one body in written order. The three structural instructions are judged and open a
     * body of their own; a variable's body is a document of its own; every other container
     * passes through, its bodies walked in the enclosing element with the content seen so far.
     *
     * @param contentSeen whether content has been written in the enclosing element before
     *                    this body
     * @return whether content has been written in the enclosing element after it
     */
    private boolean body(final List<OutputNode> nodes,
                         final Enclosing within,
                         final String name,
                         final boolean seen) {
        boolean contentSeen = seen;
        for (final OutputNode node : nodes) {
            switch (node) {
                case OutputNode.Element value -> {
                    structured = true;
                    refuseInAttribute(within, name, "element '" + value.name() + "'");
                    contentSeen = true;
                    body(value.body(), Enclosing.ELEMENT, value.name(), false);
                }
                case OutputNode.Attribute value -> {
                    structured = true;
                    refuseInAttribute(within, name, "attribute '" + value.name() + "'");
                    refuseAfterContent(within, name, contentSeen, "attribute '" + value.name() + "'");
                    body(value.body(), Enclosing.ATTRIBUTE, value.name(), false);
                }
                case OutputNode.Namespace value -> {
                    structured = true;
                    refuseInAttribute(within, name, "namespace '" + value.prefix() + "'");
                    refuseAfterContent(within, name, contentSeen, "namespace '" + value.prefix() + "'");
                }
                // A variable's body writes to its own buffer: a document of its own.
                case OutputNode.Variable value -> body(value.body(), Enclosing.NONE, null, false);
                case OutputNode.Holder holder -> {
                    // A container's bodies pass through in written order, with the content seen.
                    for (final List<OutputNode> nested : holder.bodies()) {
                        contentSeen = body(nested, within, name, contentSeen);
                    }
                }
                // A binding writes only when it binds nothing; the leaves that write are named.
                case OutputNode.Binding binding -> contentSeen |= !binding.binds();
                case OutputNode.Text ignored -> contentSeen = true;
                case OutputNode.ValueOf ignored -> contentSeen = true;
                case OutputNode.ApplyTemplates ignored -> contentSeen = true;
                case OutputNode.CallTemplate ignored -> contentSeen = true;
                case OutputNode.EmitError ignored -> {
                    // Reports; writes nothing.
                }
                case OutputNode.Append ignored -> {
                    // Accumulates; writes nothing.
                }
            }
        }
        return contentSeen;
    }

    private void refuseInAttribute(final Enclosing within, final String name, final String what) {
        if (within == Enclosing.ATTRIBUTE) {
            throw new ConfigException("Template '" + templateName + "': " + what
                                      + " inside the value of attribute '" + name
                                      + "' — an attribute's body may write text and values only");
        }
    }

    private void refuseAfterContent(final Enclosing within,
                                    final String name,
                                    final boolean contentSeen,
                                    final String what) {
        if (within == Enclosing.ELEMENT && contentSeen) {
            throw new ConfigException("Template '" + templateName + "': " + what + " follows content in element '"
                                      + name + "' — attributes and namespaces must come before text, "
                                      + "values, child elements and apply-templates");
        }
    }
}
