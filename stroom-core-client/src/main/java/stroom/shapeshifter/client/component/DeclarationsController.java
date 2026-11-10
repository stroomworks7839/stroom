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
import stroom.shapeshifter.config.Declaration;
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
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A template's declarations (design 35): scalar, list, map or set, declared once, bound into by
 * captures and read by the body; scope flows to child templates. Add, edit and remove through
 * {@link DeclarationEditController}.
 */
public class DeclarationsController extends Controller {

    private final PagerPanel view;

    private final DataGrid<Declaration> dataGrid;
    private final MultiSelectionModelImpl<Declaration> selectionModel;
    private final Provider<DeclarationEditController> editPresenterProvider;
    private final ToolbarButton addButton;
    private final ToolbarButton editButton;
    private final ToolbarButton removeButton;

    private ProjectHost host;
    private String templateId;

    @Inject
    public DeclarationsController(final EventBus eventBus,
                                 final PagerPanel view,
                                 final Provider<DeclarationEditController> editPresenterProvider) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        this.editPresenterProvider = editPresenterProvider;
        dataGrid = new DataGrid<>();
        dataGrid.setTableName("Declarations");
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        view.setDataWidget(dataGrid);
        // The toolbar shares the pager's bar, so paging is hidden by style rather than the bar by API.
        view.asWidget().addStyleName("shapeshifter-no-paging");
        addButton = view.getToolbar().button(SvgImage.ADD, "Add declaration", () -> {
        });
        editButton = view.getToolbar().button(SvgImage.EDIT, "Edit declaration", () -> {
        });
        removeButton = view.getToolbar().button(SvgImage.DELETE, "Remove declaration", () -> {
        });

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
        final DeclarationEditController editor = editPresenterProvider.get();
        editor.read(new Declaration("", Declaration.Type.SCALAR));
        editor.show(this, "New Declaration", e -> {
            if (e.isOk()) {
                final Declaration declaration = editor.write();
                if (declaration != null) {
                    final List<Declaration> declarations = new ArrayList<>(template.declarations());
                    declarations.add(declaration);
                    host.replace(host.withTemplate(Templates.withDeclarations(template, declarations)));
                    selectionModel.setSelected(declaration);
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
        final Declaration existing = selectionModel.getSelected();
        if (template == null || existing == null || host.isReadOnly()) {
            return;
        }
        final DeclarationEditController editor = editPresenterProvider.get();
        editor.read(existing);
        editor.show(this, "Edit Declaration", e -> {
            if (e.isOk()) {
                final Declaration declaration = editor.write();
                if (declaration != null) {
                    final List<Declaration> declarations = new ArrayList<>(template.declarations());
                    declarations.set(declarations.indexOf(existing), declaration);
                    host.replace(host.withTemplate(Templates.withDeclarations(template, declarations)));
                    selectionModel.setSelected(declaration);
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
