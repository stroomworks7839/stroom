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

import stroom.alert.client.event.ConfirmEvent;
import stroom.alert.client.event.PromptEvent;
import stroom.content.client.presenter.ContentTabPresenter;
import stroom.data.table.client.Refreshable;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.svg.client.IconColour;
import stroom.svg.client.SvgPresets;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MultiSelectionModel;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.function.Function;

/**
 * The Supervisor of ruling A28: every Shapeshifter AI document's attempts in one place, rather than a
 * tab on each document. The list above, and below it the dialogue of whichever attempt is selected,
 * turn by turn — what was asked, what was answered, by whom, and what the answer scored.
 */
public class SupervisorPresenter extends ContentTabPresenter<SupervisorPresenter.SupervisorView>
        implements Refreshable {

    public static final String TAB_TYPE = "ShapeshifterAiSupervisor";
    public static final String ATTEMPT_LIST = "ATTEMPT_LIST";
    public static final String TURN_LIST = "TURN_LIST";
    public static final String LEDGER = "LEDGER";
    public static final String SERVING = "SERVING";

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final SupervisorListPresenter listPresenter;
    private final SupervisorTurnsPresenter turnsPresenter;
    private final SupervisorLedgerPresenter ledgerPresenter;
    private final SupervisorServingPresenter servingPresenter;
    private final SupervisorGuidancePresenter guidancePresenter;
    private final RestFactory restFactory;
    private final ButtonView approveButton;
    private final ButtonView rejectButton;
    private final ButtonView relearnButton;
    private final ButtonView hintButton;

    @Inject
    public SupervisorPresenter(final EventBus eventBus,
                               final SupervisorView view,
                               final SupervisorListPresenter listPresenter,
                               final SupervisorTurnsPresenter turnsPresenter,
                               final SupervisorLedgerPresenter ledgerPresenter,
                               final SupervisorServingPresenter servingPresenter,
                               final SupervisorGuidancePresenter guidancePresenter,
                               final RestFactory restFactory) {
        super(eventBus, view);
        this.listPresenter = listPresenter;
        this.turnsPresenter = turnsPresenter;
        this.ledgerPresenter = ledgerPresenter;
        this.servingPresenter = servingPresenter;
        this.guidancePresenter = guidancePresenter;
        this.restFactory = restFactory;

        setInSlot(ATTEMPT_LIST, listPresenter);
        setInSlot(TURN_LIST, turnsPresenter);
        setInSlot(LEDGER, ledgerPresenter);
        setInSlot(SERVING, servingPresenter);

        // The decisions A28 puts beside the attempt they are about. Approve and Reject are review
        // mode's (A25), offered here as well as on the document's Routing tab because a person watching
        // the feature meets the draft here first. Re-learn is for a shape that was given up or bound
        // badly: it asks again, from the beginning.
        approveButton = listPresenter.add(SvgPresets.TICK.title("Approve what this attempt drafted"));
        rejectButton = listPresenter.add(SvgPresets.DISABLE.title("Reject what this attempt drafted"));
        relearnButton = listPresenter.add(SvgPresets.RERUN.title("Learn this shape again"));
        // A46's message: neither an answer to a turn nor a decision about one. It attaches to the
        // attempt's shape, so a person reading a transcript and seeing what the model did not know can
        // say so there and then, and whatever is asked next carries it.
        hintButton = listPresenter.add(SvgPresets.EDIT.title(
                "What has been said about this attempt's shape, and say something else"));
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
    }

    /// Approve what an attempt drafted (A25): the promotion it was waiting for, and the release of every
    /// stream that waited with it.
    private void approve() {
        final SupervisorAttempt attempt = awaitingReview();
        if (attempt != null) {
            ConfirmEvent.fire(this,
                    "Approve what this attempt drafted? It binds the fragment for its shape, and every "
                    + "stream that waited for it is processed again.",
                    ok -> {
                        if (ok) {
                            act(resource -> resource.approve(attempt.getId()));
                        }
                    });
        }
    }

    /// Reject what an attempt drafted (A25): the rule goes and the shape is given up with the reason.
    /// The reason is asked for rather than assumed — it is what the shape carries afterwards.
    private void reject() {
        final SupervisorAttempt attempt = awaitingReview();
        if (attempt != null) {
            PromptEvent.fire(this, "Why is this being rejected? Its shape is given up with the reason, "
                                   + "and not learned again until somebody says otherwise.", "",
                    reason -> {
                        if (!NullSafe.isBlankString(reason)) {
                            act(resource -> resource.reject(attempt.getId(), new RejectRequest(reason)));
                        }
                    });
        }
    }

    /// Ask again, from the beginning (A28): for a shape that was given up, or bound something a person
    /// is not content with. What the attempt learned before is not thrown away — it is a record — but
    /// the shape is asked afresh.
    private void relearn() {
        final SupervisorAttempt attempt = selected();
        if (attempt != null) {
            ConfirmEvent.fire(this, "Learn this shape again? The model is asked afresh, and what is "
                                    + "bound for the shape now keeps serving until it answers.",
                    ok -> {
                        if (ok) {
                            act(resource -> resource.relearn(attempt.getId()));
                        }
                    });
        }
    }

    /// Tell the learning something about the selected attempt's shape (A46).
    ///
    /// It attaches to the shape and not to the attempt, which is the ruling's first decision: what a
    /// person knows is about the feed, not about turn 7 of attempt 412. So nothing has to be timed,
    /// nothing is refused for arriving at the wrong moment, and a hint outlives the attempt it was
    /// written from — the relearning of A29, months later, carries it too.
    ///
    /// It asks for nothing to be run. A person reading a transcript and seeing what the model did not
    /// know should be able to write it down there and then.
    private void hint() {
        final SupervisorAttempt attempt = selected();
        if (attempt != null) {
            guidancePresenter.show(attempt.getDoc().getUuid(), attempt.getShape(),
                    servingPresenter::refresh);
        }
    }

    /// One action, one call, and the list read again afterwards: an attempt decided here changes the
    /// row it was decided from, and a person watching should see it change.
    ///
    /// The selection goes first, and that is not tidiness. A selection holds the row *object* it was
    /// made from, and refreshing the list builds new ones without touching it — so an attempt that has
    /// just been approved would still answer `AWAITING_REVIEW` to the guard that decides whether Reject
    /// is offered, and rejecting it would take the rule that was just promoted out of the routing table
    /// and give its shape up. What is shown afterwards is the decided attempt, which is what the person
    /// was looking at; what is *selected* is nothing, until they choose again from the list as it now is.
    private void act(final Function<SupervisorResource, SupervisorAttempt> call) {
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(call::apply)
                .onSuccess(decided -> {
                    listPresenter.getSelectionModel().clear();
                    updateButtons();
                    turnsPresenter.read(decided);
                    // The ledger with it: approving a draft releases the streams that waited for its
                    // shape, so the row they were counted in goes.
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
        ledgerPresenter.refresh();
        servingPresenter.refresh();
    }


    // --------------------------------------------------------------------------------


    public interface SupervisorView extends View {

    }
}
