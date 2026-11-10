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

package stroom.shapeshifter.client.component;

import stroom.widget.component.client.Component;
import stroom.widget.layout.client.SplitPane;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.event.dom.client.KeyDownHandler;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.gwt.user.client.ui.SimpleLayoutPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

public class ShapeshifterDesign extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    SplitPane layout;
    @UiField
    Label banner;
    @UiField
    SimplePanel templatePanel;
    @UiField
    SimplePanel messages;
    @UiField
    SimpleLayoutPanel centre;
    @UiField
    SplitPane rows;
    @UiField
    SimplePanel crumb;
    @UiField
    SimplePanel input;
    @UiField
    SimplePanel variables;
    @UiField
    SimplePanel strip;
    @UiField
    SimplePanel output;

    private Widget workbenchWidget;
    private Widget samplePageWidget;

    public ShapeshifterDesign() {
        initWidget(BINDER.createAndBindUi(this));
        setBanner(null);
    }

    public void setKeyHandler(final KeyDownHandler handler) {
        // Keys bubble here from whatever has focus in the tab: a row, a card, a pane's block.
        layout.addDomHandler(handler, KeyDownEvent.getType());
    }


    public void setTemplatePanel(final IsWidget view) {
        templatePanel.setWidget(view.asWidget());
    }

    public void setCrumb(final IsWidget view) {
        crumb.setWidget(view.asWidget());
    }

    public void setInput(final IsWidget view) {
        input.setWidget(view.asWidget());
    }

    public void setVariables(final IsWidget view) {
        variables.setWidget(view.asWidget());
    }

    public void setOutput(final IsWidget view) {
        output.setWidget(view.asWidget());
    }

    public void setStrip(final IsWidget view) {
        strip.setWidget(view.asWidget());
    }

    public void setWorkbench(final IsWidget view) {
        workbenchWidget = view.asWidget();
    }

    public void setSamplePage(final IsWidget view) {
        samplePageWidget = view.asWidget();
    }

    public void showSamplePage(final boolean open) {
        // The whole of the area right of the panel, as the workbench takes the rows above the
        // output (design 44 §5a): the sample is a page of the project, not a dialog over it.
        centre.setWidget(open
                ? samplePageWidget
                : rows);
        centre.onResize();
    }

    public void showWorkbench(final boolean open) {
        // The whole of the area right of the panel, as the sample page takes it (design 44 §5p).
        // It used to keep the output pane beside it, which showed what the cursor's frame wrote
        // in the last run — about a different question, frozen where the cursor was left, and
        // absent altogether for a library part, while the workbench's own sample is live.
        centre.setWidget(open
                ? workbenchWidget
                : rows);
        centre.onResize();
    }

    public void setMessages(final IsWidget view) {
        messages.setWidget(view.asWidget());
    }

    public void setBanner(final String text) {
        banner.setText(text == null
                ? ""
                : text);
        layout.setPaneHidden(banner, text == null);
    }

    interface Binder extends UiBinder<Widget, ShapeshifterDesign> {

    }
}
