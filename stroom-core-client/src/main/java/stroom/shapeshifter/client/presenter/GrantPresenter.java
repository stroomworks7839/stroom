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
import stroom.shapeshifter.shared.GrantRequest;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.function.Consumer;

/// Give a parked session more to spend (A65, A77): more tokens over its life, more minutes for each run of
/// it, or both (design 06 A145). Granted to the one session, never to its document; what it has spent and
/// been granted so far, and what the document allows, are set out above the two fields so that a person
/// grants knowing how far over the session already is.
public class GrantPresenter extends MyPresenterWidget<GrantPresenter.GrantView> {

    @Inject
    public GrantPresenter(final EventBus eventBus, final GrantView view) {
        super(eventBus, view);
    }

    /// Ask what to grant, and hand it on when OK is pressed with something granted. A grant of nothing is
    /// refused here, with the dialog left open, rather than sent to be refused by the server.
    ///
    /// @param attempt The parked session.
    /// @param doc     Its document, for its budgets, or null where it has not been read.
    /// @param onGrant Who is handed the grant.
    public void show(final SupervisorAttempt attempt,
                     final ShapeshifterAiDoc doc,
                     final Consumer<GrantRequest> onGrant) {
        getView().setSoFar(soFar(attempt, doc));
        getView().setTokens(0L);
        getView().setMinutes(0L);
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(560, 360))
                .caption("Give this session more to spend")
                .onShow(e -> getView().focus())
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final GrantRequest grant = GrantRequest.ofMinutes(getView().getTokens(), getView().getMinutes());
                    final String problem = grant.problem();
                    if (problem != null) {
                        AlertEvent.fireError(this, problem, e::reset);
                        return;
                    }
                    e.hide();
                    onGrant.accept(grant);
                })
                .fire();
    }

    /// What the session has spent and been granted, against what its document allows.
    static String soFar(final SupervisorAttempt attempt, final ShapeshifterAiDoc doc) {
        final StringBuilder text = new StringBuilder("It has spent ").append(attempt.getTokensSpent())
                .append(" tokens");
        if (doc != null) {
            if (doc.getTokenBudget() == null || doc.getTokenBudget() == 0L) {
                text.append(", and its document sets no token budget");
            } else {
                text.append(" of its document's ").append(doc.getTokenBudget());
            }
        }
        if (attempt.getGrantedTokens() > 0L) {
            text.append(", with ").append(attempt.getGrantedTokens()).append(" more granted");
        }
        text.append(". Each run of it may take ").append(doc == null
                ? "its document's time budget"
                : duration(doc.getAttemptBudgetMs()) + " by its document");
        if (attempt.getGrantedMs() > 0L) {
            text.append(" and ").append(duration(attempt.getGrantedMs())).append(" more granted");
        }
        return text.append(". Tokens are counted over the session's life; time over each run, so a run "
                           + "carried on has the whole of it again.").toString();
    }

    /// Minutes where the time is whole minutes, seconds otherwise.
    static String duration(final long ms) {
        return ms % 60_000L == 0L
                ? (ms / 60_000L) + " min"
                : (ms / 1_000L) + " s";
    }


    // --------------------------------------------------------------------------------


    public interface GrantView extends View {

        /// @param text What the session has spent and been granted, against its document's budgets.
        void setSoFar(String text);

        /// @return More tokens to grant, nought for none.
        long getTokens();

        /// @param tokens More tokens to grant.
        void setTokens(long tokens);

        /// @return More minutes to grant each run, nought for none.
        long getMinutes();

        /// @param minutes More minutes to grant each run.
        void setMinutes(long minutes);

        /// Put the cursor in the first field.
        void focus();
    }
}
