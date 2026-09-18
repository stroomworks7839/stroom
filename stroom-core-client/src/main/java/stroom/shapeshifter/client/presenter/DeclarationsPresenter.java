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
import stroom.shapeshifter.config.Declaration;
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
 * A template's declarations (design 35): scalar, list, map or set, declared once, bound into by
 * captures and read by the body; scope flows to child templates. Add, edit and remove through
 * {@link DeclarationEditPresenter}.
 */
public class DeclarationsPresenter extends MyPresenterWidget<PagerView> {

    private final MyDataGrid<Declaration> dataGrid;
    private final MultiSelectionModelImpl<Declaration> selectionModel;
    private final DeclarationEditPresenter editPresenter;
    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;

    private ProjectHost host;
    private String templateId;

    @Inject
    public DeclarationsPresenter(final EventBus eventBus,
                                 final PagerView view,
                                 final DeclarationEditPresenter editPresenter) {
        super(eventBus, view);
        this.editPresenter = editPresenter;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Declarations");
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        view.setDataWidget(dataGrid);
        // The toolbar shares the pager's bar, so paging is hidden by style rather than the bar by API.
        view.asWidget().addStyleName("shapeshifter-no-paging");
        addButton = view.addButton(SvgPresets.ADD.title("Add declaration"));
        editButton = view.addButton(SvgPresets.EDIT.title("Edit declaration"));
        removeButton = view.addButton(SvgPresets.DELETE.title("Remove declaration"));

        final Column<Declaration, String> name = DataGridUtil
                .textColumnBuilder(Declaration::name)
                .build();
        dataGrid.addAutoResizableColumn(name, "Declaration", 140);
        final Column<Declaration, String> type = DataGridUtil
                .textColumnBuilder((Declaration d) -> d.type().name().toLowerCase(Locale.ROOT))
                .build();
        dataGrid.addResizableColumn(type, "Type", 60);
        final Column<Declaration, String> entries = DataGridUtil
                .textColumnBuilder((Declaration d) -> d.type() == Declaration.Type.MAP
                        ? d.entries().size() + " entries"
                        : "")
                .build();
        dataGrid.addResizableColumn(entries, "Entries", 80);
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
        final List<Declaration> rows = template == null
                ? List.of()
                : template.declarations();
        dataGrid.setRowData(0, rows);
        dataGrid.setRowCount(rows.size(), true);
        // Rows are values: an edit replaces the selected one, so a selection the new rows do not
        // hold is stale and must not be edited or removed again.
        final Declaration selected = selectionModel.getSelected();
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
        editPresenter.read(new Declaration("", Declaration.Type.SCALAR));
        editPresenter.show("New Declaration", e -> {
            if (e.isOk()) {
                final Declaration declaration = editPresenter.write();
                if (declaration != null) {
                    final List<Declaration> declarations = new ArrayList<>(template.declarations());
                    declarations.add(declaration);
                    host.replace(host.withTemplate(Templates.withDeclarations(template, declarations)));
                    selectionModel.setSelected(declaration);
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onEdit() {
        final Template template = host.template(templateId);
        final Declaration existing = selectionModel.getSelected();
        if (template == null || existing == null || host.isReadOnly()) {
            return;
        }
        editPresenter.read(existing);
        editPresenter.show("Edit Declaration", e -> {
            if (e.isOk()) {
                final Declaration declaration = editPresenter.write();
                if (declaration != null) {
                    final List<Declaration> declarations = new ArrayList<>(template.declarations());
                    declarations.set(declarations.indexOf(existing), declaration);
                    host.replace(host.withTemplate(Templates.withDeclarations(template, declarations)));
                    selectionModel.setSelected(declaration);
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onRemove() {
        final Template template = host.template(templateId);
        final Declaration existing = selectionModel.getSelected();
        if (template == null || existing == null || host.isReadOnly()) {
            return;
        }
        final String message = "Remove declaration '" + existing.name() + "'? Captures and body references to"
                               + " it will no longer compile.";
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                final List<Declaration> declarations = new ArrayList<>(template.declarations());
                declarations.remove(existing);
                selectionModel.clear(false);
                host.replace(host.withTemplate(Templates.withDeclarations(template, declarations)));
            }
        });
    }
}
