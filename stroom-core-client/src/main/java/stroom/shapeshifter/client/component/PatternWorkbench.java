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

import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.Toolbar;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.layout.client.SplitPane;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

public class PatternWorkbench extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    Label subject;
    @UiField
    Toolbar buttons;
    @UiField
    SimplePanel sample;
    @UiField
    SimplePanel editor;
    @UiField
    SplitPane layout;
    @UiField
    SimplePanel guardAndLimits;

    public PatternWorkbench() {
        initWidget(BINDER.createAndBindUi(this));
    }


    /// @return The toolbar its controller adds its actions to.
    public Toolbar getToolbar() {
        return buttons;
    }

    public void setSubject(final String name) {
        subject.setText(name);
    }

    public void setSample(final IsWidget view) {
        sample.setWidget(view.asWidget());
    }

    public void setEditor(final IsWidget view) {
        editor.setWidget(view.asWidget());
    }

    public void setGuardAndLimits(final IsWidget view) {
        guardAndLimits.setWidget(view.asWidget());
    }

    public void showGuardAndLimits(final boolean shown) {
        layout.setPaneHidden(guardAndLimits, !shown);
    }

    interface Binder extends UiBinder<Widget, PatternWorkbench> {

    }
}
