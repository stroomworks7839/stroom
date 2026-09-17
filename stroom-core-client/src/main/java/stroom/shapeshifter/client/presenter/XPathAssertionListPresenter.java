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

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ButtonView;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The assertions of a business-rules scorer as an editable list: name and XPath, add/edit/remove.
 */
public class XPathAssertionListPresenter extends MyPresenterWidget<PagerView> {

    private final MyDataGrid<XPathAssertion> dataGrid;
    private final MultiSelectionModelImpl<XPathAssertion> selectionModel;
    private final Provider<XPathAssertionPresenter> editPresenterProvider;
    private final List<XPathAssertion> assertions = new ArrayList<>();
    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView deleteButton;
    private boolean readOnly;

    @Inject
    public XPathAssertionListPresenter(final EventBus eventBus,
                                       final PagerView view,
                                       final Provider<XPathAssertionPresenter> editPresenterProvider) {
        super(eventBus, view);
        this.editPresenterProvider = editPresenterProvider;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Assertions");
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((XPathAssertion row) -> SafeHtmlUtils.fromString(row.getName()))
                        .build(),
                DataGridUtil.headingBuilder("Name").build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((XPathAssertion row) -> SafeHtmlUtils.fromString(row.getXpath()))
                        .build(),
                DataGridUtil.headingBuilder("XPath")
                        .withToolTip("Must evaluate true for every record.")
                        .build(),
                500);
        addButton = view.addButton(SvgPresets.ADD.title("Add assertion"));
        editButton = view.addButton(SvgPresets.EDIT.title("Edit selected assertion"));
        deleteButton = view.addButton(SvgPresets.DELETE.title("Remove selected assertion"));
        updateButtons();
    }

    @Override
    protected void onBind() {
        registerHandler(addButton.addClickHandler(event -> {
            if (!readOnly) {
                show(new XPathAssertion("", ""), assertion -> {
                    assertions.add(assertion);
                    update();
                });
            }
        }));
        registerHandler(editButton.addClickHandler(event -> edit()));
        registerHandler(deleteButton.addClickHandler(event -> {
            final XPathAssertion selected = selectionModel.getSelected();
            if (!readOnly && selected != null) {
                ConfirmEvent.fire(this, "Remove the assertion '" + selected.getName() + "'?", ok -> {
                    if (ok) {
                        assertions.remove(selected);
                        selectionModel.clear();
                        update();
                    }
                });
            }
        }));
        registerHandler(selectionModel.addSelectionHandler(event -> {
            if (event.getSelectionType().isDoubleSelect()) {
                edit();
            }
            updateButtons();
        }));
        super.onBind();
    }

    private void edit() {
        final XPathAssertion existing = selectionModel.getSelected();
        if (!readOnly && existing != null) {
            show(existing, assertion -> {
                assertions.set(assertions.indexOf(existing), assertion);
                update();
            });
        }
    }

    private void show(final XPathAssertion assertion, final Consumer<XPathAssertion> onOk) {
        final XPathAssertionPresenter presenter = editPresenterProvider.get();
        presenter.read(assertion);
        ShowPopupEvent.builder(presenter)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(700, 250))
                .caption("Assertion")
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                    } else if (presenter.write().getXpath().isEmpty()) {
                        AlertEvent.fireError(this, "An assertion needs an XPath.", e::reset);
                    } else {
                        onOk.accept(presenter.write());
                        e.hide();
                    }
                })
                .fire();
    }

    public void read(final List<XPathAssertion> assertions, final boolean readOnly) {
        this.readOnly = readOnly;
        this.assertions.clear();
        this.assertions.addAll(assertions);
        selectionModel.clear();
        update();
    }

    public List<XPathAssertion> write() {
        return new ArrayList<>(assertions);
    }

    private void update() {
        dataGrid.setRowData(0, assertions);
        dataGrid.setRowCount(assertions.size());
        updateButtons();
    }

    private void updateButtons() {
        final boolean selected = selectionModel.getSelected() != null;
        addButton.setEnabled(!readOnly);
        editButton.setEnabled(!readOnly && selected);
        deleteButton.setEnabled(!readOnly && selected);
    }
}
