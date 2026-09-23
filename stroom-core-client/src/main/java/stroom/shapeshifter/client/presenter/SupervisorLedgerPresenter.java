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

package stroom.shapeshifter.client.presenter;

import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.client.presenter.RestDataProvider;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.shapeshifter.shared.LedgerShape;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;
import stroom.widget.customdatebox.client.ClientDateUtil;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.function.Consumer;

/**
 * What is waiting on the ledger (design 01 §5.2, A28 §11.6), a row per shape, beside the attempts.
 * <p>
 * Nothing is held: every stream counted here was processed to an error stream and is where it always
 * was, so this is a list of what a promotion <em>would</em> release rather than a queue of anything
 * being kept. Grouped by shape because that is what settles — one promotion takes a whole shape off —
 * and a feed that has been unknown for a week is one row saying how many streams that is, not a page
 * of them.
 * <p>
 * Reading it releases nothing. A view that answered by releasing would put a backlog through the
 * pipeline because somebody opened a screen.
 */
public class SupervisorLedgerPresenter extends MyPresenterWidget<PagerView> {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final RestDataProvider<LedgerShape, ResultPage<LedgerShape>> dataProvider;
    private final MyDataGrid<LedgerShape> dataGrid;

    @Inject
    public SupervisorLedgerPresenter(final EventBus eventBus,
                                     final PagerView view,
                                     final RestFactory restFactory) {
        super(eventBus, view);
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Shapeshifter AI Ledger");
        dataGrid.setMultiLine(true);
        dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        initTableColumns();

        // Paged, like the attempts beside it. A document may have more waiting shapes than anybody
        // wants to scroll, and a grid handed all of them shows the first page and loses the rest.
        dataProvider = new RestDataProvider<LedgerShape, ResultPage<LedgerShape>>(eventBus) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<LedgerShape>> dataConsumer,
                                final RestErrorHandler errorHandler) {
                restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.ledger(null,
                                new PageRequest(range.getStart(), range.getLength())))
                        .onSuccess(dataConsumer)
                        .onFailure(errorHandler)
                        .taskMonitorFactory(view)
                        .exec();
            }
        };
        dataProvider.addDataDisplay(dataGrid);
    }

    /**
     * Read the ledger again: what is waiting changes when a shape settles.
     */
    public void refresh() {
        dataProvider.refresh();
    }

    private void initTableColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((LedgerShape shape) ->
                        text(NullSafe.get(shape.getDoc(), DocRef::getName))).build(),
                DataGridUtil.headingBuilder("Document")
                        .withToolTip("Whose ledger it is. Two documents may have shapes of the same name "
                                     + "and they settle separately.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((LedgerShape shape) -> text(shape.getShapeId())).build(),
                DataGridUtil.headingBuilder("Shape")
                        .withToolTip("The learning key's values for the streams that are waiting.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((LedgerShape shape) ->
                        text(Integer.toString(shape.getWaiting()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Waiting")
                        .withToolTip("How many streams. Nothing is held: each was processed to an error "
                                     + "stream and is where it always was. This is what a promotion of "
                                     + "the shape would ask to be processed again.")
                        .rightAligned()
                        .build(),
                80);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((LedgerShape shape) ->
                        text(ClientDateUtil.toISOString(shape.getOldestTimeMs()))).build(),
                DataGridUtil.headingBuilder("Waiting since")
                        .withToolTip("When the first of them arrived, which is how long this shape has "
                                     + "been going unprocessed.")
                        .build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((LedgerShape shape) -> text(shape.getReason())).build(),
                DataGridUtil.headingBuilder("Reason")
                        .withToolTip("What the most recent stream of this shape was told: nothing binds "
                                     + "it, it is being learned, it has been given up, or a draft awaits "
                                     + "review.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(NullSafe.string(value));
    }
}
