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

import stroom.shapeshifter.client.presenter.SupervisorPresenter;
import stroom.widget.tab.client.view.LinkTabBar;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.LayerContainer;
import com.gwtplatform.mvp.client.ViewImpl;

public class SupervisorViewImpl extends ViewImpl implements SupervisorPresenter.SupervisorView {

    private final Widget widget;
    @UiField
    SimplePanel learners;
    @UiField
    LinkTabBar tabBar;
    @UiField
    LayerContainer layerContainer;
    @UiField
    SimplePanel turnList;
    @UiField
    SimplePanel flow;

    @Inject
    public SupervisorViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setFlowChart(final Widget chart) {
        flow.setWidget(chart);
    }

    @Override
    public LinkTabBar getTabBar() {
        return tabBar;
    }

    @Override
    public LayerContainer getLayerContainer() {
        return layerContainer;
    }

    @Override
    public void setInSlot(final Object slot, final Widget content) {
        if (SupervisorPresenter.LEARNERS.equals(slot)) {
            learners.setWidget(content);
        } else if (SupervisorPresenter.TURN_LIST.equals(slot)) {
            turnList.setWidget(content);
        }
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, SupervisorViewImpl> {

    }
}
