/*
 * Copyright 2016-2026 Crown Copyright
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

import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.shapeshifter.shared.RoutingFields;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ButtonView;
import stroom.widget.menu.client.presenter.Item;
import stroom.widget.menu.client.presenter.MenuBuilder;
import stroom.widget.menu.client.presenter.MenuPresenter;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupPosition;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * The learning key as an ordered list (A29): the fields a learned rule binds on and the chain question
 * sees, drawn from {@link RoutingFields#NAMES}, in the order the prompt will list them. Add offers only
 * the fields not yet in the key, so the key cannot hold a name the store would refuse or the same field
 * twice; the key's order is the operator's, kept by position rather than stored on the entry.
 */
public class LearningKeyPresenter extends MyPresenterWidget<PagerView> {

    private final MyDataGrid<KeyRow> dataGrid;
    private final MultiSelectionModelImpl<KeyRow> selectionModel;
    private final MenuPresenter menuPresenter;
    private final ButtonView addButton;
    private final ButtonView deleteButton;
    private final ButtonView moveUpButton;
    private final ButtonView moveDownButton;
    private final List<String> fields = new ArrayList<>();
    private final List<KeyRow> rows = new ArrayList<>();
    private ShapeshifterAiSettingsUiHandlers uiHandlers;
    private boolean readOnly;

    @Inject
    public LearningKeyPresenter(final EventBus eventBus,
                                final PagerView view,
                                final MenuPresenter menuPresenter) {
        super(eventBus, view);
        this.menuPresenter = menuPresenter;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Learning Key");
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);

        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((KeyRow row) ->
                                SafeHtmlUtils.fromString(Integer.toString(row.number())))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("#")
                        .withToolTip("The prompt lists the key's values in this order, strongest signal first.")
                        .rightAligned()
                        .build(),
                40);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((KeyRow row) -> SafeHtmlUtils.fromString(row.field())).build(),
                DataGridUtil.headingBuilder("Field")
                        .withToolTip("A meta field, a receipt header, or the record shape signature.")
                        .build(),
                300);

        addButton = view.addButton(SvgPresets.ADD.title("Add a field to the key"));
        deleteButton = view.addButton(SvgPresets.DELETE.title("Remove the selected field"));
        moveUpButton = view.addButton(SvgPresets.UP.title("Move the selected field up"));
        moveDownButton = view.addButton(SvgPresets.DOWN.title("Move the selected field down"));
        updateButtons();
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(addButton.addClickHandler(this::onAdd));
        registerHandler(deleteButton.addClickHandler(this::onDelete));
        registerHandler(moveUpButton.addClickHandler(event -> move(-1)));
        registerHandler(moveDownButton.addClickHandler(event -> move(1)));
        registerHandler(selectionModel.addSelectionHandler(event -> updateButtons()));
    }

    public void setUiHandlers(final ShapeshifterAiSettingsUiHandlers uiHandlers) {
        this.uiHandlers = uiHandlers;
    }

    public void read(final List<String> learningKey) {
        fields.clear();
        fields.addAll(learningKey);
        selectionModel.clear();
        update();
    }

    public List<String> write() {
        return new ArrayList<>(fields);
    }

    public void setReadOnly(final boolean readOnly) {
        this.readOnly = readOnly;
        updateButtons();
    }

    private String selected() {
        final KeyRow row = selectionModel.getSelected();
        return row == null
                ? null
                : row.field();
    }

    /**
     * Add is a menu of what remains rather than a dialog: one click, no free text, nothing to validate.
     */
    private void onAdd(final ClickEvent event) {
        if (readOnly) {
            return;
        }
        final MenuBuilder menu = MenuBuilder.builder();
        for (final String field : RoutingFields.NAMES) {
            if (!fields.contains(field)) {
                menu.withSimpleMenuItem(item -> item
                        .text(field)
                        .command(() -> append(field)));
            }
        }
        final List<Item> items = menu.build();
        if (items.isEmpty()) {
            return;
        }
        final NativeEvent nativeEvent = event.getNativeEvent();
        menuPresenter.setData(items);
        ShowPopupEvent.builder(menuPresenter)
                .popupType(PopupType.POPUP)
                .popupPosition(new PopupPosition(nativeEvent.getClientX(), nativeEvent.getClientY()))
                .fire();
    }

    private void append(final String field) {
        fields.add(field);
        update();
        select(field);
        changed();
    }

    private void onDelete(final ClickEvent event) {
        final String field = selected();
        if (!readOnly && field != null) {
            fields.remove(field);
            selectionModel.clear();
            update();
            changed();
        }
    }

    private void move(final int by) {
        final String field = selected();
        if (!readOnly && field != null) {
            final int index = fields.indexOf(field);
            final int target = index + by;
            if (target >= 0 && target < fields.size()) {
                fields.remove(index);
                fields.add(target, field);
                update();
                select(field);
                changed();
            }
        }
    }

    private void select(final String field) {
        selectionModel.clear();
        final int index = fields.indexOf(field);
        if (index >= 0) {
            selectionModel.setSelected(rows.get(index));
        }
    }

    private void update() {
        rows.clear();
        for (int i = 0; i < fields.size(); i++) {
            rows.add(new KeyRow(i + 1, fields.get(i)));
        }
        dataGrid.setRowData(0, rows);
        dataGrid.setRowCount(rows.size());
        updateButtons();
    }

    private void updateButtons() {
        final boolean editable = !readOnly;
        final String field = selected();
        final int index = field == null
                ? -1
                : fields.indexOf(field);
        addButton.setEnabled(editable && fields.size() < RoutingFields.NAMES.size());
        deleteButton.setEnabled(editable && field != null);
        moveUpButton.setEnabled(editable && index > 0);
        moveDownButton.setEnabled(editable && index >= 0 && index < fields.size() - 1);
    }

    private void changed() {
        if (uiHandlers != null) {
            uiHandlers.onChange();
        }
    }


    // --------------------------------------------------------------------------------


    /**
     * A field with its position in the key. Rows are rebuilt on every change, so a row's identity is its
     * position and the grid is told which row to select by index.
     */
    public static final class KeyRow {

        private final int number;
        private final String field;

        private KeyRow(final int number, final String field) {
            this.number = number;
            this.field = field;
        }

        public int number() {
            return number;
        }

        public String field() {
            return field;
        }
    }
}
