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

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.shapeshifter.client.presenter.TemplatePanelPresenter.TemplatePanelView;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterTrace.Timing;
import stroom.svg.client.Preset;
import stroom.svg.client.SvgPresets;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MouseUtil;

import com.google.gwt.event.logical.shared.HasValueChangeHandlers;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.event.shared.LegacyHandlerWrapper;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The project panel (design 18 §5.6, 43 §4.1): the sample the project runs over at the head —
 * nothing else here means anything without it — then the project itself, then the templates
 * grouped by mode in project order, which is dispatch order, so up and down are semantics, and
 * last the pattern library's parts. A template's row is a swatch, a name and, once there is a
 * trace, its match count and heat. Managed through the toolbar over the list, acting on the
 * selection; the selection is published as a value change of the row's id — a template's, a
 * part's, the sample's, or null for the project — and the root shows what that row is.
 */
public class TemplatePanelPresenter
        extends MyPresenterWidget<TemplatePanelView>
        implements TemplatePanelUiHandlers, HasValueChangeHandlers<String> {

    private final TemplateEditPresenter editPresenter;
    private final ModeEditorPresenter modeEditor;
    private final NamePresenter namePrompt;
    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;
    private final ButtonView upButton;
    private final ButtonView downButton;
    private final ButtonView modesButton;
    private final ButtonView patternButton;

    private ProjectHost host;
    private String selected;

    @Inject
    public TemplatePanelPresenter(final EventBus eventBus,
                                  final TemplatePanelView view,
                                  final TemplateEditPresenter editPresenter,
                                  final ModeEditorPresenter modeEditor,
                                  final NamePresenter namePrompt) {
        super(eventBus, view);
        this.editPresenter = editPresenter;
        this.modeEditor = modeEditor;
        this.namePrompt = namePrompt;
        view.setUiHandlers(this);
        addButton = view.addButton(SvgPresets.ADD.title("Add template"));
        editButton = view.addButton(SvgPresets.EDIT.title("Edit template"));
        removeButton = view.addButton(SvgPresets.DELETE.title("Remove template"));
        upButton = view.addButton(SvgPresets.UP.title("Move up: earlier in dispatch order"));
        downButton = view.addButton(SvgPresets.DOWN.title("Move down: later in dispatch order"));
        modesButton = view.addButton(SvgPresets.enabled(SvgImage.TAGS, "Modes: add, rename or remove"));
        patternButton = view.addButton(SvgPresets.enabled(SvgImage.LINK,
                "Add a pattern part: a tree defined once, named with ref from any template"));
        enableButtons();
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        modeEditor.setHost(host);
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(addButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onAdd();
            }
        }));
        registerHandler(editButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                editSelected();
            }
        }));
        registerHandler(removeButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onRemove();
            }
        }));
        registerHandler(upButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onMove(-1);
            }
        }));
        registerHandler(downButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onMove(1);
            }
        }));
        registerHandler(modesButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event) && host.getProject() != null) {
                modeEditor.show();
            }
        }));
        registerHandler(patternButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onAddPattern();
            }
        }));
    }

    @Override
    public HandlerRegistration addValueChangeHandler(final ValueChangeHandler<String> handler) {
        return new LegacyHandlerWrapper(addHandlerToSource(ValueChangeEvent.getType(), handler));
    }

    /** The selected template's id, or null when the project row (or nothing) is selected. */
    public String getSelectedTemplateId() {
        return selected;
    }

    @Override
    public void onSelect(final String id) {
        select(id, true);
    }

    @Override
    public void onOpen(final String id) {
        select(id, true);
        editSelected();
    }

    @Override
    public void onHover(final String id) {
        if (host != null) {
            host.hover(id == null
                    ? null
                    : Hot.template(id));
        }
    }

    /** A template, or a frame of one, lights its row. */
    public void setHot(final Hot hot) {
        getView().setHot(hot == null || hot.getKind() == Hot.Kind.CAPTURE || hot.getKind() == Hot.Kind.INSTRUCTION
                ? null
                : hot.getTemplateId());
    }

    /** Select a template, or the project for null, as if clicked: the root follows through the event. */
    public void select(final String id) {
        select(id, true);
    }

    private void select(final String id, final boolean fire) {
        final boolean changed = !Objects.equals(id, selected);
        selected = id;
        getView().setSelected(id);
        enableButtons();
        if (fire && changed) {
            ValueChangeEvent.fire(this, id);
        }
    }

    /** Re-read the rows from the host's project, keeping the selection by id where it survives. */
    public void refresh() {
        final Project project = host.getProject();
        final List<TemplateRowData> rows = new ArrayList<>();
        // What the project runs over, first: nothing else here means anything without it
        // (design 18 §5.7, design 44 §5a).
        rows.add(new TemplateRowData(SampleSource.rowId(), "Sample data", "data", "transparent",
                host.getSampleSource() == null
                        ? "none"
                        : host.getSampleSource().getLabel(), host.getSampleSource() == null));
        rows.add(new TemplateRowData(null, project == null
                ? "project"
                : project.name(), "document", "transparent", host.trace() == null
                ? "doc"
                : Profile.runTotal(host.trace()), false));
        boolean survives = selected == null || SampleSource.isRow(selected);
        if (project != null) {
            // Grouped by mode - the root group first, then modes as they first appear - and in
            // project order within a group, which is the order dispatch tries them in.
            final List<String> modes = new ArrayList<>();
            modes.add(null);
            for (final Template template : project.templates()) {
                if (!modes.contains(template.mode())) {
                    modes.add(template.mode());
                }
            }
            final TraceModel trace = host.trace();
            for (final String mode : modes) {
                // The root group is structural, not a named mode; it is headed once, unnamed.
                final String section = mode == null
                        ? "root"
                        : "mode: " + mode;
                for (final Template template : project.templates()) {
                    if (Objects.equals(template.mode(), mode)) {
                        // The profile is a run's reading (design 18 §5.8): before one, the row has a
                        // kind and no heat.
                        rows.add(trace == null
                                ? new TemplateRowData(template.id(), template.name(), section,
                                        host.colour(template.id()), count(null, template), false)
                                : new TemplateRowData(template.id(), template.name(), section,
                                        host.colour(template.id()), count(trace, template), zero(trace, template),
                                        Profile.share(trace, template.id()), Profile.cost(trace, template.id()),
                                        Profile.describe(trace, template.id()),
                                        trace.worstOfTemplate(template.id())));
                        survives |= template.id().equals(selected);
                    }
                }
            }
            // The library's parts (design 44 §3): each with how many trees name it.
            for (final String name : project.patterns().keySet()) {
                final int uses = Patterns.uses(project, name);
                rows.add(new TemplateRowData(Patterns.rowId(name), name, "patterns", "transparent", uses == 0
                        ? "unused"
                        : "used by " + uses, uses == 0));
                survives |= Patterns.rowId(name).equals(selected);
            }
        }
        getView().setRows(rows);
        if (!survives) {
            selected = null;
        }
        getView().setSelected(selected);
        enableButtons();
    }

    /**
     * The row's right-hand word: the kind of match until there is a trace; then the match count,
     * or how many places were tried for none (design 18 §5.5 - a zero is a fact about the
     * sample, not a blank).
     */
    private static String count(final TraceModel trace, final Template template) {
        final Timing timing = trace == null
                ? null
                : trace.timing(template.id());
        if (trace == null) {
            return Templates.kind(template.match());
        }
        if (timing == null || timing.getAttempts() == 0) {
            return "not tried";
        }
        if (timing.getMatched() == 0) {
            return "0 · tried " + timing.getAttempts();
        }
        // A skipping template's wins are not matches: they move the cursor past bytes and are
        // gone (D36's eater), opening no frame, binding nothing and writing nothing. Saying
        // "11" would promise all three.
        return template.consume()
                ? timing.getMatched() + " skipped"
                : String.valueOf(timing.getMatched());
    }

    private static boolean zero(final TraceModel trace, final Template template) {
        final Timing timing = trace == null
                ? null
                : trace.timing(template.id());
        return trace != null && (timing == null || timing.getMatched() == 0);
    }

    private void enableButtons() {
        final boolean editable = host != null && !host.isReadOnly();
        final boolean template = selected != null && host != null && host.template(selected) != null;
        final boolean pattern = selectedPattern() != null;
        final boolean data = SampleSource.isRow(selected);
        addButton.setEnabled(editable && !data);
        modesButton.setEnabled(host != null && host.getProject() != null && !data);
        patternButton.setEnabled(editable && host.getProject() != null && !data);
        editButton.setEnabled(editable && (template || pattern));
        removeButton.setEnabled(editable && (template || pattern));
        final int index = template
                ? indexOf(selected)
                : -1;
        upButton.setEnabled(editable && index >= 0 && neighbour(index, -1) >= 0);
        downButton.setEnabled(editable && index >= 0 && neighbour(index, 1) >= 0);
    }

    /** The index of the nearest template in the same mode in a direction, or -1: what up and down swap with. */
    private int neighbour(final int index, final int by) {
        final List<Template> templates = host.getProject().templates();
        final String mode = templates.get(index).mode();
        for (int i = index + by; i >= 0 && i < templates.size(); i += by) {
            if (Objects.equals(templates.get(i).mode(), mode)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOf(final String id) {
        final List<Template> templates = host.getProject().templates();
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void onAdd() {
        if (host.isReadOnly()) {
            return;
        }
        final Template current = host.template(selected);
        // Not a skipping template: an ordinary one, whose matches count, bind and write (D36's
        // eater is a deliberate choice, made with the tick in the dialog).
        editPresenter.read(host, Templates.create("", current == null
                ? null
                : current.mode(), false), null);
        editPresenter.show("New Template", e -> {
            if (e.isOk()) {
                final Template template = editPresenter.write();
                if (template != null) {
                    host.replace(host.withTemplate(template));
                    host.setColour(template.id(), editPresenter.getColour());
                    select(template.id(), true);
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    /** The library part the selection is, or null. */
    private String selectedPattern() {
        final String name = Patterns.nameOf(selected);
        return name != null && host != null && host.getProject() != null
               && host.getProject().patterns().containsKey(name)
                ? name
                : null;
    }

    // ---- the library's parts (design 44 §3) ----

    private void onAddPattern() {
        final Project project = host.getProject();
        if (project == null || host.isReadOnly()) {
            return;
        }
        namePrompt.show("New Pattern Part", "pattern part", Patterns.HELP, "", project.patterns().keySet(), name -> {
            host.replace(Patterns.define(project, name, new PatternNode.Regex("", null)));
            select(Patterns.rowId(name), true);
        });
    }

    private void renamePattern(final String from) {
        final Project project = host.getProject();
        namePrompt.show("Rename Pattern Part", "pattern part", Patterns.HELP, from, project.patterns().keySet(),
                name -> {
                    host.replace(Patterns.rename(project, from, name));
                    select(Patterns.rowId(name), true);
                });
    }

    private void removePattern(final String name) {
        final Project project = host.getProject();
        final int uses = Patterns.uses(project, name);
        if (uses > 0) {
            AlertEvent.fireWarn(this, "'" + name + "' is named by " + uses + (uses == 1
                    ? " tree"
                    : " trees") + "; inline or re-point those refs first", null);
            return;
        }
        ConfirmEvent.fire(this, "Remove pattern part '" + name + "'?", ok -> {
            if (ok) {
                selected = null;
                host.replace(Patterns.remove(project, name));
            }
        });
    }

    /** Open the selected template's name, mode and consume for editing; the strip's header calls this too. */
    public void editSelected() {
        if (selectedPattern() != null) {
            if (!host.isReadOnly()) {
                renamePattern(selectedPattern());
            }
            return;
        }
        final Template existing = host.template(selected);
        if (existing == null || host.isReadOnly()) {
            return;
        }
        editPresenter.read(host, existing, overrideOf(existing.id()));
        editPresenter.show("Edit Template", e -> {
            if (e.isOk()) {
                final Template template = editPresenter.write();
                if (template != null) {
                    if (!template.equals(existing)) {
                        host.replace(host.withTemplate(template));
                    }
                    host.setColour(template.id(), editPresenter.getColour());
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    /** The colour the author chose for a template, or null when it wears the palette's. */
    private String overrideOf(final String id) {
        final String colour = host.colour(id);
        final int index = indexOf(id);
        return index >= 0 && colour.equals(Templates.colour(index))
                ? null
                : colour;
    }

    private void onRemove() {
        if (selectedPattern() != null) {
            if (!host.isReadOnly()) {
                removePattern(selectedPattern());
            }
            return;
        }
        final Template existing = host.template(selected);
        if (existing == null || host.isReadOnly()) {
            return;
        }
        final String message = "Remove template '" + existing.name() + "'? Templates dispatched to from its body"
                               + " stay; the sites that dispatch to it become choices with one fewer candidate.";
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                final Project project = host.getProject();
                final List<Template> templates = new ArrayList<>(project.templates());
                templates.removeIf(t -> t.id().equals(existing.id()));
                selected = null;
                host.replace(project.withTemplates(templates));
            }
        });
    }

    private void onMove(final int by) {
        final int index = selected == null
                ? -1
                : indexOf(selected);
        final Project project = host.getProject();
        if (index < 0 || host.isReadOnly()) {
            return;
        }
        final int to = neighbour(index, by);
        if (to < 0) {
            return;
        }
        // Swap with the neighbour in the same mode: dispatch order within the mode changes, and
        // nothing about the other modes does.
        final List<Template> templates = new ArrayList<>(project.templates());
        final Template moved = templates.get(index);
        templates.set(index, templates.get(to));
        templates.set(to, moved);
        host.replace(project.withTemplates(templates));
    }

    public interface TemplatePanelView extends View, HasUiHandlers<TemplatePanelUiHandlers> {

        ButtonView addButton(Preset preset);

        /** The rows in order; the view groups them under mode headers as they come. */
        void setRows(List<TemplateRowData> rows);

        void setSelected(String id);

        /** Light a template's row, or none for null. */
        void setHot(String id);
    }
}
