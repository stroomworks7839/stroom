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

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.PromptEvent;
import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.client.presenter.RestDataProvider;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.shapeshifter.shared.LearnAgainRequest;
import stroom.shapeshifter.shared.LedgerLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;
import stroom.widget.button.client.ButtonView;
import stroom.widget.customdatebox.client.ClientDateUtil;
import stroom.widget.util.client.MultiSelectionModel;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.function.Consumer;

/**
 * What is waiting on the ledger (design 01 §5.2, A28 §11.6), a row per learner, beside the attempts.
 * <p>
 * Nothing is held: every stream counted here was processed to an error stream and is where it always
 * was, so this is a list of what a promotion <em>would</em> release rather than a queue of anything
 * being kept. Grouped by learner because that is what settles — one promotion takes a learner's streams off —
 * and a feed that has been unknown for a week is one row saying how many streams that is, not a page
 * of them.
 * <p>
 * Reading it releases nothing. A view that answered by releasing would put a backlog through the
 * pipeline because somebody opened a screen.
 */
public class SupervisorLedgerPresenter extends MyPresenterWidget<PagerView> {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final RestDataProvider<LedgerLearner, ResultPage<LedgerLearner>> dataProvider;
    private final MyDataGrid<LedgerLearner> dataGrid;
    private final MultiSelectionModel<LedgerLearner> selectionModel;
    private final RestFactory restFactory;
    private final ButtonView learnButton;
    /// Told when a learner has been sent back to learn, so that the attempts beside this can be read
    /// again: what was asked for will show up there.
    private Runnable onLearn;

    @Inject
    public SupervisorLedgerPresenter(final EventBus eventBus,
                                     final PagerView view,
                                     final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Shapeshifter AI Ledger");
        dataGrid.setMultiLine(true);
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        initTableColumns();

        // Paged, like the attempts beside it. A document may have more waiting learners than anybody
        // wants to scroll, and a grid handed all of them shows the first page and loses the rest.
        dataProvider = new RestDataProvider<LedgerLearner, ResultPage<LedgerLearner>>(eventBus) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<LedgerLearner>> dataConsumer,
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
        // A28's way of starting learning rather than merely approving it: a learner waiting here is
        // waiting because nothing served its streams, and this asks for it to learn now.
        learnButton = view.addButton(SvgPresets.RERUN.title(
                "Learn now, and process the streams waiting on this learner again"));
        updateButtons();
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(selectionModel.addSelectionHandler(event -> updateButtons()));
        registerHandler(learnButton.addClickHandler(event -> learn()));
    }

    /**
     * Told when a learner has been sent back to learn.
     */
    public void setOnLearn(final Runnable onLearn) {
        this.onLearn = onLearn;
    }

    /// Ask for the selected learner to learn now (A28).
    ///
    /// This is the door the ruling leans on — "they raise an attempt for a given-up shape from the same
    /// screen, so no on-request learning mode is needed". A learner that gave up stays given up until
    /// somebody says otherwise, and this is saying otherwise: the give-up goes, the learner is sent back,
    /// and the streams waiting on it are asked to be processed again so that the first of them through
    /// learns.
    ///
    /// The reason is asked for rather than assumed. It opens the next attempt — the model is told why
    /// it is being asked again — and travels with every stream asked for.
    private void learn() {
        final LedgerLearner row = selectionModel.getSelected();
        if (row == null || row.getDoc() == null) {
            return;
        }
        PromptEvent.fire(this,
                "Why is this learner learning again? The model is told, and every stream waiting on "
                + "it is asked to be processed again — " + row.getWaiting()
                + (row.getWaiting() == 1
                        ? " stream."
                        : " streams."), "",
                reason -> {
                    // Null is Cancel; empty is no reason, and this one is not offered without one.
                    if (NullSafe.isBlankString(reason)) {
                        return;
                    }
                    restFactory
                            .create(SUPERVISOR_RESOURCE)
                            .method(resource -> resource.learn(row.getDoc().getUuid(),
                                    new LearnAgainRequest(row.getLearner(), reason)))
                            .onSuccess(asked -> AlertEvent.fireInfo(this, asked == null || asked == 0
                                    ? "It will learn when its feed next ships: nothing was "
                                      + "waiting on it."
                                    : asked + (asked == 1
                                            ? " stream is"
                                            : " streams are")
                                      + " asked to be processed again, and the first of them through "
                                      + "learns.", this::learned))
                            .taskMonitorFactory(getView())
                            .exec();
                });
    }

    private void learned() {
        // The row has just been released — the streams it counted are being processed again — so the
        // selection is on a learner that is no longer waiting, and the button would stay lit for it.
        selectionModel.clear();
        updateButtons();
        refresh();
        if (onLearn != null) {
            onLearn.run();
        }
    }

    private void updateButtons() {
        learnButton.setEnabled(selectionModel.getSelected() != null);
    }

    /**
     * Read the ledger again: what is waiting changes when a learner settles.
     */
    public void refresh() {
        dataProvider.refresh();
    }

    private void initTableColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((LedgerLearner row) ->
                        text(NullSafe.get(row.getDoc(), DocRef::getName))).build(),
                DataGridUtil.headingBuilder("Document")
                        .withToolTip("The document the learner runs under.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((LedgerLearner row) -> text(String.valueOf(row.getLearner())))
                        .build(),
                DataGridUtil.headingBuilder("Learner")
                        .withToolTip("The pipeline and the element in it whose streams are waiting.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((LedgerLearner row) ->
                        text(Integer.toString(row.getWaiting()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Waiting")
                        .withToolTip("How many streams. Nothing is held: each was processed to an error "
                                     + "stream and is where it always was. This is what a promotion of "
                                     + "the learner would ask to be processed again.")
                        .rightAligned()
                        .build(),
                80);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((LedgerLearner row) ->
                        text(ClientDateUtil.toISOString(row.getOldestTimeMs()))).build(),
                DataGridUtil.headingBuilder("Waiting since")
                        .withToolTip("When the first of them arrived, which is how long this learner has "
                                     + "been going unprocessed.")
                        .build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((LedgerLearner row) -> text(row.getReason())).build(),
                DataGridUtil.headingBuilder("Reason")
                        .withToolTip("What the most recent of its streams was told: nothing serves it, it "
                                     + "is learning, it has given up, or a draft awaits review.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(NullSafe.string(value));
    }
}
