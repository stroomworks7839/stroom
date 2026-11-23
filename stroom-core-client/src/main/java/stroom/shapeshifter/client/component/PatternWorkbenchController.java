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

import stroom.alert.client.event.AlertEvent;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.util.client.MouseUtil;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.Objects;
import java.util.function.BiConsumer;

/// The pattern workbench (design 18 §5.6, design 44): an in-place mode of the Design tab, not a
/// dialog and not full-screen. Its subject is a template's match. What is written is on the
/// left: the match editor — the kind and its form — and under it the guard and limits,
/// mechanism-independent, folded away when there are none. The data is on the right, where the
/// Design tab has its input: the sample to try the match against, what it found, and each
/// match's groups (ruled 2026-11-20). There is no Apply or
/// Cancel — every field commits as it changes, as everywhere else — so closing, or retargeting to
/// another template, never has anything to lose.
public class PatternWorkbenchController extends Controller {

    private final PatternWorkbench view;

    private final SampleController sample;
    private final MatchEditorController matchEditor;
    private final GuardAndLimitsController guardAndLimits;
    private final ToolbarButton closeButton;

    private ProjectHost host;
    private Runnable onClose;
    // From Contexts.READ_ONLY: true until the document says otherwise
    private boolean readOnly = true;
    // The part that is the subject, or null when it is a template
    private String part;
    // The template that is the subject, or null when it is a part
    private String templateId;
    private BiConsumer<String, String> onRenamePart = (from, to) -> {
    };
    // The groups' colours the pattern and the sample were last handed
    private GroupHues hues = GroupHues.NONE;

