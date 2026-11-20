/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.config.Template;

import java.util.Objects;
import java.util.function.BiFunction;

/// The colour of each group of the pattern workbench's subject (ruled 2026-11-20): one capture is
/// one colour wherever it is drawn. A group bound to a variable takes the colour the Design tab's
/// panes give that variable ([CaptureHues]), so `$1` beside its pattern, under the sample and in
/// the input pane is the same thing in the same colour. A group bound to nothing, or to a variable
/// nothing in scope has bound, takes a hue numbered past every one the panes allocated, so it is
/// never mistaken for one of theirs. That settles the third place a capture's colour appears,
/// which design 18 §5.11.2 left open (`Colours.hue`).
///
/// A variable's colour moves as the cursor walks, as the panes' do: the price [CaptureHues]
/// already pays for a colour that means a variable rather than a position. The workbench takes
/// one of these each time it shows its subject and hands the same one to the pattern and the
/// sample, so the two agree even when a run of the document lands between their drawing.
final class GroupHues {

    /// No colours known yet: every group numbered from one.
    static final GroupHues NONE = new GroupHues(null, null, null);

    private final String templateId;
    private final Template template;
    private final CaptureHues scope;
    private final BiFunction<String, String, String> variableHue;
    private final int allocated;

    /// @param templateId The template whose groups these are, or null for a part of the library.
    /// @param template   The template, for its bindings, or null.
    /// @param scope      The panes' colours, or null before the first run.
    GroupHues(final String templateId, final Template template, final CaptureHues scope) {
        this(templateId, template, scope, scope == null
                ? (id, name) -> null
                : scope::variableHue, scope == null
                ? 0
                : scope.allocated());
    }

    /// For a test, which has no run: the panes' colours as a lookup and a count.
    ///
    /// @param templateId  The template whose groups these are, or null for a part of the library.
    /// @param template    The template, for its bindings, or null.
    /// @param variableHue A variable's colour in the panes by template and name.
    /// @param allocated   How many colours the panes allocated.
    GroupHues(final String templateId,
              final Template template,
              final BiFunction<String, String, String> variableHue,
              final int allocated) {
        this(templateId, template, null, variableHue, allocated);
    }

    private GroupHues(final String templateId,
                      final Template template,
                      final CaptureHues scope,
                      final BiFunction<String, String, String> variableHue,
                      final int allocated) {
        this.templateId = templateId;
        this.template = template;
        this.scope = scope;
        this.variableHue = variableHue;
        this.allocated = allocated;
    }

    /// The colours of a template's groups at the host's cursor.
    ///
    /// @param host       The Design tab.
    /// @param templateId The template, or null for a part of the library.
    /// @param template   The template as it is tried, for its bindings, or null.
    /// @return The colours.
    static GroupHues of(final ProjectHost host, final String templateId, final Template template) {
        return new GroupHues(templateId, template, host.captureHues());
    }

    /// Whether these give every group the colour another does: the same subject, as bound, over
    /// the same allocation of the panes'. The host keeps one allocation per run and cursor, so a
    /// new one is a run or a step, and only then can a colour have moved.
    ///
    /// @param other The colours to compare with.
    /// @return Whether nothing would be drawn differently.
    boolean sameAs(final GroupHues other) {
        return other != null && Objects.equals(templateId, other.templateId) && template == other.template
               && scope == other.scope;
    }

    /// A group's colour.
    ///
    /// @param group Its number, counted from one.
    /// @param label Its label, for a tree's, or null.
    /// @return A CSS colour.
    String hue(final int group, final String label) {
        final String variable = variableOf(group, label);
        final String bound = variable == null || templateId == null
                ? null
                : variableHue.apply(templateId, variable);
        return bound != null
                ? bound
                : Colours.hue(allocated + group);
    }

    // The variable a group is bound to, by its number or by its label, or null
    private String variableOf(final int group, final String label) {
        if (template == null) {
            return null;
        }
        for (final CaptureBinding capture : template.captures()) {
            if ((capture.select() instanceof CaptureSource.Group g && g.group() == group)
                || (label != null && capture.select() instanceof CaptureSource.Label l
                    && label.equals(l.label()))) {
                return capture.name();
            }
        }
        return null;
    }
}
