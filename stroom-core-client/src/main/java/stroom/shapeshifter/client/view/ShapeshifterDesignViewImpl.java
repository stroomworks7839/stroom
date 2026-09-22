/*
 * Copyright 2016 Crown Copyright
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

import stroom.shapeshifter.client.presenter.ShapeshifterDesignPresenter.ShapeshifterDesignView;

import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.event.dom.client.KeyDownHandler;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimpleLayoutPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.ThinSplitLayoutPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

public class ShapeshifterDesignViewImpl extends ViewImpl implements ShapeshifterDesignView {

    private final Widget widget;

    @UiField
    ThinSplitLayoutPanel layout;
    @UiField
    Label banner;
    @UiField
    SimplePanel templatePanel;
    @UiField
    SimplePanel messages;
    @UiField
    SimpleLayoutPanel centre;
    @UiField
    ThinSplitLayoutPanel rows;
    @UiField
    ThinSplitLayoutPanel topRow;
    @UiField
    SimplePanel crumb;
    @UiField
    SimplePanel input;
    @UiField
    SimplePanel variables;
    @UiField
    ThinSplitLayoutPanel bottomRow;
    @UiField
    SimplePanel strip;
    @UiField
    SimplePanel output;

    private Widget stripWidget;
    private Widget workbenchWidget;
    private Widget samplePageWidget;

    @Inject
    public ShapeshifterDesignViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        setBanner(null);
    }

    @Override
    public void setKeyHandler(final KeyDownHandler handler) {
        // Keys bubble here from whatever has focus in the tab: a row, a card, a pane's block.
        layout.addDomHandler(handler, KeyDownEvent.getType());
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setTemplatePanel(final View view) {
        templatePanel.setWidget(view.asWidget());
    }

    @Override
    public void setCrumb(final View view) {
        crumb.setWidget(view.asWidget());
    }

    @Override
    public void setInput(final View view) {
        input.setWidget(view.asWidget());
    }

    @Override
    public void setVariables(final View view) {
        variables.setWidget(view.asWidget());
    }

    @Override
    public void setOutput(final View view) {
        output.setWidget(view.asWidget());
    }

    @Override
    public void setStrip(final View view) {
        stripWidget = view.asWidget();
        strip.setWidget(stripWidget);
    }

    @Override
    public void setWorkbench(final View view) {
        workbenchWidget = view.asWidget();
    }

    @Override
    public void setSamplePage(final View view) {
        samplePageWidget = view.asWidget();
    }

    @Override
    public void showSamplePage(final boolean open) {
        // The whole of the area right of the panel, as the workbench takes the rows above the
        // output (design 44 §5a): the sample is a page of the project, not a dialog over it.
        centre.setWidget(open
                ? samplePageWidget
                : rows);
        centre.onResize();
    }

    @Override
    public void showWorkbench(final boolean open) {
        // In place of the crumb, input, variables and strip (design 18 section 5.6): the top row
        // goes, and the strip cell holds the workbench; the panel and the output pane stay.
        rows.setWidgetHidden(topRow, open);
        strip.setWidget(open
                ? workbenchWidget
                : stripWidget);
        rows.onResize();
    }

    @Override
    public void setMessages(final View view) {
        messages.setWidget(view.asWidget());
    }

    @Override
    public void setBanner(final String text) {
        banner.setText(text == null
                ? ""
                : text);
        layout.setWidgetHidden(banner, text == null);
    }

    public interface Binder extends UiBinder<Widget, ShapeshifterDesignViewImpl> {

    }
}
