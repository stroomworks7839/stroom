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
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MouseUtil;
import stroom.widget.util.client.MultiSelectEvent;
import stroom.widget.util.client.MultiSelectionModelImpl;
import stroom.widget.util.client.SelectionType;

import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.user.cellview.client.Column;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * The template panel (design 18 §5.6): the project itself as the topmost row — its name, its source
 * settings behind it — then
 * the templates in project order, which is dispatch order (D34's ordered choice), so the up and
 * down buttons are semantics. Managed through the toolbar acting on the selection: add, edit and
 * remove open or confirm through {@link TemplateEditPresenter}. Each row carries its swatch,
 * assigned stably from the palette by position; mode and match kind beside the name.
 */
public class TemplatePanelPresenter extends MyPresenterWidget<PagerView> {

    private final MyDataGrid<Row> dataGrid;
    private final MultiSelectionModelImpl<Row> selectionModel;
    private final TemplateEditPresenter editPresenter;
    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;
    private final ButtonView upButton;
    private final ButtonView downButton;

    private ProjectHost host;

    @Inject
    public TemplatePanelPresenter(final EventBus eventBus,
                                  final PagerView view,
                                  final TemplateEditPresenter editPresenter) {
        super(eventBus, view);
        this.editPresenter = editPresenter;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Templates");
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        view.setDataWidget(dataGrid);
        view.setPagerVisible(false);

        addButton = view.addButton(SvgPresets.ADD.title("Add template"));
        editButton = view.addButton(SvgPresets.EDIT.title("Edit template"));
        removeButton = view.addButton(SvgPresets.DELETE.title("Remove template"));
        upButton = view.addButton(SvgPresets.UP.title("Move up: earlier in dispatch order"));
        downButton = view.addButton(SvgPresets.DOWN.title("Move down: later in dispatch order"));

        final Column<Row, String> swatch = DataGridUtil
                .colourSwatchColumnBuilder((Row row) -> row.colour)
                .build();
        dataGrid.addColumn(swatch, "", 24);
        final Column<Row, String> name = DataGridUtil
                .textColumnBuilder((Row row) -> row.name)
                .build();
        dataGrid.addAutoResizableColumn(name, "Template", 120);
        final Column<Row, String> mode = DataGridUtil
                .textColumnBuilder((Row row) -> row.mode)
                .build();
        dataGrid.addResizableColumn(mode, "Mode", 70);
        final Column<Row, String> kind = DataGridUtil
                .textColumnBuilder((Row row) -> row.kind)
                .build();
        dataGrid.addResizableColumn(kind, "Match", 60);
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
                onEdit();
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
        registerHandler(selectionModel.addSelectionHandler(event -> {
            enableButtons();
            if (event.getSelectionType().isDoubleSelect()) {
                onEdit();
            }
        }));
    }

    public HandlerRegistration addSelectionHandler(final MultiSelectEvent.Handler handler) {
        return selectionModel.addSelectionHandler(handler);
    }

    /** The selected template's id, or null when the source row (or nothing) is selected. */
    public String getSelectedTemplateId() {
        final Row row = selectionModel.getSelected();
        return row == null
                ? null
                : row.id;
    }

    /** Re-read the rows from the host's project, keeping the selection by id where it survives. */
    public void refresh() {
        final Project project = host.getProject();
        final String selected = getSelectedTemplateId();
        final List<Row> rows = new ArrayList<>();
        Row reselect = null;
        rows.add(new Row(null, project == null
                ? "project"
                : project.name(), "", "project", "transparent"));
        if (project != null) {
            int i = 0;
            for (final Template template : project.templates()) {
                final Row row = new Row(template.id(), template.name(),
                        template.mode() == null
                                ? ""
                                : template.mode(),
                        Templates.kind(template.match()), Templates.colour(i++));
                if (row.id.equals(selected)) {
                    reselect = row;
                }
                rows.add(row);
            }
        }
        dataGrid.setRowData(0, rows);
        dataGrid.setRowCount(rows.size(), true);
        if (reselect != null) {
            selectionModel.setSelected(reselect, new SelectionType(), false);
        } else if (selected != null) {
            selectionModel.clear(false);
        }
        enableButtons();
    }

    private void enableButtons() {
        final boolean editable = host != null && !host.isReadOnly();
        final Row row = selectionModel.getSelected();
        final boolean template = row != null && row.id != null;
        addButton.setEnabled(editable);
        editButton.setEnabled(editable && template);
        removeButton.setEnabled(editable && template);
        final int index = template
                ? indexOf(row.id)
                : -1;
        upButton.setEnabled(editable && index > 0);
        downButton.setEnabled(editable && index >= 0 && index < host.getProject().templates().size() - 1);
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
        final Row row = selectionModel.getSelected();
        final Template selected = row == null
                ? null
                : host.template(row.id);
        editPresenter.read(host.getProject(), Templates.create("", selected == null
                ? null
                : selected.mode(), true));
        editPresenter.show("New Template", e -> {
            if (e.isOk()) {
                final Template template = editPresenter.write();
                if (template != null) {
                    host.replace(host.withTemplate(template));
                    select(template.id());
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onEdit() {
        final Template existing = host.template(getSelectedTemplateId());
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
        final Template existing = host.template(getSelectedTemplateId());
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
                selectionModel.clear(false);
                host.replace(new Project(project.name(), project.version(), project.source(), templates));
            }
        });
    }

    private void onMove(final int by) {
        final String id = getSelectedTemplateId();
        final int index = id == null
                ? -1
                : indexOf(id);
        final Project project = host.getProject();
        if (index < 0 || host.isReadOnly()) {
            return;
        }
        final int to = index + by;
        if (to < 0 || to >= project.templates().size()) {
            return;
        }
        final List<Template> templates = new ArrayList<>(project.templates());
        final Template moved = templates.remove(index);
        templates.add(to, moved);
        host.replace(new Project(project.name(), project.version(), project.source(), templates));
        select(id);
    }

    private void select(final String id) {
        for (final Row row : dataGrid.getVisibleItems()) {
            if (id.equals(row.id)) {
                selectionModel.setSelected(row);
                return;
            }
        }
    }

    /** One row: the source (id null) or a template. */
    private static final class Row {

        private final String id;
        private final String name;
        private final String mode;
        private final String kind;
        private final String colour;

        private Row(final String id, final String name, final String mode, final String kind, final String colour) {
            this.id = id;
            this.name = name;
            this.mode = mode;
            this.kind = kind;
            this.colour = colour;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof Row && java.util.Objects.equals(((Row) o).id, id);
        }

        @Override
        public int hashCode() {
            return id == null
                    ? 0
                    : id.hashCode();
        }
    }
}
