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

import stroom.alert.client.event.ConfirmEvent;
import stroom.shapeshifter.client.presenter.TemplatePanelPresenter.TemplatePanelView;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.svg.client.Preset;
import stroom.svg.client.SvgPresets;
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
 * The template panel (design 18 §5.6, 43 §4.1): the project itself as the topmost row, then
 * the templates grouped by mode in project order — which is dispatch order, so up and down
 * are semantics — each row a swatch, a name and, once there is a trace, its match count and
 * heat. Managed through the toolbar over the list, acting on the selection; the selection is
 * published as a value change of the selected template's id, null for the project.
 */
public class TemplatePanelPresenter
        extends MyPresenterWidget<TemplatePanelView>
        implements TemplatePanelUiHandlers, HasValueChangeHandlers<String> {

    private final TemplateEditPresenter editPresenter;
    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;
    private final ButtonView upButton;
    private final ButtonView downButton;

    private ProjectHost host;
    private String selected;

    @Inject
    public TemplatePanelPresenter(final EventBus eventBus,
                                  final TemplatePanelView view,
                                  final TemplateEditPresenter editPresenter) {
        super(eventBus, view);
        this.editPresenter = editPresenter;
        view.setUiHandlers(this);
        addButton = view.addButton(SvgPresets.ADD.title("Add template"));
        editButton = view.addButton(SvgPresets.EDIT.title("Edit template"));
        removeButton = view.addButton(SvgPresets.DELETE.title("Remove template"));
        upButton = view.addButton(SvgPresets.UP.title("Move up: earlier in dispatch order"));
        downButton = view.addButton(SvgPresets.DOWN.title("Move down: later in dispatch order"));
        enableButtons();
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
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
        rows.add(new TemplateRowData(null, project == null
                ? "project"
                : project.name(), null, "transparent", "doc", false));
        boolean survives = selected == null;
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
            for (final String mode : modes) {
                int i = 0;
                for (final Template template : project.templates()) {
                    if (Objects.equals(template.mode(), mode)) {
                        rows.add(new TemplateRowData(template.id(), template.name(), template.mode(),
                                Templates.colour(i), Templates.kind(template.match()), false));
                        survives |= template.id().equals(selected);
                    }
                    i++;
                }
            }
        }
        getView().setRows(rows);
        if (!survives) {
            selected = null;
        }
        getView().setSelected(selected);
        enableButtons();
    }

    private void enableButtons() {
        final boolean editable = host != null && !host.isReadOnly();
        final boolean template = selected != null && host != null && host.template(selected) != null;
        addButton.setEnabled(editable);
        editButton.setEnabled(editable && template);
        removeButton.setEnabled(editable && template);
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
        editPresenter.read(host.getProject(), Templates.create("", current == null
                ? null
                : current.mode(), true));
        editPresenter.show("New Template", e -> {
            if (e.isOk()) {
                final Template template = editPresenter.write();
                if (template != null) {
                    host.replace(host.withTemplate(template));
                    select(template.id(), true);
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    /** Open the selected template's name, mode and consume for editing; the strip's header calls this too. */
    public void editSelected() {
        final Template existing = host.template(selected);
        if (existing == null || host.isReadOnly()) {
            return;
        }
        editPresenter.read(host.getProject(), existing);
        editPresenter.show("Edit Template", e -> {
            if (e.isOk()) {
                final Template template = editPresenter.write();
                if (template != null) {
                    host.replace(host.withTemplate(template));
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onRemove() {
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
                host.replace(new Project(project.name(), project.version(), project.source(), templates));
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
        host.replace(new Project(project.name(), project.version(), project.source(), templates));
    }

    public interface TemplatePanelView extends View, HasUiHandlers<TemplatePanelUiHandlers> {

        ButtonView addButton(Preset preset);

        /** The rows in order; the view groups them under mode headers as they come. */
        void setRows(List<TemplateRowData> rows);

        void setSelected(String id);
    }
}
