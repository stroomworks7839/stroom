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

import stroom.widget.button.client.LinkButton;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.HasWidgets;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.Iterator;
import java.util.function.Consumer;

/// A pane under a title bar that folds it away (design 47 E): the bar names the pane, and
/// pressing it leaves only the bar. The panes it is used for stand one above another, and a
/// reader who is not using one of them wants its room for the others rather than a smaller
/// share of it.
///
/// It hides what it holds and says so; making room of the space is its owner's, which knows
/// what the pane is beside ([#setOnToggle]).
///
/// ```
/// <ss:CollapsiblePane ui:field="inputPane" heading="Input">
///     <ss:ContentPane ui:field="input"/>
/// </ss:CollapsiblePane>
/// ```
public class CollapsiblePane extends Composite implements HasWidgets {

    private static final Binder BINDER = GWT.create(Binder.class);

    /// The height of the title bar, which is the height of the pane when it is collapsed.
    public static final int HEAD_PX = 26;

    private static final String COLLAPSED = "collapsible-pane--collapsed";

    @UiField
    FlowPanel root;
    @UiField
    LinkButton toggle;
    @UiField
    SimplePanel content;

    private String heading = "";
    private boolean collapsed;
    private Consumer<Boolean> onToggle = isCollapsed -> {
    };

    public CollapsiblePane() {
        initWidget(BINDER.createAndBindUi(this));
        toggle.setOnClick(() -> {
            setCollapsed(!collapsed);
            onToggle.accept(collapsed);
        });
        describe();
    }

    /// @param heading The pane's name, shown on its title bar.
    public void setHeading(final String heading) {
        this.heading = heading;
        toggle.setText(heading);
        describe();
    }

    /// @param onToggle Told when the user collapses or expands the pane, with whether it is now
    ///                 collapsed.
    public void setOnToggle(final Consumer<Boolean> onToggle) {
        this.onToggle = onToggle;
    }

    /// @return Whether only the title bar is shown.
    public boolean isCollapsed() {
        return collapsed;
    }

    /// Collapses or expands the pane, without telling [#setOnToggle]'s handler: for the owner
    /// that has changed its mind about the room, e.g. because a splitter was dragged.
    ///
    /// @param collapsed Whether only the title bar is to be shown.
    public void setCollapsed(final boolean collapsed) {
        this.collapsed = collapsed;
        content.setVisible(!collapsed);
        root.setStyleName(COLLAPSED, collapsed);
        describe();
    }

    // What the bar does next, for the pointer; and whether the pane is open, for a screen reader
    private void describe() {
        toggle.setAriaExpanded(!collapsed);
        toggle.setTitle((collapsed
                ? "Show the "
                : "Hide the ") + heading + " pane");
    }

    @Override
    public void add(final Widget widget) {
        content.setWidget(widget);
    }

    @Override
    public void clear() {
        content.clear();
    }

    @Override
    public Iterator<Widget> iterator() {
        return content.iterator();
    }

    @Override
    public boolean remove(final Widget widget) {
        return content.remove(widget);
    }

    interface Binder extends UiBinder<Widget, CollapsiblePane> {

    }
}
