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

package stroom.shapeshifter.client.component;

import stroom.alert.client.event.ConfirmEvent;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.config.Template;
import stroom.svg.shared.SvgImage;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Controller;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.datagrid.client.PagerPanel;
import stroom.widget.util.client.MouseUtil;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.user.cellview.client.Column;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A template's captures: what binds a pattern's group or label — or a selection over the values
 * in scope — into a declared name, with a cast. Add, edit and remove through
 * {@link CaptureEditController}.
 */
public class CapturesController extends Controller {

    private final PagerPanel view;

    private final DataGrid<CaptureBinding> dataGrid;
    private final MultiSelectionModelImpl<CaptureBinding> selectionModel;
    private final CaptureEditController editPresenter;
    private final ToolbarButton addButton;
    private final ToolbarButton editButton;
    private final ToolbarButton removeButton;

    private ProjectHost host;
    private String templateId;

    @Inject
    public CapturesController(final EventBus eventBus,
                             final PagerPanel view,
                             final CaptureEditController editPresenter) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        this.editPresenter = own(editPresenter);
        dataGrid = new DataGrid<>();
        dataGrid.setTableName("Captures");
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        view.setDataWidget(dataGrid);
        // The toolbar shares the pager's bar, so paging is hidden by style rather than the bar by API.
        view.asWidget().addStyleName("shapeshifter-no-paging");
        addButton = view.getToolbar().button(SvgImage.ADD, "Add capture", () -> {
        });
        editButton = view.getToolbar().button(SvgImage.EDIT, "Edit capture", () -> {
        });
        removeButton = view.getToolbar().button(SvgImage.DELETE, "Remove capture", () -> {
        });

        final Column<CaptureBinding, String> name = DataGridUtil
                .textColumnBuilder(CaptureBinding::name)
                .build();
        dataGrid.addResizableColumn(name, "Capture", 120);
        final Column<CaptureBinding, String> source = DataGridUtil
                .textColumnBuilder((CaptureBinding c) -> Templates.describe(c.select()))
                .build();
        dataGrid.addAutoResizableColumn(source, "From", 140);
        final Column<CaptureBinding, String> cast = DataGridUtil
                .textColumnBuilder((CaptureBinding c) -> c.as() == null
                        ? ""
                        : c.as().name().toLowerCase(Locale.ROOT))
                .build();
        dataGrid.addResizableColumn(cast, "As", 70);
        enableButtons();
        own(addButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onAdd();
            }
        }));
        own(editButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onEdit();
            }
        }));
        own(removeButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onRemove();
            }
        }));
        own(selectionModel.addSelectionHandler(event -> {
            enableButtons();
            if (event.getSelectionType().isDoubleSelect()) {
                onEdit();
            }
        }));

    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }


    public void setTemplate(final String id) {
        this.templateId = id;
        final Template template = host.template(id);
        final List<CaptureBinding> rows = template == null
                ? List.of()
                : template.captures();
        dataGrid.setRowData(0, rows);
        dataGrid.setRowCount(rows.size(), true);
        // Rows are values: an edit replaces the selected one, so a selection the new rows do not
        // hold is stale and must not be edited or removed again.
        final CaptureBinding selected = selectionModel.getSelected();
        if (selected != null && !rows.contains(selected)) {
            selectionModel.clear(false);
        }
        enableButtons();
    }

    private void enableButtons() {
        final boolean editable = host != null && !host.isReadOnly() && templateId != null;
        final boolean selected = selectionModel.getSelected() != null;
        addButton.setEnabled(editable);
        editButton.setEnabled(editable && selected);
        removeButton.setEnabled(editable && selected);
    }

    private void onAdd() {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        editPresenter.read(template, new CaptureBinding("", new CaptureSource.Group(1), null));
        editPresenter.show("New Capture", e -> {
            if (e.isOk()) {
                final CaptureBinding capture = editPresenter.write();
                if (capture != null) {
                    final List<CaptureBinding> captures = new ArrayList<>(template.captures());
                    captures.add(capture);
                    host.replace(host.withTemplate(Templates.withCaptures(template, captures)));
                    selectionModel.setSelected(capture);
                    e.hide();
                } else {
                    // Nothing was written, so the dialog stays open — and its OK button has to come back
                    // out of its busy state, or the alert leaves it spinning for good.
                    e.reset();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onEdit() {
        final Template template = host.template(templateId);
        final CaptureBinding existing = selectionModel.getSelected();
        if (template == null || existing == null || host.isReadOnly()) {
            return;
        }
        editPresenter.read(template, existing);
        editPresenter.show("Edit Capture", e -> {
            if (e.isOk()) {
                final CaptureBinding capture = editPresenter.write();
                if (capture != null) {
                    final List<CaptureBinding> captures = new ArrayList<>(template.captures());
                    captures.set(captures.indexOf(existing), capture);
                    host.replace(host.withTemplate(Templates.withCaptures(template, captures)));
                    selectionModel.setSelected(capture);
                    e.hide();
                } else {
                    // Nothing was written, so the dialog stays open — and its OK button has to come back
                    // out of its busy state, or the alert leaves it spinning for good.
                    e.reset();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onRemove() {
        final Template template = host.template(templateId);
        final CaptureBinding existing = selectionModel.getSelected();
        if (template == null || existing == null || host.isReadOnly()) {
            return;
        }
        ConfirmEvent.fire(this, "Remove capture '" + existing.name() + "'?", ok -> {
            if (ok) {
                final List<CaptureBinding> captures = new ArrayList<>(template.captures());
                captures.remove(existing);
                selectionModel.clear(false);
                host.replace(host.withTemplate(Templates.withCaptures(template, captures)));
            }
        });
    }
}
