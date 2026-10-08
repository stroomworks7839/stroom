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
import stroom.alert.client.event.ConfirmEvent;
import stroom.alert.client.event.PromptEvent;
import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.client.presenter.RestDataProvider;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.shapeshifter.shared.ImproveRequest;
import stroom.shapeshifter.shared.LearnerCriteria;
import stroom.shapeshifter.shared.LearnerRequest;
import stroom.shapeshifter.shared.LearnerState;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;
import stroom.widget.button.client.ButtonView;
import stroom.widget.customdatebox.client.ClientDateUtil;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.SelectionType;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.function.Consumer;

/// The Supervisor's learning pipelines (design 06 A115): one row per pipeline's Shapeshifter AI element that
/// has learned or is learning, where it stands, what serves it, what waits on it and when it last had a
/// session, most waiting first. The pipeline-level actions sit here — send back, give up, guidance, improve
/// — and so do those on the binding the row shows serving: accept, open its fragment, retract (A141).
/// Narrowed by document, state and deletion (A142).
public class SupervisorLearnersPresenter extends MyPresenterWidget<PagerView> {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final RestFactory restFactory;
    private final SupervisorGuidancePresenter guidancePresenter;
    private final Provider<LearnerFilterPresenter> filterPresenterProvider;
    private final RestDataProvider<SupervisorLearner, ResultPage<SupervisorLearner>> dataProvider;
    private final MyDataGrid<SupervisorLearner> dataGrid;
    private final MultiSelectionModel<SupervisorLearner> selectionModel;
    private final ButtonView filterButton;
    private final ButtonView relearnButton;
    private final ButtonView giveUpButton;
    private final ButtonView hintButton;
    private final ButtonView improveButton;
    private final ButtonView acceptButton;
    private final ButtonView openButton;
    private final ButtonView retractButton;
    /// What is being asked for: every learning pipeline of every readable document until somebody narrows
    /// it. Kept here because the data provider is asked for a page at a time and must ask the same
    /// question each time.
    private LearnerCriteria criteria = new LearnerCriteria();
    /// What is read again after a row is acted on: this list alone, until the Supervisor says otherwise.
    private Runnable onActed = this::refresh;

