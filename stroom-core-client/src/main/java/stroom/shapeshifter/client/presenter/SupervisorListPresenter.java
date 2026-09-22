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
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;
import stroom.widget.customdatebox.client.ClientDateUtil;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.function.Consumer;

/**
 * Every document's attempts, newest first (A28): one row per attempt — when, where, in what mode, what
 * it was learning from, what it came to and what it cost.
 */
public class SupervisorListPresenter extends MyPresenterWidget<PagerView> {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final MultiSelectionModelImpl<SupervisorAttempt> selectionModel;
    private final RestDataProvider<SupervisorAttempt, ResultPage<SupervisorAttempt>> dataProvider;

    @Inject
    public SupervisorListPresenter(final EventBus eventBus,
                                   final PagerView view,
                                   final RestFactory restFactory) {
        super(eventBus, view);

        final MyDataGrid<SupervisorAttempt> dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Shapeshifter AI Attempts");
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        initTableColumns(dataGrid);

        dataProvider = new RestDataProvider<SupervisorAttempt, ResultPage<SupervisorAttempt>>(eventBus) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<SupervisorAttempt>> dataConsumer,
                                final RestErrorHandler errorHandler) {
                final AttemptCriteria criteria = new AttemptCriteria(
                        new PageRequest(range.getStart(), range.getLength()),
                        null, null, null, null, null, null, null);
                restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.find(criteria))
                        .onSuccess(dataConsumer)
                        .onFailure(errorHandler)
                        .taskMonitorFactory(view)
                        .exec();
            }
        };
        dataProvider.addDataDisplay(dataGrid);
    }

    public MultiSelectionModel<SupervisorAttempt> getSelectionModel() {
        return selectionModel;
    }

    public void refresh() {
        dataProvider.refresh();
    }

    private void initTableColumns(final MyDataGrid<SupervisorAttempt> dataGrid) {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) ->
                        text(ClientDateUtil.toISOString(row.getCreateTimeMs()))).build(),
                DataGridUtil.headingBuilder("Started")
                        .withToolTip("When the attempt was opened.")
                        .build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) -> text(row.getFeed())).build(),
                DataGridUtil.headingBuilder("Feed")
                        .withToolTip("The feed of the stream the attempt was raised on.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) -> text(row.getShape())).build(),
                DataGridUtil.headingBuilder("Shape")
                        .withToolTip("The shape being learned, as the document's learning key writes it.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) ->
                        text(NullSafe.get(row.getStatus(), status -> status.getDisplayValue()))).build(),
                DataGridUtil.headingBuilder("Status")
                        .withToolTip("Where the attempt stands: learning, waiting for the model, waiting for "
                                     + "a person, or what it came to.")
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) -> text(row.getDecision())).build(),
                DataGridUtil.headingBuilder("Decision")
                        .withToolTip("What the attempt came to, in its own words.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) ->
                        text(NullSafe.get(row.getScore(), score -> score.toString())))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Score")
                        .withToolTip("What it scored, where it bound anything.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) ->
                        text(Long.toString(row.getTokensSpent())))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Tokens")
                        .withToolTip("What the model charged for this attempt, where it says.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) ->
                        text(NullSafe.get(row.getExecutionMode(), mode -> mode.getDisplayValue()))).build(),
                DataGridUtil.headingBuilder("Mode")
                        .withToolTip("Inline: learned inside the processing task. Deferred: parked for the "
                                     + "worker to carry on.")
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) -> text(row.getNode())).build(),
                DataGridUtil.headingBuilder("Node")
                        .withToolTip("Which node last carried the attempt.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(NullSafe.string(value));
    }
}
