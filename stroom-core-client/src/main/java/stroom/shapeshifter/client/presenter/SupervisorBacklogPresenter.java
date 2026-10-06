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
import stroom.shapeshifter.shared.BacklogStream;
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

/// The Supervisor's backlog (design 04 §6): what is waiting on a session, a row per stream — on which
/// transform it fell short, since when, how many times it has been tried — with the streams that have
/// waited past their document's maximum deferral age raised to the top and marked (A57, A78, A79). Nothing
/// here is an action: a stream waits until its session promotes, or its shape is given up, and both are
/// done from beside the attempts.
public class SupervisorBacklogPresenter extends MyPresenterWidget<PagerView> {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final RestDataProvider<BacklogStream, ResultPage<BacklogStream>> dataProvider;
    private final MyDataGrid<BacklogStream> dataGrid;

    @Inject
    public SupervisorBacklogPresenter(final EventBus eventBus,
                                      final PagerView view,
                                      final RestFactory restFactory) {
        super(eventBus, view);
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Shapeshifter AI Backlog");
        dataGrid.setMultiLine(true);
        view.setDataWidget(dataGrid);
        initTableColumns();
        dataProvider = new RestDataProvider<BacklogStream, ResultPage<BacklogStream>>(eventBus) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<BacklogStream>> dataConsumer,
                                final RestErrorHandler errorHandler) {
                restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.backlog(null,
                                new PageRequest(range.getStart(), range.getLength())))
                        .onSuccess(dataConsumer)
                        .onFailure(errorHandler)
                        .taskMonitorFactory(view)
                        .exec();
            }
        };
        dataProvider.addDataDisplay(dataGrid);
    }

    private void initTableColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) -> text(stream.isRaised()
                        ? "Raised"
                        : "")).build(),
                DataGridUtil.headingBuilder("Raised")
                        .withToolTip("A stream that has waited past the document's maximum deferral age. It "
                                     + "goes on waiting; this is the flag that says somebody should look.")
                        .build(),
                70);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) ->
                        text(NullSafe.get(stream.getDoc(), DocRef::getName))).build(),
                DataGridUtil.headingBuilder("Document").build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) ->
                        text(stream.getFeed() + " / " + stream.getType())).build(),
                DataGridUtil.headingBuilder("Feed and type").build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) ->
                        text(Long.toString(stream.getInputId()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Stream")
                        .withToolTip("The stream's id, as the data browser has it.")
                        .rightAligned()
                        .build(),
                80);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) ->
                        text(ClientDateUtil.toISOString(stream.getFirstDeferredMs()))).build(),
                DataGridUtil.headingBuilder("Waiting since")
                        .withToolTip("When the stream first fell short and was held.")
                        .build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) ->
                        text(Integer.toString(stream.getTries()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Tries")
                        .withToolTip("How many times a task has decided this stream and held it again.")
                        .rightAligned()
                        .build(),
                60);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) ->
                        text(NullSafe.getOrElse(stream.getFailedAgainst(), v -> v, "nothing bound"))).build(),
                DataGridUtil.headingBuilder("Held against")
                        .withToolTip("The snapshot of the transform the stream was run through and fell "
                                     + "short of. A promotion of that shape moves it on.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((BacklogStream stream) -> text(stream.getReason())).build(),
                DataGridUtil.headingBuilder("Reason").build(),
                ColumnSizeConstants.BIG_COL);
    }

    private static SafeHtml text(final String text) {
        return SafeHtmlUtils.fromString(text == null
                ? ""
                : text);
    }

    /// Read the backlog again: what is waiting changes as sessions promote.
    public void refresh() {
        dataProvider.refresh();
    }
}
