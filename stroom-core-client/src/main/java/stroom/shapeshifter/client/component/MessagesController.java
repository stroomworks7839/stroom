/*
 * Copyright 2026 Crown Copyright
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

import stroom.shapeshifter.shared.ShapeshifterMessage;
import stroom.util.client.DataGridUtil;
import stroom.widget.component.client.Controller;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.datagrid.client.PagerPanel;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.user.cellview.client.Column;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongConsumer;

/// Design 18 §5.8: the engine's messages about the project as it stands, fed by `validate`
/// on every edit. A source that does not parse is the first message, so the Design tab never
/// goes silent about why it is showing the previous project.
public class MessagesController extends Controller {

    private PagerPanel view;

    private final DataGrid<ShapeshifterMessage> dataGrid;
    private final MultiSelectionModelImpl<ShapeshifterMessage> selectionModel;
    private LongConsumer onGoTo;

    @Inject
    public MessagesController(final EventBus eventBus) {
        super(eventBus);
        dataGrid = new DataGrid<>();
        dataGrid.setTableName("Messages");
        selectionModel = dataGrid.addDefaultSelectionModel(false);
    }

    /// Attaches this to a pager, e.g. one a template declares. The pager is then this
    /// controller's widget.
    ///
    /// @param view The pager.
    /// @return This, for its owner to keep.
    public MessagesController attach(final PagerPanel view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setDataWidget(dataGrid);
        // Everything it holds is shown at once, so it pages nothing; its actions share
        // the pager's bar, which stays (setPagerVisible would take the bar as well).
        view.setPagingVisible(false);

        final Column<ShapeshifterMessage, String> severity = DataGridUtil
                .textColumnBuilder(ShapeshifterMessage::getSeverity)
                .build();
        dataGrid.addResizableColumn(severity, "Severity", 80);
        final Column<ShapeshifterMessage, String> text = DataGridUtil
                .textWithTooltipColumnBuilder(ShapeshifterMessage::getText)
                .build();
        dataGrid.addAutoResizableColumn(text, "Message", 400);
        setMessages(null, null);
        // A message from a run names its frame: selecting it goes there (design 18 §5.8).
        own(selectionModel.addSelectionHandler(event -> {
            final ShapeshifterMessage selected = selectionModel.getSelected();
            if (selected != null && selected.getFrameId() != ShapeshifterMessage.NO_FRAME && onGoTo != null) {
                onGoTo.accept(selected.getFrameId());
            }
        }));
        return this;

    }


    /// What a selected message's frame does: the root moves the cursor there.
    public void setOnGoTo(final LongConsumer onGoTo) {
        this.onGoTo = onGoTo;
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
