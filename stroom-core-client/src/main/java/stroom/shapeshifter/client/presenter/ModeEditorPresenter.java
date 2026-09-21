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
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ButtonView;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MouseUtil;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.user.cellview.client.Column;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * The mode editor (design 18 §5.6) as Stroom's item managers are: a grid with add, edit and
 * remove. A mode exists through its templates and apply-templates sites, so a new one is
 * declared with the host until a template takes it, and shows here as waiting. A rename moves
 * the mode's templates and every site naming it; removal is of empty modes only, with a
 * warning when sites still dispatch into it. Edits are live, like the rest of the editor.
 */
public class ModeEditorPresenter extends MyPresenterWidget<PagerView> {

    private final MyDataGrid<ModeRowData> dataGrid;
    private final MultiSelectionModelImpl<ModeRowData> selectionModel;
    private final ModeNamePresenter modeName;
    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;

    private ProjectHost host;

    @Inject
    public ModeEditorPresenter(final EventBus eventBus,
                               final PagerView view,
                               final ModeNamePresenter modeName) {
        super(eventBus, view);
        this.modeName = modeName;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Modes");
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        view.setDataWidget(dataGrid);
        // The toolbar shares the pager's bar, so paging is hidden by style rather than the bar by API.
        view.asWidget().addStyleName("shapeshifter-no-paging");
        addButton = view.addButton(SvgPresets.ADD.title("New mode"));
        editButton = view.addButton(SvgPresets.EDIT.title("Rename mode: its templates and apply sites follow"));
        removeButton = view.addButton(SvgPresets.DELETE.title("Remove mode: only one with no templates"));

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
                onRename();
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
                onRename();
            }
        }));
    }

    public void show() {
        refresh();
        ShowPopupEvent.builder(this)
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
        final boolean editable = host != null && host.getProject() != null && !host.isReadOnly();
        final ModeRowData selected = selectionModel.getSelected();
        addButton.setEnabled(editable);
        editButton.setEnabled(editable && selected != null);
        removeButton.setEnabled(editable && selected != null && selected.getTemplates() == 0);
    }

    private void onAdd() {
        if (host.getProject() == null || host.isReadOnly()) {
            return;
        }
        modeName.show("New Mode", "", host.modes(), name -> {
            host.declareMode(name);
            refresh();
            selectionModel.setSelected(new ModeRowData(name, 0, 0));
        });
    }

    private void onRename() {
        final Project project = host.getProject();
        final ModeRowData existing = selectionModel.getSelected();
        if (project == null || existing == null || host.isReadOnly()) {
            return;
        }
        final String from = existing.getName();
        modeName.show("Rename Mode", from, host.modes(), name -> {
            if (existing.isWaiting()) {
                host.forgetMode(from);
                host.declareMode(name);
            } else {
                host.replace(Modes.rename(project, from, name));
            }
            refresh();
            selectionModel.setSelected(new ModeRowData(name, existing.getTemplates(), existing.getApplySites()));
        });
    }

    private void onRemove() {
        final Project project = host.getProject();
        final ModeRowData existing = selectionModel.getSelected();
        if (project == null || existing == null || host.isReadOnly() || existing.getTemplates() > 0) {
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
