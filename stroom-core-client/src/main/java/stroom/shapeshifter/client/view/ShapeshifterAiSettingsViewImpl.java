/*
 * Copyright 2016-2026 Crown Copyright
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

import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.ShapeshifterAiSettingsPresenter.ShapeshifterAiSettingsView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiSettingsUiHandlers;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.LearningMode;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

public class ShapeshifterAiSettingsViewImpl
        extends ViewWithUiHandlers<ShapeshifterAiSettingsUiHandlers>
        implements ShapeshifterAiSettingsView, ReadOnlyChangeHandler {

    private final Widget widget;

    @UiField
    SelectionBox<ExecutionMode> executionMode;
    @UiField
    SelectionBox<LearningMode> learningMode;
    @UiField
    ValueSpinner errorModeAfter;

    @Inject
    public ShapeshifterAiSettingsViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        executionMode.addItems(ExecutionMode.values());
        learningMode.addItems(LearningMode.values());
        errorModeAfter.setMin(1);
        errorModeAfter.setMax(Integer.MAX_VALUE);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public ExecutionMode getExecutionMode() {
        return executionMode.getValue();
    }

    @Override
    public void setExecutionMode(final ExecutionMode executionMode) {
        this.executionMode.setValue(executionMode);
    }

    @Override
    public LearningMode getLearningMode() {
        return learningMode.getValue();
    }

    @Override
    public void setLearningMode(final LearningMode learningMode) {
        this.learningMode.setValue(learningMode);
    }

    @Override
    public int getErrorModeAfter() {
        return errorModeAfter.getIntValue();
    }

    @Override
    public void setErrorModeAfter(final int errorModeAfter) {
        this.errorModeAfter.setValue(errorModeAfter);
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        executionMode.setEnabled(!readOnly);
        learningMode.setEnabled(!readOnly);
        errorModeAfter.setEnabled(!readOnly);
    }

    private void fireChange() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    @UiHandler("executionMode")
    public void onExecutionMode(final ValueChangeEvent<ExecutionMode> event) {
        fireChange();
    }

    @UiHandler("learningMode")
    public void onLearningMode(final ValueChangeEvent<LearningMode> event) {
        fireChange();
    }

    @UiHandler("errorModeAfter")
    public void onErrorModeAfter(final ValueChangeEvent<Long> event) {
        fireChange();
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, ShapeshifterAiSettingsViewImpl> {

    }
}
