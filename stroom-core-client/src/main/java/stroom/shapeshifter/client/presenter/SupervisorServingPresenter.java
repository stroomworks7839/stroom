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
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.ServingCriteria;
import stroom.shapeshifter.shared.ServingRule;
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
 * What is serving (ruling A46, design 01 §11.6): a rule per learned shape, ordered by the traffic each
 * carries, with <em>improve</em> on the row.
 * <p>
 * This is the way in that nothing else offers. Every other surface in the feature is reached because
 * something went wrong — a shape was given up, a draft awaits review, a rolling score fell through the
 * floor. A rule serving at 0.93 is above every threshold and nothing will ever flag it, so a person who
 * wants it better has to be able to go and find it. They find it here, busiest first, because the rule
 * carrying the most streams is the one worth an hour.
 * <p>
 * The threshold is a filter and not the order. Sorting by score would put the worst rule in the
 * installation at the top whether it carried one stream a month or a million.
 */
public class SupervisorServingPresenter extends MyPresenterWidget<PagerView> {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final RestFactory restFactory;
    private final SupervisorGuidancePresenter guidancePresenter;
    private final RestDataProvider<ServingRule, ResultPage<ServingRule>> dataProvider;
    private final MyDataGrid<ServingRule> dataGrid;
    private final MultiSelectionModel<ServingRule> selectionModel;
    private final ButtonView improveButton;
    private final ButtonView hintButton;
    private final ButtonView retractButton;
    private final ButtonView acceptButton;
    private final ButtonView openButton;
    private final ButtonView filterButton;
    private Double below;

