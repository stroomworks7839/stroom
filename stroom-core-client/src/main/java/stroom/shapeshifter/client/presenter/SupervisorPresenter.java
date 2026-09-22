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

import stroom.content.client.presenter.ContentTabPresenter;
import stroom.data.table.client.Refreshable;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.svg.client.IconColour;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.NullSafe;
import stroom.widget.util.client.MultiSelectionModel;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

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

    private final SupervisorListPresenter listPresenter;
    private final SupervisorTurnsPresenter turnsPresenter;

    @Inject
    public SupervisorPresenter(final EventBus eventBus,
                               final SupervisorView view,
                               final SupervisorListPresenter listPresenter,
                               final SupervisorTurnsPresenter turnsPresenter) {
        super(eventBus, view);
        this.listPresenter = listPresenter;
        this.turnsPresenter = turnsPresenter;

        setInSlot(ATTEMPT_LIST, listPresenter);
        setInSlot(TURN_LIST, turnsPresenter);
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
        }));
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
    }


    // --------------------------------------------------------------------------------


    public interface SupervisorView extends View {

    }
}