    @Inject
    public SupervisorLearnersPresenter(final EventBus eventBus,
                                       final PagerView view,
                                       final SupervisorGuidancePresenter guidancePresenter,
                                       final Provider<LearnerFilterPresenter> filterPresenterProvider,
                                       final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.guidancePresenter = guidancePresenter;
        this.filterPresenterProvider = filterPresenterProvider;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Shapeshifter AI Learning Pipelines");
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        initTableColumns();

        filterButton = view.addButton(SvgPresets.FILTER.title("Narrow which learning pipelines are shown"));
        relearnButton = view.addButton(SvgPresets.UNDO.title(
                "Learn again: the next stream opens a session while the binding goes on serving it"));
        giveUpButton = view.addButton(SvgPresets.STOP.title(
                "Give this learner up: it learns no more, and its streams nothing serves error"));
        hintButton = view.addButton(SvgPresets.EDIT.title(
                "What has been said to this learner, and say something else"));
        improveButton = view.addButton(SvgPresets.RERUN.title(
                "Ask for this binding to be made better, from what it already does"));
        // §6, §11.6: a provisional binding a person may accept rather than wait out.
        acceptButton = view.addButton(SvgPresets.TICK.title(
                "Accept this provisional binding now, without waiting for records to judge it on"));
        openButton = view.addButton(SvgPresets.OPEN.title("Open this binding's fragment"));
        retractButton = view.addButton(SvgPresets.DISABLE.title(
                "Take this binding back, and ask for what it produced to be processed again"));

        dataProvider = new RestDataProvider<SupervisorLearner, ResultPage<SupervisorLearner>>(eventBus) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<SupervisorLearner>> dataConsumer,
                                final RestErrorHandler errorHandler) {
                final LearnerCriteria asked = new LearnerCriteria(
                        new PageRequest(range.getStart(), range.getLength()),
                        criteria.getSortList(),
                        criteria.getDocUuid(),
                        criteria.getStates(),
                        criteria.isShowDeletedPipelines());
                restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.learners(asked))
                        .onSuccess(page -> {
                            dataConsumer.accept(page);
                            keepSelected(page);
                        })
                        .onFailure(errorHandler)
                        .taskMonitorFactory(view)
                        .exec();
            }
        };
        dataProvider.addDataDisplay(dataGrid);
        updateFilterButton();
        updateButtons();
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(selectionModel.addSelectionHandler(event -> updateButtons()));
        registerHandler(filterButton.addClickHandler(event -> filter()));
        registerHandler(relearnButton.addClickHandler(event -> relearn()));
        registerHandler(giveUpButton.addClickHandler(event -> giveUp()));
        registerHandler(hintButton.addClickHandler(event -> hint()));
        registerHandler(improveButton.addClickHandler(event -> improve()));
        registerHandler(acceptButton.addClickHandler(event -> accept()));
        registerHandler(openButton.addClickHandler(event -> open()));
        registerHandler(retractButton.addClickHandler(event -> retract()));
    }

    /// @return The selection, whose learner the levels beneath follow.
    public MultiSelectionModel<SupervisorLearner> getSelectionModel() {
        return selectionModel;
    }

    /// Read the page again.
    public void refresh() {
        dataProvider.refresh();
    }

    /// @param onActed What to read again after a row is acted on.
    public void setOnActed(final Runnable onActed) {
        this.onActed = onActed;
    }

    /// Narrow what is shown, or widen it again (A142). Always from the first page: the offset a person
    /// is on is an offset into a list that has just changed length.
    private void filter() {
        // What is being asked for now, taken now: the popup does not stop a person opening it again
        // behind itself, and reading the field when OK is pressed would write one dialog's answer over
        // the other's.
        final LearnerCriteria opened = criteria;
        final LearnerFilterPresenter presenter = filterPresenterProvider.get();
        presenter.read(opened);
        ShowPopupEvent.builder(presenter)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(600, 520))
                .caption("Which learning pipelines to show")
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        criteria = presenter.write(opened);
                        updateFilterButton();
                        // The selection goes with it: a row the filter has just excluded would still
                        // answer the guards that decide what is offered.
                        selectionModel.clear();
                        updateButtons();
                        dataGrid.setVisibleRange(new Range(0, PageRequest.DEFAULT_PAGE_LENGTH));
                        refresh();
                    }
                    e.hide();
                })
                .fire();
    }

    /// The button says whether anything is being left out, or deleted pipelines let in.
    private void updateFilterButton() {
        filterButton.setTitle(LearnerFilterPresenter.isFiltering(criteria)
                ? "Learning pipelines are being filtered; click to change or clear"
                : "Narrow which learning pipelines are shown");
    }

    /// Send the learner back to learn afresh from its next stream (A28, A29): for one that gave up, or
    /// that bound something a person is not content with.
    private void relearn() {
        final SupervisorLearner learner = selected();
        if (learner == null) {
            return;
        }
        ConfirmEvent.fire(this, "Learn again? The learner learns afresh from its next stream, and what is "
                                + "bound now keeps serving until then. Streams that errored while it had "
                                + "given up are ordinary error streams: reprocess them on the pipeline to "
                                + "put them through.",
                ok -> {
                    if (ok) {
                        restFactory
                                .create(SUPERVISOR_RESOURCE)
                                .method(resource -> resource.relearnLearner(learner.getDoc().getUuid(),
                                        new LearnerRequest(learner.getLearner(), null)))
                                .onSuccess(done -> acted())
                                .taskMonitorFactory(this)
                                .exec();
                    }
                });
    }

    /// Give the learner up (design 06 A115): it learns no more, and its streams nothing serves error with
    /// the reason, which is asked for rather than assumed. What serves it goes on serving.
    private void giveUp() {
        final SupervisorLearner learner = selected();
        if (learner == null) {
            return;
        }
        PromptEvent.fire(this,
                "Why is this learner being given up? It learns no more, and its streams that nothing "
                + "serves error with the reason. What serves it goes on serving, and learning again "
                + "sends it back.",
                "",
                reason -> {
                    // Null is Cancel; empty is no reason, and this one is not offered without one.
                    if (NullSafe.isBlankString(reason)) {
                        return;
                    }
                    restFactory
                            .create(SUPERVISOR_RESOURCE)
                            .method(resource -> resource.giveUp(learner.getDoc().getUuid(),
                                    new LearnerRequest(learner.getLearner(), reason)))
                            .onSuccess(done -> acted())
                            .taskMonitorFactory(this)
                            .exec();
                });
    }

    /// Ask for a binding that is already serving to be made better (A46).
    ///
    /// The message is optional and the prompt says so: asking again from the incumbent, with the records
    /// it was accepted on and the score it made on them, is itself worth something. What is typed is kept
    /// as guidance for the learner rather than for the attempt, so it is carried into every question it
    /// asks from then on — including the relearning of A29 months later.
    private void improve() {
        final SupervisorLearner binding = selected();
        if (binding == null) {
            return;
        }
        PromptEvent.fire(this,
                "What should be better about this binding? Leave it empty to ask again from what it already "
                + "does. What you write is kept against the learner, so everything it asks from now "
                + "on carries it.", "",
                message -> {
                    // Null is Cancel and empty is "ask again from what it already does", and the two
                    // must not be confused: an improvement spends a model's tokens and may rebind the
                    // binding, so a person who changed their mind must not have started one.
                    if (message == null) {
                        return;
                    }
                    restFactory
                            .create(SUPERVISOR_RESOURCE)
                            .method(resource -> resource.improve(binding.getDoc().getUuid(),
                                    binding.getBindingUuid(), new ImproveRequest(message)))
                            .onSuccess(outcome -> {
                                // The incumbent served every stream throughout and a candidate takes
                                // over only through the ordinary gate, so "nothing changed" is an
                                // outcome and not a failure. The person is told which of the four.
                                AlertEvent.fireInfo(this, outcome.getSaid(), this::acted);
                            })
                            // The attempt runs while the request is open, so a request that times out
                            // has not necessarily failed: it may have promoted or drafted since. Said
                            // rather than left as a bare error, because the answer to "did it work?" is
                            // in the attempt list and a second click would spend another model run.
                            .onFailure(error -> AlertEvent.fireError(this,
                                    "The improvement did not come back: " + error.getMessage(),
                                    "It may still be running, or may have finished after the request "
                                    + "gave up. Look for its session under the pipeline before asking again — "
                                    + "asking twice spends a second model run.",
                                    this::acted))
                            .taskMonitorFactory(this)
                            .exec();
                });
    }

    /// Tell this binding's learner something, without asking for anything to be run
    /// (A46): the feed's own explanation of a field, a correction, a fact no sample shows.
    ///
    /// Separate from improving, because a person who has just learned something about a feed should be
    /// able to write it down at once and have it carried into whatever is asked next — which may be
    /// months away, and may be a relearning nobody is watching.
    private void hint() {
        final SupervisorLearner binding = selected();
        if (binding != null) {
            // What is standing first, then the adding: a person about to say something should see what
            // has already been said, or the same hint is given three times and a wrong one is never
            // taken back. The row's count is read again when anything changes.
            // Only the count on the row changes, so the row a person is reading stays selected.
            guidancePresenter.show(binding.getDoc().getUuid(), binding.getLearner(), binding.isPipelineDeleted(),
                    this::refresh);
        }
    }

    /// Accept a provisional binding now (§6): the binding stops being marked provisional and serves on
    /// the score it was bound at.
    ///
    /// Confirmed, and the confirmation says what is being skipped. A provisional binding cleared the
    /// promotion floor — that is what made it bindable — and what it has not had is enough records for
    /// a held-out judgement. Accepting says the wait is not worth it, which is a person's call to make
    /// and not a gate's.
    private void accept() {
        final SupervisorLearner binding = selected();
        if (binding == null || !binding.isProvisional()) {
            return;
        }
        ConfirmEvent.fire(this,
                "Accept this binding? It scored " + score(binding.getPromotedScore()) + " on the stream it "
                + "was learned from, which cleared the floor but was too few records to judge it on. "
                + "Accepting serves it on that score without waiting for more.",
                ok -> {
                    if (ok) {
                        restFactory
                                .create(SUPERVISOR_RESOURCE)
                                .method(resource -> resource.accept(binding.getDoc().getUuid(),
                                        binding.getBindingUuid()))
                                .onSuccess(done -> acted())
                                .taskMonitorFactory(this)
                                .exec();
                    }
                });
    }

    /// Open the selected binding's fragment, which is how a person gets from "this is scoring
    /// 0.71" to the stylesheet that is doing it.
    private void open() {
        final SupervisorLearner binding = selected();
        if (binding != null && binding.getFragment() != null) {
            OpenDocumentEvent.fire(this, binding.getFragment(), true);
        }
    }

    /// Take a binding back (A28): what it produced was produced by a binding that is
    /// being withdrawn. It is counted, not put through again (A129): reprocessing it as it would be now
    /// is a filter the person makes on the pipeline.
    ///
    /// Confirmed and reasoned, because it is the most consequential thing on this screen: a feed stops
    /// being served until its learner learns again. The reason is asked for rather than assumed — it is
    /// recorded against the attempt that wrote the binding.
    private void retract() {
        final SupervisorLearner binding = selected();
        if (binding == null) {
            return;
        }
        PromptEvent.fire(this,
                "Why is this binding being taken back? It stops serving and its learner learns again "
                + "from the next stream. What it produced stands as it made it: you are told how many "
                + "streams that is, to reprocess on the pipeline if you want them as they would be now.",
                "",
                reason -> {
                    // Null is Cancel; empty is no reason, and this one is not offered without one.
                    if (NullSafe.isBlankString(reason)) {
                        return;
                    }
                    restFactory
                            .create(SUPERVISOR_RESOURCE)
                            .method(resource -> resource.retract(binding.getDoc().getUuid(),
                                    binding.getBindingUuid(), new RejectRequest(reason)))
                            .onSuccess(produced -> {
                                // What a person cannot see for themselves: how much stands as the
                                // withdrawn binding made it.
                                AlertEvent.fireInfo(this, produced == null || produced == 0
                                        ? "The binding has been taken back. It had produced nothing."
                                        : "The binding has been taken back. " + produced + " stream"
                                          + (produced == 1
                                                  ? " it produced stands"
                                                  : "s it produced stand")
                                          + " as it made them; reprocess them on the pipeline to put "
                                          + "them through what serves now.", this::acted);
                            })
                            .taskMonitorFactory(this)
                            .exec();
                });
    }

    /// Read everything again after something was done to a row: the row, and the sessions and waiting
    /// streams beneath it, which giving up or learning again change too. The row stays selected, and
    /// [#keepSelected] swaps in its fresh copy when the page comes back.
    private void acted() {
        onActed.run();
    }

    /// The selected row swapped for its copy in the page just read, without telling anyone the selection
    /// changed, since the learner chosen has not.
    ///
    /// A selection holds the row *object* it was made from, and reading the list again makes new ones
    /// without touching it — so a binding just accepted would go on answering "provisional" to the guard
    /// that offers Accept, a learner a session's rejection has just given up would go on offering Give up,
    /// and a binding a promotion has just replaced would go on offering Retract for itself. A row no
    /// longer on the page lets the selection go, and the levels beneath with it.
    private void keepSelected(final ResultPage<SupervisorLearner> page) {
        final SupervisorLearner selected = selected();
        if (selected == null) {
            return;
        }
        final SupervisorLearner fresh = NullSafe.stream(page.getValues())
                .filter(row -> selected.getLearner().equals(row.getLearner()))
                .findFirst()
                .orElse(null);
        if (fresh == null) {
            selectionModel.clear();
        } else {
            selectionModel.setSelected(fresh, new SelectionType(), false);
        }
        updateButtons();
    }

    private static String score(final Double score) {
        return score == null
                ? ""
                : Double.toString(Math.round(score * 1000.0) / 1000.0);
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(NullSafe.string(value));
    }

    private SupervisorLearner selected() {
        return selectionModel.getSelected();
    }

    /// What is offered depends on the row: nothing that would change a deleted pipeline's learning (A137),
    /// the binding's actions only where a binding serves, accept only where it is provisional, and give
    /// up only where the learner has not given up already.
    private void updateButtons() {
        final SupervisorLearner selected = selected();
        final boolean one = selected != null;
        final boolean live = one && !selected.isPipelineDeleted();
        final boolean serving = live && selected.getBindingUuid() != null;
        relearnButton.setEnabled(live);
        giveUpButton.setEnabled(live && selected.getState() != LearnerState.GIVEN_UP);
        hintButton.setEnabled(one);
        improveButton.setEnabled(serving);
        acceptButton.setEnabled(serving && selected.isProvisional());
        openButton.setEnabled(one && selected.getFragment() != null);
        retractButton.setEnabled(serving);
    }

    private void initTableColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) -> text(pipeline(row))).build(),
                DataGridUtil.headingBuilder("Pipeline")
                        .withToolTip("The pipeline whose Shapeshifter AI element this is, and the element's id "
                                     + "where it is not the usual one.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) ->
                        text(NullSafe.get(row.getDoc(), DocRef::getName))).build(),
                DataGridUtil.headingBuilder("Document")
                        .withToolTip("The Shapeshifter AI document the element runs under.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) -> text(state(row))).build(),
                DataGridUtil.headingBuilder("State")
                        .withToolTip("Learning, waiting for a person, serving, given up, or learning off for "
                                     + "its document; and whether its pipeline has been deleted, when deleted "
                                     + "pipelines are shown.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) -> text(serving(row))).build(),
                DataGridUtil.headingBuilder("Serving")
                        .withToolTip("The fragment that serves it, what it scores lately over the learner's "
                                     + "rolling memory and how many records that rests on, what it scored "
                                     + "when it was promoted, and whether it is scoring below its "
                                     + "document's relearn threshold.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) -> text(waiting(row)))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Waiting")
                        .withToolTip("How many streams wait on it, and whether the longest has waited past "
                                     + "its document's maximum deferral age.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) ->
                        text(NullSafe.get(row.getLastSessionMs(), ClientDateUtil::toISOString))).build(),
                DataGridUtil.headingBuilder("Last session")
                        .withToolTip("When its most recent learning session opened.")
                        .build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) ->
                        text(row.getGuidance() == 0
                                ? ""
                                : Integer.toString(row.getGuidance()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Said")
                        .withToolTip("How many things a supervisor has told this learner, so that you can see "
                                     + "whether the last was acted on before saying another.")
                        .rightAligned()
                        .build(),
                60);
    }

    private static String pipeline(final SupervisorLearner row) {
        final String name = row.getPipeline() == null || row.getPipeline().getName() == null
                ? NullSafe.get(row.getLearner(), learner -> learner.getPipelineUuid())
                : row.getPipeline().getName();
        final String element = NullSafe.get(row.getLearner(), learner -> learner.getElementId());
        return "shapeshifterAi".equals(element) || element == null
                ? name
                : name + " / " + element;
    }

    private static String state(final SupervisorLearner row) {
        final String state = NullSafe.get(row.getState(), LearnerState::getDisplayValue);
        final String withReason = row.getGivenUpReason() == null
                ? state
                : state + ": " + row.getGivenUpReason();
        return row.isPipelineDeleted()
                ? withReason + " (pipeline deleted)"
                : withReason;
    }

    private static String serving(final SupervisorLearner row) {
        if (row.getBindingUuid() == null) {
            return "";
        }
        final StringBuilder serving = new StringBuilder(NullSafe.string(
                NullSafe.get(row.getFragment(), DocRef::getName)));
        if (row.getRollingScore() != null) {
            // What the score rests on, since 0.6 over three records and 0.6 over ten thousand are different
            // things to a person deciding whether to improve it (A46).
            serving.append(", ").append(score(row.getRollingScore())).append(" lately over ")
                    .append(row.getRecords()).append(row.getRecords() == 1
                            ? " record"
                            : " records");
        }
        if (row.getPromotedScore() != null) {
            serving.append(", ").append(score(row.getPromotedScore())).append(" promoted");
        }
        if (row.isProvisional()) {
            serving.append(", provisional");
        }
        if (row.isBelowThreshold()) {
            // The document's relearn threshold, read here and nowhere else (design 04 §6): a person may
            // improve it.
            serving.append(", below the threshold");
        }
        return serving.toString();
    }

    private static String waiting(final SupervisorLearner row) {
        return row.isRaised()
                ? row.getWaiting() + " (raised)"
                : Integer.toString(row.getWaiting());
    }
}