    @Inject
    public SupervisorServingPresenter(final EventBus eventBus,
                                      final PagerView view,
                                      final SupervisorGuidancePresenter guidancePresenter,
                                      final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.guidancePresenter = guidancePresenter;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Shapeshifter AI Serving Rules");
        dataGrid.setMultiLine(true);
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        initTableColumns();

        improveButton = view.addButton(SvgPresets.RERUN.title(
                "Ask for this rule to be made better, from what it already does"));
        hintButton = view.addButton(SvgPresets.EDIT.title(
                "What has been said about this shape, and say something else"));
        // §6, §11.6: a provisional binding a person may accept rather than wait out. Offered only for
        // the rows that are one, which is why the row says which it is.
        acceptButton = view.addButton(SvgPresets.TICK.title(
                "Accept this provisional binding now, without waiting for records to judge it on"));
        // What the rule actually bound. The fragment is a pipeline document, so from it stroom's own
        // editor reaches the Data Splitter and the stylesheet the model wrote.
        openButton = view.addButton(SvgPresets.EDIT.title(
                "Open the fragment this rule binds"));
        retractButton = view.addButton(SvgPresets.DISABLE.title(
                "Take this rule back out of the table, and ask for what it produced to be processed "
                + "again"));
        // The filter of A46: "good but not perfect" is the one thing worth narrowing by, since nothing
        // else in the feature will ever raise a rule that is merely good.
        filterButton = view.addButton(SvgPresets.FILTER.title(
                "Show only the rules whose shape has been scoring below a figure"));

        // Paged, like the attempts and the ledger beside it. One rule per learned shape means a busy
        // document has as many rules as it has shapes, and a grid handed all of them shows the first
        // page and loses the rest.
        dataProvider = new RestDataProvider<ServingRule, ResultPage<ServingRule>>(eventBus) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<ServingRule>> dataConsumer,
                                final RestErrorHandler errorHandler) {
                restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.serving(new ServingCriteria(
                                new PageRequest(range.getStart(), range.getLength()), null, null, below)))
                        .onSuccess(dataConsumer)
                        .onFailure(errorHandler)
                        .taskMonitorFactory(view)
                        .exec();
            }
        };
        dataProvider.addDataDisplay(dataGrid);
        updateButtons();
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(selectionModel.addSelectionHandler(event -> updateButtons()));
        registerHandler(improveButton.addClickHandler(event -> improve()));
        registerHandler(hintButton.addClickHandler(event -> hint()));
        registerHandler(acceptButton.addClickHandler(event -> accept()));
        registerHandler(openButton.addClickHandler(event -> open()));
        registerHandler(retractButton.addClickHandler(event -> retract()));
        registerHandler(filterButton.addClickHandler(event -> filter()));
    }

    public void refresh() {
        dataProvider.refresh();
    }

    /// Read the list again after something was done to a row, and let go of the row first.
    ///
    /// A selection holds the row *object* it was made from, and rebuilding the list makes new ones
    /// without touching it — so a rule just accepted would go on answering "provisional" to the guard
    /// that offers Accept, and a rule just retracted would go on offering Improve for a rule that is no
    /// longer in the table. Both would be told no by the server, which is the right answer arriving in
    /// the wrong way.
    private void acted() {
        selectionModel.clear();
        updateButtons();
        refresh();
    }

    /// Ask for a rule that is already serving to be made better (A46).
    ///
    /// The message is optional and the prompt says so: asking again from the incumbent, with the records
    /// it was accepted on and the score it made on them, is itself worth something. What is typed is kept
    /// as guidance for the shape rather than for the attempt, so it is carried into every question asked
    /// about that shape from then on — including the relearning of A29 months later.
    private void improve() {
        final ServingRule rule = selected();
        if (rule == null) {
            return;
        }
        if (rule.isPinned()) {
            // §7.3 rule 2: a pin freezes a rule. Said here rather than letting the call fail, because a
            // button that is offered and then refuses is a button that lies.
            AlertEvent.fireWarn(this, "This rule is pinned, which keeps it serving exactly as it is. "
                                      + "Unpin it on the document's Routing tab before improving it.", null);
            return;
        }
        PromptEvent.fire(this,
                "What should be better about this rule? Leave it empty to ask again from what it already "
                + "does. What you write is kept against the shape, so everything asked about it from now "
                + "on carries it.", "",
                message -> {
                    // Null is Cancel and empty is "ask again from what it already does", and the two
                    // must not be confused: an improvement spends a model's tokens and may rebind the
                    // rule, so a person who changed their mind must not have started one.
                    if (message == null) {
                        return;
                    }
                    restFactory
                            .create(SUPERVISOR_RESOURCE)
                            .method(resource -> resource.improve(rule.getDoc().getUuid(),
                                    rule.getRuleUuid(), new ImproveRequest(message)))
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
                                    + "gave up. Look for its attempt in the list before asking again — "
                                    + "asking twice spends a second model run.",
                                    this::acted))
                            .taskMonitorFactory(this)
                            .exec();
                });
    }

    /// Tell the learning something about this rule's shape, without asking for anything to be run
    /// (A46): the feed's own explanation of a field, a correction, a fact no sample shows.
    ///
    /// Separate from improving, because a person who has just learned something about a feed should be
    /// able to write it down at once and have it carried into whatever is asked next — which may be
    /// months away, and may be a relearning nobody is watching.
    private void hint() {
        final ServingRule rule = selected();
        if (rule != null) {
            // What is standing first, then the adding: a person about to say something should see what
            // has already been said, or the same hint is given three times and a wrong one is never
            // taken back. The row's count is read again when anything changes.
            // Only the count on the row changes, so the row a person is reading stays selected.
            guidancePresenter.show(rule.getDoc().getUuid(), rule.getShapeId(), this::refresh);
        }
    }

    /// Accept a provisional binding now (§6): the rule stops being marked provisional and serves on
    /// the score it was bound at.
    ///
    /// Confirmed, and the confirmation says what is being skipped. A provisional rule cleared the
    /// promotion floor — that is what made it bindable — and what it has not had is enough records for
    /// a held-out judgement. Accepting says the wait is not worth it, which is a person's call to make
    /// and not a gate's.
    private void accept() {
        final ServingRule rule = selected();
        if (rule == null || !rule.isProvisional()) {
            return;
        }
        ConfirmEvent.fire(this,
                "Accept this binding? It scored " + score(rule.getPromotedScore()) + " on the stream it "
                + "was learned from, which cleared the floor but was too few records to judge it on. "
                + "Accepting serves it on that score without waiting for more.",
                ok -> {
                    if (ok) {
                        restFactory
                                .create(SUPERVISOR_RESOURCE)
                                .method(resource -> resource.accept(rule.getDoc().getUuid(),
                                        rule.getRuleUuid()))
                                .onSuccess(done -> acted())
                                .taskMonitorFactory(this)
                                .exec();
                    }
                });
    }

    /// Open the fragment the selected rule binds, which is how a person gets from "this is scoring
    /// 0.71" to the stylesheet that is doing it.
    private void open() {
        final ServingRule rule = selected();
        if (rule != null && rule.getFragment() != null) {
            OpenDocumentEvent.fire(this, rule.getFragment(), true);
        }
    }

    /// Take a rule back out of the table (A28): what it produced was produced by a binding that is
    /// being withdrawn, so all of it is asked to be processed again as it would be now (A12).
    ///
    /// Confirmed and reasoned, because it is the most consequential thing on this screen: a backlog goes
    /// through the pipeline and a feed stops being processed until its shape is learned again. The
    /// reason is asked for rather than assumed — it travels with every stream it asks for.
    private void retract() {
        final ServingRule rule = selected();
        if (rule == null) {
            return;
        }
        if (rule.isPinned()) {
            AlertEvent.fireWarn(this, "This rule is pinned, which keeps it serving exactly as it is. "
                                      + "Unpin it on the document's Routing tab before retracting it.", null);
            return;
        }
        PromptEvent.fire(this,
                "Why is this rule being taken back? It goes out of the table, its shape is learned again "
                + "from the next stream, and everything it has produced is asked to be processed again "
                + "as it would be now. The reason travels with every one of those streams.", "",
                reason -> {
                    // Null is Cancel; empty is no reason, and this one is not offered without one.
                    if (NullSafe.isBlankString(reason)) {
                        return;
                    }
                    restFactory
                            .create(SUPERVISOR_RESOURCE)
                            .method(resource -> resource.retract(rule.getDoc().getUuid(),
                                    rule.getRuleUuid(), new RejectRequest(reason)))
                            .onSuccess(asked -> {
                                // What a person cannot see for themselves: how much is now going
                                // through the pipeline again because of what they just pressed.
                                AlertEvent.fireInfo(this, asked == null || asked == 0
                                        ? "The rule is out of the table. It had produced nothing, so "
                                          + "nothing is being processed again."
                                        : "The rule is out of the table, and " + asked + " stream"
                                          + (asked == 1
                                                  ? " it produced is"
                                                  : "s it produced are")
                                          + " asked to be processed again.", this::acted);
                            })
                            .taskMonitorFactory(this)
                            .exec();
                });
    }

    /// Narrow the list to what has been scoring below a figure, or clear it: A46's filter over serving
    /// rules by rolling score. An empty answer shows everything again.
    private void filter() {
        PromptEvent.fire(this,
                "Show only the rules scoring below what? A figure between 0 and 1 — 0.95, say. Leave it "
                + "empty to show all of them.",
                below == null
                        ? ""
                        : below.toString(),
                answer -> {
                    if (answer == null) {
                        return;
                    }
                    if (answer.trim().isEmpty()) {
                        below = null;
                        applyFilter();
                        return;
                    }
                    final Double asked = asScore(answer.trim());
                    if (asked == null) {
                        AlertEvent.fireWarn(this, "'" + answer + "' is not a score. A score is a number "
                                                  + "from 0 to 1.", null);
                        return;
                    }
                    below = asked;
                    applyFilter();
                });
    }

    /// A score a rule can be asked to be below: a real number from 0 to 1, or null where what was typed
    /// is not one. `Double.valueOf` takes `NaN` and `Infinity` without complaint, and neither is a score
    /// — a query bound with one is refused by the database, which would answer a typing mistake with a
    /// server error.
    private static Double asScore(final String answer) {
        try {
            final double value = Double.parseDouble(answer);
            return Double.isFinite(value) && value >= 0.0 && value <= 1.0
                    ? value
                    : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /// Filtered from the first page. What a person is looking at is an offset into a list that has just
    /// changed length, so re-reading the page they were on would show them an empty grid whenever the
    /// filter leaves fewer rows than they had already scrolled past.
    private void applyFilter() {
        dataGrid.setVisibleRange(new Range(0, PageRequest.DEFAULT_PAGE_LENGTH));
        refresh();
    }

    private ServingRule selected() {
        return selectionModel.getSelected();
    }

    private void updateButtons() {
        final boolean one = selected() != null;
        improveButton.setEnabled(one);
        hintButton.setEnabled(one);
        retractButton.setEnabled(one);
        acceptButton.setEnabled(one && selected().isProvisional());
        openButton.setEnabled(one && selected().getFragment() != null);
    }

    private void initTableColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ServingRule rule) ->
                        text(NullSafe.get(rule.getDoc(), DocRef::getName))).build(),
                DataGridUtil.headingBuilder("Document")
                        .withToolTip("Whose rule it is. The view is over every document a person may "
                                     + "read.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ServingRule rule) -> text(rule.getShapeId())).build(),
                DataGridUtil.headingBuilder("Shape")
                        .withToolTip("The learning key's values this rule was learned for.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ServingRule rule) ->
                        text(NullSafe.get(rule.getFragment(), DocRef::getName))).build(),
                DataGridUtil.headingBuilder("Serving")
                        .withToolTip("The pipeline fragment it binds: what every stream of this shape is "
                                     + "processed by.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ServingRule rule) -> text(state(rule))).build(),
                DataGridUtil.headingBuilder("State")
                        .withToolTip("Serving on a judgement, serving provisionally until enough "
                                     + "records arrive to make one (§6), or frozen by a pin (§7.3).")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ServingRule rule) ->
                        text(score(rule.getRollingScore()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Scoring")
                        .withToolTip("What it has been scoring lately, over the shape's rolling memory. "
                                     + "Blank until the shape has served something.")
                        .rightAligned()
                        .build(),
                80);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ServingRule rule) ->
                        text(Integer.toString(rule.getRecords()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Records")
                        .withToolTip("How many records that score rests on: the traffic this rule "
                                     + "carries, and what the list is ordered by.")
                        .rightAligned()
                        .build(),
                80);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ServingRule rule) ->
                        text(score(rule.getPromotedScore()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Promoted at")
                        .withToolTip("What it scored on the records it was promoted on. A rolling score "
                                     + "well below this is a feed that has moved.")
                        .rightAligned()
                        .build(),
                80);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ServingRule rule) ->
                        text(ClientDateUtil.toISOString(rule.getPromotedTimeMs()))).build(),
                DataGridUtil.headingBuilder("Promoted")
                        .withToolTip("When it took over.")
                        .build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ServingRule rule) ->
                        text(rule.getGuidance() == 0
                                ? ""
                                : Integer.toString(rule.getGuidance()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Said")
                        .withToolTip("How many things a supervisor has told the learning about this "
                                     + "shape, so that you can see whether the last hint was acted on "
                                     + "before giving another.")
                        .rightAligned()
                        .build(),
                60);
    }

    /// What a person may do to the row depends on this, so it is on the row.
    private static String state(final ServingRule rule) {
        final StringBuilder state = new StringBuilder(rule.isProvisional()
                ? "Provisional"
                : "Serving");
        if (rule.isPinned()) {
            state.append(", pinned");
        }
        return state.toString();
    }

    private static String score(final Double score) {
        return score == null
                ? ""
                : Double.toString(Math.round(score * 1000.0) / 1000.0);
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(NullSafe.string(value));
    }
}