    @Inject
    public PatternWorkbenchController(final EventBus eventBus,
                                     final PatternWorkbench view,
                                     final SampleController sample,
                                     final MatchEditorController matchEditor,
                                     final GuardAndLimitsController guardAndLimits) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        this.sample = own(sample);
        this.matchEditor = own(matchEditor);
        this.guardAndLimits = own(guardAndLimits);
        matchEditor.setOnGroupSelect(sample::isolate);
        // A group pointed at in the pattern or its row is shown alone in the sample, and one
        // pointed at in the sample alone in the pattern and its row (ruled 2026-11-20)
        matchEditor.setOnGroupPoint(sample::pointGroup);
        sample.setOnGroupPoint(matchEditor::pointGroup);
        // A tree's node pointed at marks what it matched in the sample, and a tree that matched
        // nowhere marks the node it stopped at (design 44 §4a)
        matchEditor.setOnLabelPoint(sample::pointLabel);
        sample.setOnStopped(matchEditor::markStopped);
        matchEditor.setOnLabelSelect(sample::isolate);
        // A click on a mark in the sample selects what it is of, as a click on its row or its
        // node does (ruled 2026-11-20)
        sample.setOnPinGroup(matchEditor::selectGroup);
        sample.setOnPinLabel(matchEditor::selectLabel);
        guardAndLimits.setOnChange(this::describeGuardAndLimits);
        watch(Contexts.READ_ONLY, locked -> {
            this.readOnly = locked;
            if (part != null) {
                view.setSubject("part", part, !locked);
            }
        });
        view.getSubject().setOnToggle(() -> {
            if (view.getSubject().isOpen()) {
                takeName();
            } else if (part != null && !readOnly) {
                view.getSubject().open();
            }
        });
        view.getSubject().setOnEnter(this::takeName);
        view.getSubject().setOnCancel(() -> {
            view.getSubject().close();
            view.getSubject().focus();
        });
        view.setSample(sample);
        view.setEditor(matchEditor);
        view.setGuardAndLimits(guardAndLimits);
        // Enabled from the start: closing has nothing to decide (design 18 §5.6 - every field
        // has already committed), and SvgImage.CLOSE is a disabled preset.
        closeButton = view.getToolbar().button(SvgImage.CLOSE, "Close the workbench (Escape)", () -> {
        });
        own(closeButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event) && onClose != null) {
                onClose.run();
            }
        }));

    }


    /// Closed: the match editor's drafts of the kinds not chosen go with the session.
    public void closed() {
        matchEditor.forget();
        // Opened again, the template is shown afresh: its guard and limits open if it has any
        templateId = null;
        view.forgetGuardFold();
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        sample.setHost(host);
        matchEditor.setHost(host);
        guardAndLimits.setHost(host);
    }

    /// Told when the workbench is to be closed.
    ///
    /// @param onClose The handler.
    public void setOnClose(final Runnable onClose) {
        this.onClose = onClose;
    }

    public void setTemplate(final String id) {
        final Template template = host.template(id);
        part = null;
        view.setSubject("", template == null
                ? ""
                : template.name(), false);
        view.showGuardAndLimits(true);
        recolour(id, template);
        matchEditor.setTemplate(id);
        guardAndLimits.setTemplate(id);
        sample.setTemplate(id);
        // Folded or opened for a template just shown, and not as it is edited: deleting the last
        // clause must not fold the clauses away under the pointer
        final boolean shown = !id.equals(templateId);
        templateId = id;
        describeGuardAndLimits();
        if (shown && template != null) {
            view.foldGuardAndLimits(Templates.unguardedAndUnlimited(template));
        }
    }

    // One set of colours for the pattern and the sample, so the two agree: each group the colour
    // of the variable it is bound to, as the Design tab's panes draw it (ruled 2026-11-20). Taken
    // as the subject is shown, which draws them.
    private void recolour(final String id, final Template template) {
        hues = GroupHues.of(host, id, template);
        matchEditor.setHues(hues);
        sample.setHues(hues);
    }

    /// Draws the groups in their variables' colours again, if a run of the document or a step of
    /// its cursor moved any: the Design tab's panes allocate them, and the workbench must not keep
    /// a colour that is now another variable's.
    public void recolour() {
        final GroupHues next = GroupHues.of(host, templateId, templateId == null
                ? null
                : host.template(templateId));
        if (!next.sameAs(hues)) {
            hues = next;
            matchEditor.recolour(next);
            sample.recolour(next);
        }
    }

    // What the template's guard and limits hold, on their title bar
    private void describeGuardAndLimits() {
        final Template template = templateId == null
                ? null
                : host.template(templateId);
        if (template != null) {
            view.describeGuardAndLimits(Templates.describeGuardAndLimits(template));
        }
    }

    /// Opens the guard and limits, as asked for from the template's own line for them: what the
    /// workbench was opened for.
    public void openGuardAndLimits() {
        view.openGuardAndLimits();
    }

    /// Opens the part's name to be changed, where it reads.
    public void editName() {
        if (part != null && !readOnly) {
            view.getSubject().open();
        }
    }

    /// @param onRenamePart Asked to rename the part that is the subject, with its name and the
    ///                     name it is to have, once the name is known to be free. The owner
    ///                     renames it, so that the selection goes with the name (design 47 §7.8,
    ///                     finding 5).
    public void setOnRenamePart(final BiConsumer<String, String> onRenamePart) {
        this.onRenamePart = onRenamePart;
    }

    // The part's name, taken: Enter in it, or its edit button pressed again. Every tree that
    // names the part follows it.
    private void takeName() {
        final Project project = host.getProject();
        final String from = part;
        if (!view.getSubject().isOpen() || from == null || readOnly || project == null) {
            return;
        }
        final String name = view.getSubject().getText().trim();
        if (name.isEmpty()) {
            AlertEvent.fireWarn(this, "A pattern part needs a name", null);
            return;
        }
        if (!name.equals(from) && project.patterns().containsKey(name)) {
            AlertEvent.fireWarn(this, "There is already a pattern part named '" + name + "'", null);
            return;
        }
        view.getSubject().close();
        if (!name.equals(from)) {
            onRenamePart.accept(from, name);
        }
        view.getSubject().focus();
    }

    /// A part of the project's library as the subject (design 44 §3): a tree with no template around it.
    public void setPattern(final String name) {
        if (!Objects.equals(name, part)) {
            // A name being typed is the part's that was shown, and goes with it: left open, it
            // renamed whichever part was shown next (design 47 §7.8, finding 2). Let go rather
            // than taken, as a mode's name is: taking it would move the selection that is just
            // being changed.
            view.getSubject().close();
        }
        part = name;
        templateId = null;
        view.setSubject("part", name, !readOnly);
        view.showGuardAndLimits(false);
        recolour(null, null);
        matchEditor.setPattern(name);
        sample.setPattern(name);
    }

}
