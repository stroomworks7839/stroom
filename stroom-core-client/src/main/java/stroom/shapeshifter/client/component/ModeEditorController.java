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
import stroom.shapeshifter.client.model.ModeRowData;
import stroom.shapeshifter.client.model.Modes;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.Project;
import stroom.svg.shared.SvgImage;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.datagrid.client.PagerPanel;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;
import stroom.widget.util.client.MouseUtil;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.user.cellview.client.Column;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;

/**
 * The mode editor (design 18 §5.6) as Stroom's item managers are: a grid with add, edit and
 * remove. A mode exists through its templates and apply-templates sites, so a new one is
 * declared with the host until a template takes it, and shows here as waiting. A rename moves
 * the mode's templates and every site naming it; removal is of empty modes only, with a
 * warning when sites still dispatch into it. Edits are live, like the rest of the editor.
 */
public class ModeEditorController extends Controller {

    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    private final PagerPanel view;

    private final DataGrid<ModeRowData> dataGrid;
    private final MultiSelectionModelImpl<ModeRowData> selectionModel;
    private final Provider<NameEditorController> namePromptProvider;
    private final ToolbarButton addButton;
    private final ToolbarButton editButton;
    private final ToolbarButton removeButton;

    private ProjectHost host;

    @Inject
    public ModeEditorController(final EventBus eventBus,
                               final PagerPanel view,
                               final Provider<NameEditorController> namePromptProvider) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        watch(Contexts.READ_ONLY, readOnly -> this.readOnly = readOnly);
        this.namePromptProvider = namePromptProvider;
        dataGrid = new DataGrid<>();
        dataGrid.setTableName("Modes");
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        view.setDataWidget(dataGrid);
        // The toolbar shares the pager's bar, so paging is hidden by style rather than the bar by API.
        // Everything it holds is shown at once, so it pages nothing; its actions
        // share the pager's bar, which stays.
        view.setPagingVisible(false);
        addButton = view.getToolbar().button(SvgImage.ADD, "New mode", () -> {
        });
        editButton = view.getToolbar().button(SvgImage.EDIT,
                "Rename mode: its templates and apply sites follow",
                () -> {
                });
        removeButton = view.getToolbar().button(SvgImage.DELETE, "Remove mode: only one with no templates", () -> {
        });

        final Column<ModeRowData, String> name = DataGridUtil
                .textColumnBuilder(ModeRowData::getName)
                .build();
        dataGrid.addAutoResizableColumn(name, "Mode", 160);
        final Column<ModeRowData, String> templates = DataGridUtil
                .textColumnBuilder((ModeRowData row) -> row.isWaiting()
                        ? "waiting for a template"
                        : String.valueOf(row.getTemplates()))
                .build();
        dataGrid.addResizableColumn(templates, "Templates", 150);
        final Column<ModeRowData, String> sites = DataGridUtil
                .textColumnBuilder((ModeRowData row) -> row.isWaiting()
                        ? ""
                        : String.valueOf(row.getApplySites()))
                .build();
        dataGrid.addResizableColumn(sites, "Apply sites", 90);
        enableButtons();
        own(addButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onAdd();
            }
        }));
        own(editButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onRename();
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
                onRename();
            }
        }));

    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }


    /// @param opener What shows it, which owns it until it is hidden.
    public void show(final HasControllerNode opener) {
        refresh();
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.CLOSE_DIALOG)
                .popupSize(PopupSize.resizable(520, 360))
                .caption("Modes")
                .onHideRequest(e -> e.hide())
                .fire();
    }

    private void refresh() {
        final Project project = host.getProject();
        final List<ModeRowData> rows = new ArrayList<>();
        if (project != null) {
            for (final String mode : host.modes()) {
                rows.add(new ModeRowData(mode, Modes.templateCount(project, mode),
                        Modes.applySiteCount(project, mode)));
            }
        }
        dataGrid.setRowData(0, rows);
        dataGrid.setRowCount(rows.size(), true);
        // Rows are values: after an edit the selection is stale unless a row still says the same.
        final ModeRowData selected = selectionModel.getSelected();
        if (selected != null && !rows.contains(selected)) {
            selectionModel.clear(false);
        }
        enableButtons();
    }

    private void enableButtons() {
        final boolean editable = host != null && host.getProject() != null && !readOnly;
        final ModeRowData selected = selectionModel.getSelected();
        addButton.setEnabled(editable);
        editButton.setEnabled(editable && selected != null);
        removeButton.setEnabled(editable && selected != null && selected.getTemplates() == 0);
    }

    private void onAdd() {
        if (host.getProject() == null || readOnly) {
            return;
        }
        namePromptProvider.get().prompt(this,
                "New Mode", "mode", Modes.HELP, "", host.modes(), name -> {
                    host.declareMode(name);
                    refresh();
                    selectionModel.setSelected(new ModeRowData(name, 0, 0));
                });
    }

    private void onRename() {
        final Project project = host.getProject();
        final ModeRowData existing = selectionModel.getSelected();
        if (project == null || existing == null || readOnly) {
            return;
        }
        final String from = existing.getName();
        namePromptProvider.get().prompt(this,
                "Rename Mode", "mode", Modes.HELP, from, host.modes(), name -> {
                    if (existing.isWaiting()) {
                        host.forgetMode(from);
                        host.declareMode(name);
                    } else {
                        host.replace(Modes.rename(project, from, name));
                    }
                    refresh();
                    selectionModel.setSelected(new ModeRowData(name,
                            existing.getTemplates(),
                            existing.getApplySites()));
                });
    }

    private void onRemove() {
        final Project project = host.getProject();
        final ModeRowData existing = selectionModel.getSelected();
        if (project == null || existing == null || readOnly || existing.getTemplates() > 0) {
            return;
        }
        final String mode = existing.getName();
        if (existing.isWaiting()) {
            host.forgetMode(mode);
            selectionModel.clear(false);
            refresh();
            return;
        }
        final int sites = existing.getApplySites();
        final String message = sites == 0
                ? "Remove mode '" + mode + "'?"
                : "Remove mode '" + mode + "'? " + sites + (sites == 1
                        ? " apply-templates site still dispatches"
                        : " apply-templates sites still dispatch") + " into it; those become dispatches into the"
                  + " root, and the messages will say so.";
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                selectionModel.clear(false);
                host.replace(Modes.removeSites(project, mode));
                refresh();
            }
        });
    }
}
