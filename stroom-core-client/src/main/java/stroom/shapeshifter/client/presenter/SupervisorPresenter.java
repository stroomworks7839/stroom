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
import stroom.shapeshifter.shared.GrantRequest;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.svg.client.IconColour;
import stroom.svg.client.SvgPresets;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MultiSelectionModel;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The Supervisor of ruling A28: every Shapeshifter AI document's attempts in one place, rather than a
 * tab on each document. The list above, and below it the conversation of whichever attempt is selected,
 * turn by turn — what was asked, what was answered, by whom, and what the answer scored.
 */
public class SupervisorPresenter extends ContentTabPresenter<SupervisorPresenter.SupervisorView>
        implements Refreshable {

    public static final String TAB_TYPE = "ShapeshifterAiSupervisor";
    public static final String ATTEMPT_LIST = "ATTEMPT_LIST";
    public static final String TURN_LIST = "TURN_LIST";
    public static final String SERVING = "SERVING";
    public static final String BACKLOG = "BACKLOG";

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);
    private static final ShapeshifterAiResource DOCUMENT_RESOURCE = GWT.create(ShapeshifterAiResource.class);

    private final SupervisorListPresenter listPresenter;
    private final SupervisorTurnsPresenter turnsPresenter;
    private final SupervisorServingPresenter servingPresenter;
    private final SupervisorGuidancePresenter guidancePresenter;
    private final SupervisorBacklogPresenter backlogPresenter;
    private final RestFactory restFactory;
    /// The selected attempt's path over its document's flow (A88), drawn from the turns the pane fetched
    /// and the flow fetched once per document.
    private final FlowChart flowChart = new FlowChart();
    private final Map<String, ShapeshifterAiDoc> documents = new HashMap<>();
    private final ButtonView approveButton;
    private final ButtonView rejectButton;
    private final ButtonView relearnButton;
    private final ButtonView hintButton;
    private final ButtonView grantButton;
    private final ButtonView exhaustButton;

    @Inject
    public SupervisorPresenter(final EventBus eventBus,
                               final SupervisorView view,
                               final SupervisorListPresenter listPresenter,
                               final SupervisorTurnsPresenter turnsPresenter,
                               final SupervisorServingPresenter servingPresenter,
                               final SupervisorGuidancePresenter guidancePresenter,
                               final SupervisorBacklogPresenter backlogPresenter,
                               final RestFactory restFactory) {
        super(eventBus, view);
        this.listPresenter = listPresenter;
        this.turnsPresenter = turnsPresenter;
        this.servingPresenter = servingPresenter;
        this.guidancePresenter = guidancePresenter;
        this.backlogPresenter = backlogPresenter;
        this.restFactory = restFactory;

        setInSlot(ATTEMPT_LIST, listPresenter);
        // A turn answered reopens the attempt and takes its claim back (A28), so the row it was
        // answered from is showing a status that is no longer true.
        turnsPresenter.setOnAmended(amended -> {
            listPresenter.getSelectionModel().clear();
            updateButtons();
            refresh();
        });
        setInSlot(TURN_LIST, turnsPresenter);
        turnsPresenter.setOnDetail(this::drawPath);
        flowChart.fitToPane();
        view.setFlowChart(flowChart);
        setInSlot(SERVING, servingPresenter);
        setInSlot(BACKLOG, backlogPresenter);

        // The decisions A28 puts beside the attempt they are about. Approve and Reject are review
        // mode's (A25), offered here as well as on the document's Routing tab because a person watching
        // the feature meets the draft here first. Re-learn is for a learner that gave up or bound
        // badly: it asks again, from the beginning.
        approveButton = listPresenter.add(SvgPresets.TICK.title("Approve what this attempt drafted"));
        rejectButton = listPresenter.add(SvgPresets.DISABLE.title("Reject what this attempt drafted"));
        relearnButton = listPresenter.add(SvgPresets.RERUN.title(
                "Learn again: the next stream opens a session while the rule goes on serving it"));
        // A46's message: neither an answer to a turn nor a decision about one. It attaches to the
        // attempt's learner, so a person reading a transcript and seeing what the model did not know can
        // say so there and then, and whatever is asked next carries it.
        hintButton = listPresenter.add(SvgPresets.EDIT.title(
                "What has been said to this attempt's learner, and say something else"));
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
        registerHandler(relearnButton.addClickHandler(event -> relearn()));
        registerHandler(hintButton.addClickHandler(event -> hint()));
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

    /// Reject what an attempt drafted (A25): the rule goes and the learner gives up with the reason.
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

    /// Ask again, from the beginning (A28): for a learner that gave up, or bound something a person is
    /// not content with. What the attempt learned before is not thrown away — it is a record — but the
    /// learner is asked afresh.
    private void relearn() {
        final SupervisorAttempt attempt = selected();
        if (attempt != null) {
            ConfirmEvent.fire(this, "Learn again? The learner learns afresh from its next stream, "
                                    + "and what is bound now keeps serving until then. Streams that "
                                    + "errored while it had given up are ordinary error streams: "
                                    + "reprocess them on the pipeline to put them through.",
                    ok -> {
                        if (ok) {
                            act(resource -> resource.relearn(attempt.getId()));
                        }
                    });
        }
    }

    /// Tell the selected attempt's learner something (A46).
    ///
    /// It attaches to the learner and not to the attempt, which is the ruling's first decision: what a
    /// person knows is about the feed, not about turn 7 of attempt 412. So nothing has to be timed,
    /// nothing is refused for arriving at the wrong moment, and a hint outlives the attempt it was
    /// written from — the relearning of A29, months later, carries it too.
    ///
    /// It asks for nothing to be run. A person reading a transcript and seeing what the model did not
    /// know should be able to write it down there and then.
    private void hint() {
        final SupervisorAttempt attempt = selected();
        if (attempt != null) {
            guidancePresenter.show(attempt.getDoc().getUuid(), attempt.getLearner(),
                    servingPresenter::refresh);
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
            flowChart.clear();
            return;
        }
        final String uuid = detail.getDoc().getUuid();
        final ShapeshifterAiDoc known = documents.get(uuid);
        if (known != null) {
            flowChart.show(known.getFlow().getSteps(), null, detail.getTurns(), null);
            return;
        }
        restFactory
                .create(DOCUMENT_RESOURCE)
                .method(resource -> resource.fetch(uuid))
                .onSuccess(doc -> {
                    documents.put(uuid, doc);
                    flowChart.show(doc.getFlow().getSteps(), null, detail.getTurns(), null);
                })
                .onFailure(error -> flowChart.clear())
                .taskMonitorFactory(this)
                .exec();
    }

    /// The selected attempt where it is parked for a person, and null otherwise.
    private SupervisorAttempt parked() {
        final SupervisorAttempt attempt = selected();
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
    /// is offered, and rejecting it would take the rule that was just promoted out of the routing table
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
                    // What serves with it: approving a draft puts a rule in front of its learner.
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

    /// The selected attempt where it is one a person may approve or reject, and null otherwise: an
    /// attempt that drafted nothing has nothing to decide about.
    private SupervisorAttempt awaitingReview() {
        final SupervisorAttempt attempt = selected();
        return attempt != null && AttemptStatus.AWAITING_REVIEW == attempt.getStatus()
                ? attempt
                : null;
    }

    private void updateButtons() {
        final boolean review = awaitingReview() != null;
        approveButton.setEnabled(review);
        rejectButton.setEnabled(review);
        relearnButton.setEnabled(selected() != null);
        hintButton.setEnabled(selected() != null);
        grantButton.setEnabled(parked() != null);
        exhaustButton.setEnabled(parked() != null);
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
        return "Shapeshifter AI Attempts";
    }

    @Override
    public String getType() {
        return TAB_TYPE;
    }

    @Override
    public void refresh() {
        listPresenter.refresh();
        servingPresenter.refresh();
        backlogPresenter.refresh();
    }


    // --------------------------------------------------------------------------------


    public interface SupervisorView extends View {

        /// The document's flow drawn beside the turns, with the attempt's path over it (A88).
        void setFlowChart(Widget chart);
    }
}
