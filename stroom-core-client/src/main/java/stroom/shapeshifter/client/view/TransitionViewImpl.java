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

package stroom.shapeshifter.client.view;

import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.TransitionPresenter;
import stroom.shapeshifter.client.presenter.TransitionPresenter.TransitionView;
import stroom.shapeshifter.shared.StepOutcome;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.ArrayList;
import java.util.List;

public class TransitionViewImpl extends ViewImpl implements TransitionView {

    private final Widget widget;

    @UiField
    SelectionBox<StepOutcome> outcome;
    @UiField
    SelectionBox<String> target;

    @Inject
    public TransitionViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        outcome.addItems(StepOutcome.values());
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public StepOutcome getOutcome() {
        return outcome.getValue();
    }

    @Override
    public void setOutcome(final StepOutcome outcome) {
        this.outcome.setValue(outcome);
    }

    @Override
    public void setTargets(final List<String> steps) {
        final List<String> targets = new ArrayList<>(steps);
        // The two places that are not steps, last: the end of the plan, and giving the attempt up.
        targets.add(TransitionPresenter.END);
        targets.add(TransitionPresenter.ABANDON);
        target.clear();
        target.addItems(targets);
    }

    @Override
    public String getTarget() {
        return target.getValue();
    }

    @Override
    public void setTarget(final String target) {
        this.target.setValue(target);
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, TransitionViewImpl> {

    }
}
