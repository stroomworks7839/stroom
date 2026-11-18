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

package stroom.shapeshifter.client.component;

import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.PagerPanel;
import stroom.widget.layout.client.SplitPane;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.event.dom.client.KeyDownHandler;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

public class ShapeshifterDesign extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    SplitPane layout;
    @UiField
    Label banner;
    @UiField
    TemplatePanel templatePanel;
    @UiField
    PagerPanel messages;
    @UiField
    SimplePanel centre;
    @UiField
    SplitPane workspace;
    @UiField
    SplitPane data;
    @UiField
    SplitPane work;
    @UiField
    CollapsiblePane messagesPane;
    @UiField
    CollapsiblePane inputPane;
    @UiField
    CollapsiblePane variablesPane;
    @UiField
    CollapsiblePane outputPane;
    @UiField
    Breadcrumb crumb;
    @UiField
    ContentPane input;
    @UiField
    VariablesPane variables;
    @UiField
    TemplateStrip strip;
    @UiField
    OutputPane output;

    private Widget workbenchWidget;
    private Widget samplePageWidget;

    // What the variables and the output are given when they are open and the input has the
    // rest: the sizes the template gives them, until the user drags a splitter.
    private String variablesSize = "22%";
    private String outputSize = "33%";
    // What the messages are given when they are open, until the user drags their splitter.
    private String messagesSize = "110px";

    public ShapeshifterDesign() {
        initWidget(BINDER.createAndBindUi(this));
        setBanner(null);
        inputPane.setOnToggle(collapsed -> shareData());
        variablesPane.setOnToggle(collapsed -> shareData());
        outputPane.setOnToggle(collapsed -> shareData());
        data.setOnResized(this::onDataResized);
        messagesPane.setOnToggle(collapsed -> work.setPaneSize(messagesPane, collapsed
                ? COLLAPSED_SIZE
                : messagesSize));
        work.setOnResized(() -> {
            // Dragged: an open pane keeps the size for when it is next opened, and a folded
            // one dragged open is open
            final String size = work.getPaneSize(messagesPane);
            if (size != null && !COLLAPSED_SIZE.equals(size)) {
                messagesSize = size;
                messagesPane.setCollapsed(false);
            }
        });
    }

    /// Says how many messages there are on the messages' title bar, so that the bar still says
    /// whether there is anything to read when the pane is folded away.
    ///
    /// @param count How many messages the last run or check left.
    public void setMessageCount(final int count) {
        messagesPane.setHeading(count == 0
                ? "Messages"
                : "Messages · " + count);
    }

    private static final String COLLAPSED_SIZE = CollapsiblePane.HEAD_PX + "px";

    // The three data panes' shares of their column, from which of them are folded away. A
    // folded pane is its title bar. Of those that are open, the first of input, output and
    // variables takes the rest - the input when it is there, since it is the one a long sample
    // needs room in - and the others have the size they were given or dragged to. With all
    // three folded the output takes the rest, as an empty pane under the three bars.
    private void shareData() {
        final CollapsiblePane rest = !inputPane.isCollapsed()
                ? inputPane
                : !outputPane.isCollapsed()
                        ? outputPane
                        : !variablesPane.isCollapsed()
                                ? variablesPane
                                : outputPane;
        // The pane that takes the rest is set last: until then two would be asking for it
        for (final CollapsiblePane pane : new CollapsiblePane[]{inputPane, variablesPane, outputPane}) {
            if (pane != rest) {
                data.setPaneSize(pane, pane.isCollapsed()
                        ? COLLAPSED_SIZE
                        : pane == variablesPane
                                ? variablesSize
                                : outputSize);
            }
        }
        data.setPaneSize(rest, "*");
    }

    // A splitter was dragged. An open pane keeps the size it was dragged to for when it is next
    // opened; a folded one that was dragged open is open, at that size.
    private void onDataResized() {
        boolean opened = false;
        for (final CollapsiblePane pane : new CollapsiblePane[]{variablesPane, outputPane, inputPane}) {
            final String size = data.getPaneSize(pane);
            if (size == null || "*".equals(size)) {
                continue;
            }
            if (pane.isCollapsed()) {
                if (COLLAPSED_SIZE.equals(size)) {
                    continue;
                }
                pane.setCollapsed(false);
                opened = true;
            }
            if (pane == variablesPane) {
                variablesSize = size;
            } else if (pane == outputPane) {
                outputSize = size;
            }
        }
        if (opened) {
            // Which pane takes the rest depends on which are open
            shareData();
        }
    }

    public void setKeyHandler(final KeyDownHandler handler) {
        // Keys bubble here from whatever has focus in the tab: a row, a card, a pane's block.
        layout.addDomHandler(handler, KeyDownEvent.getType());
    }


    /// @return The TemplatePanel its controller attaches to.
    public TemplatePanel getTemplatePanel() {
        return templatePanel;
    }

    /// @return The Breadcrumb its controller attaches to.
    public Breadcrumb getCrumb() {
        return crumb;
    }

    /// @return The ContentPane its controller attaches to.
    public ContentPane getInput() {
        return input;
    }

    /// @return The VariablesPane its controller attaches to.
    public VariablesPane getVariables() {
        return variables;
    }

    /// @return The OutputPane its controller attaches to.
    public OutputPane getOutput() {
        return output;
    }

    /// @return The TemplateStrip its controller attaches to.
    public TemplateStrip getStrip() {
        return strip;
    }

    public void setWorkbench(final IsWidget view) {
        workbenchWidget = view.asWidget();
    }

    public void setSamplePage(final IsWidget view) {
        samplePageWidget = view.asWidget();
    }

    public void showSamplePage(final boolean open) {
        // The whole of the area right of the panel, as the workbench takes it (design 44 §5a):
        // the sample is a page of the project, not a dialog over it.
        centre.setWidget(open
                ? samplePageWidget
                : workspace);
    }

    public void showWorkbench(final boolean open) {
        // The whole of the area right of the panel, as the sample page takes it (design 44 §5p).
        // It used to keep the output pane beside it, which showed what the cursor's frame wrote
        // in the last run — about a different question, frozen where the cursor was left, and
        // absent altogether for a library part, while the workbench's own sample is live.
        centre.setWidget(open
                ? workbenchWidget
                : workspace);
    }

    /// @return The pager its messages controller attaches to.
    public PagerPanel getMessages() {
        return messages;
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
