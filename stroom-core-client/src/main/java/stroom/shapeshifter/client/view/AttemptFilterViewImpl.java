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
import stroom.shapeshifter.client.presenter.AttemptFilterPresenter.AttemptFilterView;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class AttemptFilterViewImpl extends ViewImpl implements AttemptFilterView {

    /**
     * What "any" reads as in a picker that may be left alone. A null value on the criteria, which the
     * query takes as not narrowed by this at all.
     */
    private static final String ANY = "Any";

    private final Widget widget;
    private final Map<AttemptStatus, CustomCheckBox> statusBoxes = new EnumMap<>(AttemptStatus.class);

    @UiField
    SimplePanel document;
    @UiField
    TextBox feed;
    @UiField
    TextBox shape;
    @UiField
    SelectionBox<ExecutionMode> executionMode;
    @UiField
    SelectionBox<PromotionMode> promotionMode;
    @UiField
    FlowPanel statuses;

    @Inject
    public AttemptFilterViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        executionMode.setNonSelectString(ANY);
        executionMode.addItems(ExecutionMode.values());
        promotionMode.setNonSelectString(ANY);
        promotionMode.addItems(PromotionMode.values());
        for (final AttemptStatus status : AttemptStatus.values()) {
            final CustomCheckBox box = new CustomCheckBox();
            box.setLabel(status.getDisplayValue());
            statusBoxes.put(status, box);
            statuses.add(box);
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
    public String getFeed() {
        return feed.getValue();
    }

    @Override
    public void setFeed(final String feed) {
        this.feed.setValue(feed);
    }

    @Override
    public String getShape() {
        return shape.getValue();
    }

    @Override
    public void setShape(final String shape) {
        this.shape.setValue(shape);
    }

    @Override
    public ExecutionMode getExecutionMode() {
        return executionMode.getValue();
    }

    @Override
    public void setExecutionMode(final ExecutionMode mode) {
        this.executionMode.setValue(mode);
    }

    @Override
    public PromotionMode getPromotionMode() {
        return promotionMode.getValue();
    }

    @Override
    public void setPromotionMode(final PromotionMode mode) {
        this.promotionMode.setValue(mode);
    }

    @Override
    public List<AttemptStatus> getStatuses() {
        final List<AttemptStatus> ticked = new ArrayList<>();
        statusBoxes.forEach((status, box) -> {
            if (Boolean.TRUE.equals(box.getValue())) {
                ticked.add(status);
            }
        });
        // Every status ticked is the same question as none ticked, and the query reads an empty list as
        // "not narrowed by status": said as the smaller of the two, so that the screen does not report
        // itself as filtered when it is showing everything.
        return ticked.size() == statusBoxes.size()
                ? List.of()
                : ticked;
    }

    @Override
    public void setStatuses(final List<AttemptStatus> statuses) {
        statusBoxes.forEach((status, box) -> box.setValue(statuses != null && statuses.contains(status)));
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, AttemptFilterViewImpl> {

    }
}
