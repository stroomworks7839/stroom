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

import stroom.shapeshifter.client.presenter.TransitionPresenter.TransitionView;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Transition;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a step goes on one outcome (A37, design 01 §10.2): {@code on <outcome> goto <step>}.
 * <p>
 * Both ends are chosen and neither is typed. The outcomes are a closed list the checks produce, and the
 * targets are the plan's own steps — so a transition cannot name an outcome nothing raises or a step
 * that is not there, which is the whole point of editing the graph rather than its text.
 */
public class TransitionPresenter extends MyPresenterWidget<TransitionView> {

    /**
     * What a transition may go to besides a step: the end of the plan, and giving the attempt up. Both
     * are places rather than steps, so they are offered beside the step ids rather than among them.
     */
    public static final String END = Transition.END;
    public static final String ABANDON = Transition.ABANDON;

    @Inject
    public TransitionPresenter(final EventBus eventBus, final TransitionView view) {
        super(eventBus, view);
    }

    /**
     * @param steps The ids of the plan's steps, which is what a target may be.
     */
    public void read(final Transition transition, final List<String> steps) {
        final String target = transition.getGoTo() == null
                ? ABANDON
                : transition.getGoTo();
        final List<String> targets = new ArrayList<>(steps);
        if (!END.equals(target) && !ABANDON.equals(target) && !targets.contains(target)) {
            // A step this transition names that is not there any more — renamed, or removed. Offered
            // anyway, so that opening the transition shows what it says: dropped from the list it would
            // come back blank, and a blank target is *abandon*, so merely looking at a dangling
            // transition would quietly turn it into one that gives the attempt up. It stays wrong, the
            // plan's own check goes on saying so, and the person decides what it should be.
            targets.add(target);
        }
        getView().setTargets(targets);
        getView().setOutcome(transition.getOn());
        getView().setTarget(target);
    }

    public Transition write() {
        final String target = getView().getTarget();
        return new Transition(getView().getOutcome(), ABANDON.equals(target)
                ? null
                : target);
    }


    // --------------------------------------------------------------------------------


    public interface TransitionView extends View {

        StepOutcome getOutcome();

        void setOutcome(StepOutcome outcome);

        /**
         * The steps this transition may go to: the plan's own, plus the end of it and giving up.
         */
        void setTargets(List<String> steps);

        String getTarget();

        void setTarget(String target);
    }
}
