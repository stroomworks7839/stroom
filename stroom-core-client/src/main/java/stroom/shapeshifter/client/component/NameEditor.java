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
import stroom.widget.form.client.FormGroup;
import stroom.widget.form.client.TextBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

/// The content of the dialog that asks for a name: one field, with help saying what a name of
/// that kind means. [NameEditorController] shows it and refuses a blank or a taken name.
public class NameEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    FormGroup group;
    @UiField
    TextBox name;

    /// Makes an empty editor.
    public NameEditor() {
        initWidget(BINDER.createAndBindUi(this));
    }

    /// @param help What a name of this kind means, shown beneath the field.
    public void setHelp(final String help) {
        group.setHelpText(help);
    }

    /// @return The name entered, as typed.
    public String getName() {
        return name.getText();
    }

    /// @param value The name to show.
    public void setName(final String value) {
        name.setText(value);
    }

    /// Puts the cursor in the field, so the name can be typed straight away.
    public void focus() {
        name.setFocus(true);
    }

    interface Binder extends UiBinder<Widget, NameEditor> {

    }
}
