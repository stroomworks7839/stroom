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
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * One attempt's dialogue, turn by turn (A28): what was asked, what was answered and by whom, and what
 * the answer scored. A turn with no answer is the question the attempt stopped at, which is what a
 * person may answer instead.
 */
public class SupervisorTurnsPresenter extends MyPresenterWidget<PagerView> {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final MyDataGrid<SupervisorTurn> dataGrid;
    private final RestFactory restFactory;
    private final List<SupervisorTurn> turns = new ArrayList<>();
    /// Which attempt the grid is showing: a person clicking down a list asks faster than the server
    /// answers, and the answer to a row they have left must not land in the grid under the row they are
    /// on now.
    private int asked;

    @Inject
    public SupervisorTurnsPresenter(final EventBus eventBus,
                                    final PagerView view,
                                    final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Turns");
        dataGrid.setMultiLine(true);
        dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        initTableColumns();
        dataGrid.setRowData(0, turns);
        dataGrid.setRowCount(turns.size(), true);
    }

    /**
     * Read the dialogue of one attempt. The list gives the row without its turns — a page of transcripts
     * is a page nobody reads — so the detail is fetched when a row is chosen.
     */
    public void read(final SupervisorAttempt attempt) {
        final int request = ++asked;
        turns.clear();
        show();
        if (attempt != null) {
            restFactory
                    .create(SUPERVISOR_RESOURCE)
                    .method(resource -> resource.fetch(attempt.getId()))
                    .onSuccess(detail -> {
                        if (request == asked) {
                            turns.addAll(NullSafe.list(detail.getTurns()));
                            show();
                        }
                    })
                    .onFailure(error -> {
                        // The grid says nothing rather than going on showing the turns of whatever was
                        // selected before.
                        if (request == asked) {
                            show();
                        }
                    })
                    .taskMonitorFactory(getView())
                    .exec();
        }
    }

    private void show() {
        dataGrid.setRowData(0, turns);
        dataGrid.setRowCount(turns.size(), true);
    }

    private void initTableColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(Integer.toString(turn.getNumber())))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("#")
                        .withToolTip("The turn's place in the dialogue.")
                        .rightAligned()
                        .build(),
                40);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(turn.getQuestion())).build(),
                DataGridUtil.headingBuilder("Question")
                        .withToolTip("What was asked: the kind of question and what it was about. Not the "
                                     + "rendered prompt, which carries the stream's own text.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(turn.getAnswer())).build(),
                DataGridUtil.headingBuilder("Answer")
                        .withToolTip("What was answered. Empty for the question the attempt stopped at, "
                                     + "which nobody has answered yet.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(turn.getAnsweredBy())).build(),
                DataGridUtil.headingBuilder("Answered by")
                        .withToolTip("The model the document names, or the person who answered instead.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) ->
                        text(NullSafe.get(turn.getOutcome(), outcome -> outcome.getDisplayValue()))).build(),
                DataGridUtil.headingBuilder("Outcome")
                        .withToolTip("What the answer scored when it was run, where it has been run.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(value == null
                ? ""
                : value);
    }
}
