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

import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.shapeshifter.shared.ShapeshifterMessage;
import stroom.util.client.DataGridUtil;

import com.google.gwt.user.cellview.client.Column;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * Design 18 §5.8: the engine's messages about the project as it stands, fed by {@code validate}
 * on every edit. A source that does not parse is the first message, so the Design tab never
 * goes silent about why it is showing the previous project.
 */
public class MessagesPresenter extends MyPresenterWidget<PagerView> {

    private final MyDataGrid<ShapeshifterMessage> dataGrid;

    @Inject
    public MessagesPresenter(final EventBus eventBus, final PagerView view) {
        super(eventBus, view);
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Messages");
        view.setDataWidget(dataGrid);
        // The toolbar shares the pager's bar, so paging is hidden by style rather than the bar by API.
        view.asWidget().addStyleName("shapeshifter-no-paging");

        final Column<ShapeshifterMessage, String> severity = DataGridUtil
                .textColumnBuilder(ShapeshifterMessage::getSeverity)
                .build();
        dataGrid.addResizableColumn(severity, "Severity", 80);
        final Column<ShapeshifterMessage, String> text = DataGridUtil
                .textWithTooltipColumnBuilder(ShapeshifterMessage::getText)
                .build();
        dataGrid.addAutoResizableColumn(text, "Message", 400);
        setMessages(null, null);
    }

    public void setMessages(final String sourceError, final List<ShapeshifterMessage> messages) {
        final List<ShapeshifterMessage> rows = new ArrayList<>();
        if (sourceError != null) {
            rows.add(new ShapeshifterMessage("SOURCE", sourceError));
        }
        if (messages != null) {
            rows.addAll(messages);
        }
        dataGrid.setRowData(0, rows);
        dataGrid.setRowCount(rows.size(), true);
    }
}
