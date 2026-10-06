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
import stroom.shapeshifter.shared.LearningMode;

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
    SelectionBox<LearningMode> learningMode;

    @Inject
    public ShapeshifterAiSettingsViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        learningMode.addItems(LearningMode.values());
    }

    @Override
    public Widget asWidget() {
        return widget;
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
    public void onReadOnly(final boolean readOnly) {
        learningMode.setEnabled(!readOnly);
    }

    private void fireChange() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    @UiHandler("learningMode")
    public void onLearningMode(final ValueChangeEvent<LearningMode> event) {
        fireChange();
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, ShapeshifterAiSettingsViewImpl> {

    }
}
