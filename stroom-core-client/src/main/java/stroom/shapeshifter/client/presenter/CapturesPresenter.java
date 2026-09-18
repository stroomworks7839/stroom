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
import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.config.Template;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MouseUtil;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.user.cellview.client.Column;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A template's captures: what binds a pattern's group or label — or a selection over the values
 * in scope — into a declared name, with a cast. Add, edit and remove through
 * {@link CaptureEditPresenter}.
 */
public class CapturesPresenter extends MyPresenterWidget<PagerView> {

    private final MyDataGrid<CaptureBinding> dataGrid;
    private final MultiSelectionModelImpl<CaptureBinding> selectionModel;
    private final CaptureEditPresenter editPresenter;
    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;

    private ProjectHost host;
    private String templateId;

    @Inject
    public CapturesPresenter(final EventBus eventBus,
                             final PagerView view,
                             final CaptureEditPresenter editPresenter) {
        super(eventBus, view);
        this.editPresenter = editPresenter;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Captures");
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        view.setDataWidget(dataGrid);
        // The toolbar shares the pager's bar, so paging is hidden by style rather than the bar by API.
        view.asWidget().addStyleName("shapeshifter-no-paging");
        addButton = view.addButton(SvgPresets.ADD.title("Add capture"));
        editButton = view.addButton(SvgPresets.EDIT.title("Edit capture"));
        removeButton = view.addButton(SvgPresets.DELETE.title("Remove capture"));

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
        registerHandler(selectionModel.addSelectionHandler(event -> {
            enableButtons();
            if (event.getSelectionType().isDoubleSelect()) {
                onEdit();
            }
        }));
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
