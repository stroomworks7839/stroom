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

import stroom.shapeshifter.client.presenter.PlanStepPresenter.PlanStepView;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.PlanStep;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepGuard;
import stroom.util.shared.NullSafe;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;

/**
 * One step of a learning plan as a form (A37, design 01 §10.2, §12 item 24).
 * <p>
 * Everything that has a closed list is chosen from it — the kind, the role, the guard, the checks, and
 * both ends of every transition — so that nothing can be typed here that the server would refuse. That
 * is the whole reason the editor exists: the text grammar is exact and unforgiving, and a person
 * learning it by having a save rejected is a person learning it the hard way.
 * <p>
 * Two things are still typed, and both are checked: the id, which is a word, and the two limits, which
 * are numbers with a floor of one.
 */
public class PlanStepPresenter extends MyPresenterWidget<PlanStepView> {

    private final TransitionListPresenter transitionListPresenter;

    @Inject
    public PlanStepPresenter(final EventBus eventBus,
                             final PlanStepView view,
                             final TransitionListPresenter transitionListPresenter) {
        super(eventBus, view);
        this.transitionListPresenter = transitionListPresenter;
        view.setTransitionsView(transitionListPresenter.getView());
    }

    /**
     * @param steps The ids of every step of the plan, which is what a transition may go to.
     */
    public void read(final PlanStep step, final List<String> steps, final boolean readOnly) {
        getView().setId(NullSafe.string(step.getId()));
        getView().setKind(step.getKind());
        getView().setRole(step.getRole());
        getView().setWhen(step.getWhen());
        getView().setCandidates(step.getCandidates());
        getView().setKinds(step.getKinds());
        getView().setChecks(step.getChecks());
        // Read after the kind, since the form follows it: only a CONFIGURE step takes a role, and the
        // form greys the role out rather than letting a person set one the constructor will refuse.
        getView().setReadOnly(readOnly);
        transitionListPresenter.read(step.getTransitions(), steps, readOnly);
    }

    /**
     * The step as the form has it.
     *
     * @throws IllegalArgumentException As {@link PlanStep}'s own constructor does, for anything the form
     *                                  cannot stop a person writing — an id that is not a word, a limit
     *                                  below one. The caller shows it beside the field.
     */
    public PlanStep write() {
        final String id = getView().getId().trim();
        return new PlanStep(
                id.isEmpty()
                        ? null
                        : id,
                getView().getKind(),
                QuestionKind.CONFIGURE == getView().getKind()
                        ? getView().getRole()
                        : null,
                getView().getWhen(),
                getView().getCandidates(),
                getView().getKinds(),
                getView().getChecks(),
                transitionListPresenter.write());
    }


    // --------------------------------------------------------------------------------


    public interface PlanStepView extends View {

        String getId();

        void setId(String id);

        QuestionKind getKind();

        void setKind(QuestionKind kind);

        /**
         * Null unless the kind is {@code CONFIGURE}, which is the only kind that takes one.
         */
        ConfigureRole getRole();

        void setRole(ConfigureRole role);

        StepGuard getWhen();

        void setWhen(StepGuard when);

        Integer getCandidates();

        void setCandidates(Integer candidates);

        Integer getKinds();

        void setKinds(Integer kinds);

        /**
         * The checks that judge this step; empty for the kind's own.
         */
        List<Check> getChecks();

        void setChecks(List<Check> checks);

        void setTransitionsView(View view);

        void setReadOnly(boolean readOnly);
    }
}
