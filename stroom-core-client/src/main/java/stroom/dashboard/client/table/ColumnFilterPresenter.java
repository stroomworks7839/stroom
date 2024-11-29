/*
 * Copyright 2017 Crown Copyright
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

package stroom.dashboard.client.table;

import stroom.dashboard.client.table.ColumnFilterPresenter.ColumnFilterView;
import stroom.query.api.v2.Column;
import stroom.query.api.v2.ColumnFilter;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.function.BiConsumer;

public class ColumnFilterPresenter extends MyPresenterWidget<ColumnFilterView> {

    @Inject
    public ColumnFilterPresenter(final EventBus eventBus, final ColumnFilterView view) {
        super(eventBus, view);
    }

    public void show(final Column column,
                     final BiConsumer<Column, Column> columnChangeConsumer) {

        String expression = "";

        if (column.getColumnFilter() != null) {
            if (column.getColumnFilter().getFilter() != null) {
                expression = column.getColumnFilter().getFilter();
            }
        }

        getView().setFilter(expression);

        final PopupSize popupSize = PopupSize.resizable(600, 300);
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(popupSize)
                .caption("Filter '" + column.getName() + "'")
                .modal(true)
                .onShow(e -> getView().focus())
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        final ColumnFilter filter = getColumnFilter();
                        if ((filter == null && column.getFilter() != null)
                            || (filter != null && !filter.equals(column.getColumnFilter()))) {
                            columnChangeConsumer.accept(column, column.copy().columnFilter(filter).build());
                        }
                    }
                    e.hide();
                })
                .fire();
    }

    private ColumnFilter getColumnFilter() {
        String expression = null;
        if (getView().getFilter() != null && getView().getFilter().trim().length() > 0) {
            expression = getView().getFilter().trim();
        }

        ColumnFilter filter = null;
        if (expression != null) {
            filter = new ColumnFilter(expression);
        }
        return filter;
    }

    public interface ColumnFilterView extends View, Focus {

        String getFilter();

        void setFilter(String filter);
    }
}
