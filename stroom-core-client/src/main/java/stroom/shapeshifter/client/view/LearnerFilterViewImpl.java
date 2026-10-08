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

import stroom.shapeshifter.client.presenter.LearnerFilterPresenter.LearnerFilterView;
import stroom.shapeshifter.shared.LearnerState;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class LearnerFilterViewImpl extends ViewImpl implements LearnerFilterView {

    private final Widget widget;
    private final Map<LearnerState, CustomCheckBox> stateBoxes = new EnumMap<>(LearnerState.class);

    @UiField
    SimplePanel document;
    @UiField
    FlowPanel states;
    @UiField
    CustomCheckBox showDeletedPipelines;

    @Inject
    public LearnerFilterViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        for (final LearnerState state : LearnerState.values()) {
            final CustomCheckBox box = new CustomCheckBox();
            box.setLabel(state.getDisplayValue());
            stateBoxes.put(state, box);
            states.add(box);
        }
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setDocumentView(final View view) {
        document.setWidget(view.asWidget());
    }

    @Override
    public List<LearnerState> getStates() {
        final List<LearnerState> ticked = new ArrayList<>();
        stateBoxes.forEach((status, box) -> {
            if (Boolean.TRUE.equals(box.getValue())) {
                ticked.add(status);
            }
        });
        // Every state ticked is the same question as none ticked, and the query reads an empty list as
        // "not narrowed by state": said as the smaller of the two, so that the screen does not report
        // itself as filtered when it is showing everything.
        return ticked.size() == stateBoxes.size()
                ? List.of()
                : ticked;
    }

    @Override
    public void setStates(final List<LearnerState> states) {
        stateBoxes.forEach((status, box) -> box.setValue(states != null && states.contains(status)));
    }


    @Override
    public boolean isShowDeletedPipelines() {
        return Boolean.TRUE.equals(showDeletedPipelines.getValue());
    }

    @Override
    public void setShowDeletedPipelines(final boolean show) {
        showDeletedPipelines.setValue(show);
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, LearnerFilterViewImpl> {

    }
}
