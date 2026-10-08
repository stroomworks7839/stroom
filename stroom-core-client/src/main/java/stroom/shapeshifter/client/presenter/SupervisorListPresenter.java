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
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.svg.client.Preset;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;
import stroom.widget.button.client.ButtonView;
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
 * The selected learning pipeline's sessions, newest first (A28, design 06 A115, A143): one row per attempt
 * — when, where, in what mode, what it was learning from, what it came to and what it cost. Nothing is
 * listed until a pipeline is chosen above, and there is no filter: the pipeline is the way in.
 */
public class SupervisorListPresenter extends MyPresenterWidget<PagerView> {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final MultiSelectionModelImpl<SupervisorAttempt> selectionModel;
    private final RestDataProvider<SupervisorAttempt, ResultPage<SupervisorAttempt>> dataProvider;
    private final MyDataGrid<SupervisorAttempt> dataGrid;
    /// Whose sessions are listed: the pipeline chosen above, or nobody's before one is.
    private LearnerRef learner;

    @Inject
    public SupervisorListPresenter(final EventBus eventBus,
                                   final PagerView view,
                                   final RestFactory restFactory) {
        super(eventBus, view);

        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Shapeshifter AI Sessions");
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        initTableColumns(dataGrid);

        dataProvider = new RestDataProvider<SupervisorAttempt, ResultPage<SupervisorAttempt>>(eventBus) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<SupervisorAttempt>> dataConsumer,
                                final RestErrorHandler errorHandler) {
                if (learner == null) {
                    dataConsumer.accept(ResultPage.empty());
                    return;
                }
                final AttemptCriteria asked = AttemptCriteria.forLearner(
                        new PageRequest(range.getStart(), range.getLength()), learner);
                restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.find(asked))
                        .onSuccess(dataConsumer)
                        .onFailure(errorHandler)
                        .taskMonitorFactory(view)
                        .exec();
            }
        };
        dataProvider.addDataDisplay(dataGrid);
    }

    /// List this learner's sessions, from the first page, or none.
    ///
    /// @param learner The learner chosen above, or null where none is.
    public void setLearner(final LearnerRef learner) {
        this.learner = learner;
        selectionModel.clear();
        dataGrid.setVisibleRange(new Range(0, PageRequest.DEFAULT_PAGE_LENGTH));
        refresh();
    }

    /// A button on this list's own toolbar, for the actions A28 puts beside the attempt they act on.
    public ButtonView add(final Preset preset) {
        return getView().addButton(preset);
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
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) -> text(row.getType())).build(),
                DataGridUtil.headingBuilder("Type")
                        .withToolTip("The type of the stream the attempt was raised on.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) ->
                        text(status(row))).build(),
                DataGridUtil.headingBuilder("Status")
                        .withToolTip("Where the attempt stands: learning, waiting for the model, waiting for "
                                     + "a person, or what it came to; whether a person has sent it to the "
                                     + "exhaustion step; and whether its pipeline has been deleted, when "
                                     + "deleted pipelines are shown.")
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
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) -> text(row.getNode())).build(),
                DataGridUtil.headingBuilder("Node")
                        .withToolTip("Which node last carried the attempt.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
    }

    /// Where the attempt stands; that a person has sent it to the exhaustion step, until the worker takes
    /// it there (A65, ISSUES item 6); and that its pipeline is deleted where it is (design 06 A137): kept to
    /// be read, with nothing offered that would change it.
    private static String status(final SupervisorAttempt row) {
        final StringBuilder status = new StringBuilder(NullSafe.string(
                NullSafe.get(row.getStatus(), AttemptStatus::getDisplayValue)));
        if (row.isExhaustNext()) {
            status.append(", sent to the exhaustion step");
        }
        if (row.isPipelineDeleted()) {
            status.append(" (pipeline deleted)");
        }
        return status.toString();
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(NullSafe.string(value));
    }
}
