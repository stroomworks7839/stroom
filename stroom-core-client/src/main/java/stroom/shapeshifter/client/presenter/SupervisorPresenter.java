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
import stroom.content.client.presenter.ContentTabPresenter;
import stroom.data.table.client.Refreshable;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.view.FlowChart;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.GrantRequest;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.svg.client.IconColour;
import stroom.svg.client.SvgPresets;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ButtonView;
import stroom.widget.tab.client.presenter.LinkTabsLayoutView;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;
import stroom.widget.util.client.MultiSelectionModel;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The Supervisor (A28, design 06 A115): three levels, master to detail. The learning pipelines above, with
 * the actions on a pipeline's learner and its binding; beneath them the selected pipeline's sessions, with
 * the actions on a session, and beside them on a second tab the streams waiting on it; and beneath those
 * the selected session's conversation, turn by turn, beside its path drawn over the flow (A88).
 */
public class SupervisorPresenter extends ContentTabPresenter<SupervisorPresenter.SupervisorView>
        implements Refreshable {

    public static final String TAB_TYPE = "ShapeshifterAiSupervisor";
    public static final String LEARNERS = "LEARNERS";
    public static final String TURN_LIST = "TURN_LIST";
    private static final TabData SESSIONS_TAB = new TabDataImpl("Sessions");
    private static final TabData WAITING_TAB = new TabDataImpl("Waiting streams");

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);
    private static final ShapeshifterAiResource DOCUMENT_RESOURCE = GWT.create(ShapeshifterAiResource.class);

    private final SupervisorLearnersPresenter learnersPresenter;
    private final SupervisorListPresenter listPresenter;
    private final SupervisorWaitingPresenter waitingPresenter;
    private final SupervisorTurnsPresenter turnsPresenter;
    private final RestFactory restFactory;
    /// The selected attempt's path over its document's flow (A88), drawn from the turns the pane fetched
    /// and the flow fetched once per document.
    private final FlowChart flowChart = new FlowChart();
    private final Map<String, ShapeshifterAiDoc> documents = new HashMap<>();
    /// The steps drawn now, so that a step chosen on the chart can be found among the turns.
    private List<FlowStep> steps = List.of();
    private final ButtonView approveButton;
    private final ButtonView rejectButton;
    private final ButtonView grantButton;
    private final ButtonView exhaustButton;

    @Inject
    public SupervisorPresenter(final EventBus eventBus,
                               final SupervisorView view,
                               final SupervisorLearnersPresenter learnersPresenter,
                               final SupervisorListPresenter listPresenter,
                               final SupervisorWaitingPresenter waitingPresenter,
                               final SupervisorTurnsPresenter turnsPresenter,
                               final RestFactory restFactory) {
        super(eventBus, view);
        this.learnersPresenter = learnersPresenter;
        this.listPresenter = listPresenter;
        this.waitingPresenter = waitingPresenter;
        this.turnsPresenter = turnsPresenter;
        this.restFactory = restFactory;

        setInSlot(LEARNERS, learnersPresenter);
        // Giving a learner up or sending it back changes its sessions and waiting streams as well as its row.
        learnersPresenter.setOnActed(this::refresh);
        view.getTabBar().addTab(SESSIONS_TAB);
        view.getTabBar().addTab(WAITING_TAB);
        selectTab(SESSIONS_TAB);
        // A turn answered reopens the attempt and takes its claim back (A28), so the row it was
        // answered from is showing a status that is no longer true.
        turnsPresenter.setOnAmended(amended -> {
            listPresenter.getSelectionModel().clear();
            updateButtons();
            refresh();
        });
        setInSlot(TURN_LIST, turnsPresenter);
        turnsPresenter.setOnDetail(this::drawPath);
        // The transcript and the chart choose each other (A116): a turn lights its step and rings its visit;
        // a step brings its first turn into view, and a visit its own.
        turnsPresenter.setOnTurnSelected(turn -> flowChart.setSelectedTurn(NullSafe.get(turn,
                SupervisorTurn::getNumber)));
        flowChart.setOnSelect(step -> {
            flowChart.setSelected(step);
            if (step >= 0 && step < steps.size()) {
                turnsPresenter.showStep(steps.get(step).effectiveId());
            }
        });
        flowChart.setOnTurnSelect(number -> {
            flowChart.setSelectedTurn(number);
            turnsPresenter.showTurn(number);
        });
        flowChart.fitToPane();
        view.setFlowChart(flowChart);

        // The decisions A28 puts beside the attempt they are about. Approve and Reject are review
        // mode's (A25), offered here because this is where a person watching the feature meets the
        // draft. What acts on the learner rather than the session — learn again, give up, guidance — is on
        // the pipeline's row above (A115, A141).
        approveButton = listPresenter.add(SvgPresets.TICK.title("Approve what this attempt drafted"));
        rejectButton = listPresenter.add(SvgPresets.DISABLE.title("Reject what this attempt drafted"));
        // A65's first action on a session parked for a person: more to spend, granted to this attempt
        // alone (A77), which the worker then carries on with.
        grantButton = listPresenter.add(SvgPresets.ADD.title(
                "Give this parked attempt more to spend, and carry it on"));
        // A65's other action on a parked session: no more trying — the next transform question is not
        // put, and the flow goes to its exhaustion step, where what cannot be translated is admitted.
        exhaustButton = listPresenter.add(SvgPresets.FAST_FORWARD_BLUE.title(
                "Send this parked attempt to the exhaustion step, and carry it on"));
        updateButtons();
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(learnersPresenter.getSelectionModel().addSelectionHandler(event -> {
            // A pipeline chosen: its sessions and waiting streams beneath it, and no session yet.
            final SupervisorLearner chosen = learnersPresenter.getSelectionModel().getSelected();
            listPresenter.setLearner(NullSafe.get(chosen, SupervisorLearner::getLearner));
            waitingPresenter.setLearner(chosen);
            turnsPresenter.read(null);
            updateButtons();
        }));
        registerHandler(getView().getTabBar().addSelectionHandler(event -> selectTab(event.getSelectedItem())));

        registerHandler(listPresenter.getSelectionModel().addSelectionHandler(event -> {
            final SupervisorAttempt selected = NullSafe.get(
                    listPresenter,
                    SupervisorListPresenter::getSelectionModel,
                    MultiSelectionModel::getSelected);
            turnsPresenter.read(selected);
            updateButtons();
        }));
        registerHandler(approveButton.addClickHandler(event -> approve()));
        registerHandler(rejectButton.addClickHandler(event -> reject()));
        registerHandler(grantButton.addClickHandler(event -> grant()));
        registerHandler(exhaustButton.addClickHandler(event -> exhaust()));
    }

    /// Approve what an attempt drafted (A25): the promotion it was waiting for, and the release of every
    /// stream that waited with it.
    private void approve() {
        final SupervisorAttempt attempt = awaitingReview();
        if (attempt != null) {
            ConfirmEvent.fire(this,
                    "Approve what this attempt drafted? It binds the fragment for its learner, and every "
                    + "stream that waited for it is tried again.",
                    ok -> {
                        if (ok) {
                            act(resource -> resource.approve(attempt.getId()));
                        }
                    });
        }
    }

    /// Reject what an attempt drafted (A25): the binding goes and the learner gives up with the reason.
    /// The reason is asked for rather than assumed — it is what the learner carries afterwards.
    private void reject() {
        final SupervisorAttempt attempt = awaitingReview();
        if (attempt != null) {
            PromptEvent.fire(this, "Why is this being rejected? Its learner gives up with the reason, "
                                   + "and not learned again until somebody says otherwise.", "",
                    reason -> {
                        if (!NullSafe.isBlankString(reason)) {
                            act(resource -> resource.reject(attempt.getId(), new RejectRequest(reason)));
                        }
                    });
        }
    }

    /// Give a parked session more to spend (A65, A77): tokens, asked for here, recorded against the
    /// attempt alone and not the document, and the worker carries the session on with them.
    private void grant() {
        final SupervisorAttempt attempt = parked();
        if (attempt != null) {
            PromptEvent.fire(this, "How many more tokens may this attempt spend? The grant is this "
                                   + "attempt's alone; the document's budget is not changed. It has spent "
                                   + attempt.getTokensSpent() + " and been granted " + attempt.getGrantedTokens()
                                   + " so far.", "",
                    tokens -> {
                        if (NullSafe.isBlankString(tokens)) {
                            return;
                        }
                        final long granted;
                        try {
                            granted = Long.parseLong(tokens.trim());
                        } catch (final NumberFormatException e) {
                            AlertEvent.fireError(this, "'" + tokens + "' is not a number of tokens", null);
                            return;
                        }
                        if (granted <= 0) {
                            AlertEvent.fireError(this, "A grant is more than nothing", null);
                            return;
                        }
                        act(resource -> resource.grant(attempt.getId(), new GrantRequest(granted, 0L)));
                    });
        }
    }

    /// Send a parked session to the exhaustion step (A65, A76): the worker carries it on, puts no further
    /// transform question to the model, and asks for the admitted form for what the stream still wants.
    private void exhaust() {
        final SupervisorAttempt attempt = parked();
        if (attempt != null) {
            ConfirmEvent.fire(this,
                    "Send this attempt to the exhaustion step? The model is not asked for another "
                    + "translation: it is asked to admit, as marked Unknown events, the records the stream "
                    + "still cannot translate, and the stream is served with them.",
                    ok -> {
                        if (ok) {
                            act(resource -> resource.exhaust(attempt.getId()));
                        }
                    });
        }
    }

    /// The attempt's path over its document's flow (A88): the flow is fetched once per document and the
    /// turns are the pane's; nothing is drawn where there is no attempt, or no document to draw.
    private void drawPath(final SupervisorAttempt detail) {
        if (detail == null || detail.getDoc() == null) {
            clearPath();
            return;
        }
        final String uuid = detail.getDoc().getUuid();
        final ShapeshifterAiDoc known = documents.get(uuid);
        if (known != null) {
            showPath(known, detail);
            return;
        }
        restFactory
                .create(DOCUMENT_RESOURCE)
                .method(resource -> resource.fetch(uuid))
                .onSuccess(doc -> {
                    documents.put(uuid, doc);
                    showPath(doc, detail);
                })
                .onFailure(error -> clearPath())
                .taskMonitorFactory(this)
                .exec();
    }

    private void showPath(final ShapeshifterAiDoc doc, final SupervisorAttempt detail) {
        steps = NullSafe.list(doc.getFlow().getSteps());
        flowChart.show(steps, null, detail.getTurns(), null);
    }

    private void clearPath() {
        steps = List.of();
        flowChart.clear();
    }

    /// The selected attempt where it is parked for a person, and null otherwise.
    private SupervisorAttempt parked() {
        final SupervisorAttempt attempt = live();
        return attempt != null && AttemptStatus.PARKED == attempt.getStatus()
                ? attempt
                : null;
    }

    /// One action, one call, and the list read again afterwards: an attempt decided here changes the
    /// row it was decided from, and a person watching should see it change.
    ///
    /// The selection goes first, and that is not tidiness. A selection holds the row *object* it was
    /// made from, and refreshing the list builds new ones without touching it — so an attempt that has
    /// just been approved would still answer `AWAITING_REVIEW` to the guard that decides whether Reject
    /// is offered, and rejecting it would take back the binding that was just promoted
    /// and give its learner up. What is shown afterwards is the decided attempt, which is what the person
    /// was looking at; what is *selected* is nothing, until they choose again from the list as it now is.
    private void act(final Function<SupervisorResource, SupervisorAttempt> call) {
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(call::apply)
                .onSuccess(decided -> {
                    listPresenter.getSelectionModel().clear();
                    updateButtons();
                    turnsPresenter.read(decided);
                    // What serves with it, and where its pipeline now stands: approving a draft puts a binding
                    // in front of its learner, and rejecting one gives it up.
                    refresh();
                })
                .taskMonitorFactory(this)
                .exec();
    }

    private SupervisorAttempt selected() {
        return NullSafe.get(
                listPresenter,
                SupervisorListPresenter::getSelectionModel,
                MultiSelectionModel::getSelected);
    }

    /// The selected attempt where its pipeline has not been deleted, and null otherwise: a deleted
    /// pipeline's attempts are kept to be read, and nothing is offered that would change them (design 06
    /// A137).
    private SupervisorAttempt live() {
        final SupervisorAttempt attempt = selected();
        return attempt != null && !attempt.isPipelineDeleted()
                ? attempt
                : null;
    }

    /// The selected attempt where it is one a person may approve or reject, and null otherwise: an
    /// attempt that drafted nothing has nothing to decide about.
    private SupervisorAttempt awaitingReview() {
        final SupervisorAttempt attempt = live();
        return attempt != null && AttemptStatus.AWAITING_REVIEW == attempt.getStatus()
                ? attempt
                : null;
    }

    private void updateButtons() {
        final boolean review = awaitingReview() != null;
        approveButton.setEnabled(review);
        rejectButton.setEnabled(review);
        grantButton.setEnabled(parked() != null);
        // Sent once is sent (A65): the mark stands until the attempt closes, and the list says so.
        exhaustButton.setEnabled(parked() != null && !parked().isExhaustNext());
    }

    /// Show a tab's list beneath the pipelines: the sessions, or the waiting streams.
    private void selectTab(final TabData tab) {
        final MyPresenterWidget<?> content = WAITING_TAB.equals(tab)
                ? waitingPresenter
                : listPresenter;
        getView().getLayerContainer().show(content);
        getView().getTabBar().selectTab(tab);
    }

    @Override
    public SvgImage getIcon() {
        return SvgImage.AI;
    }

    @Override
    public IconColour getIconColour() {
        return IconColour.GREY;
    }

    @Override
    public String getLabel() {
        return "Shapeshifter AI Supervisor";
    }

    @Override
    public String getType() {
        return TAB_TYPE;
    }

    @Override
    public void refresh() {
        learnersPresenter.refresh();
        listPresenter.refresh();
        waitingPresenter.refresh();
    }


    // --------------------------------------------------------------------------------


    public interface SupervisorView extends LinkTabsLayoutView {

        /// The document's flow drawn beside the turns, with the attempt's path over it (A88).
        void setFlowChart(Widget chart);
    }
}
