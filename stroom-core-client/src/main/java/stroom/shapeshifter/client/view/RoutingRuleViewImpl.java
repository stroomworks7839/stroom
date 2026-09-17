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

import stroom.shapeshifter.client.presenter.RoutingRulePresenter.RoutingRuleView;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

public class RoutingRuleViewImpl extends ViewImpl implements RoutingRuleView {

    private final Widget widget;

    @UiField
    SimplePanel expression;
    @UiField
    SimplePanel fragment;
    @UiField
    CustomCheckBox pinned;

    @Inject
    public RoutingRuleViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setExpressionView(final View view) {
        expression.setWidget(view.asWidget());
    }

    @Override
    public void setFragmentView(final View view) {
        fragment.setWidget(view.asWidget());
    }

    @Override
    public boolean isPinned() {
        return pinned.getValue();
    }

    @Override
    public void setPinned(final boolean pinned) {
        this.pinned.setValue(pinned);
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, RoutingRuleViewImpl> {

    }
}
