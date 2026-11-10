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

import stroom.widget.button.client.Button;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextArea;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

public class TemplateEdit extends Component {

    private Runnable onNewMode = () -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    TextBox name;
    @UiField
    SelectionBox<String> mode;
    @UiField
    Button newMode;
    @UiField
    ColourPalette colour;
    @UiField
    TextArea params;
    @UiField
    SelectionBox<String> encoding;
    @UiField
    TickBox ignoreErrors;

    public TemplateEdit() {
        initWidget(BINDER.createAndBindUi(this));
        newMode.addClickHandler(event -> onNewMode(event));
        mode.setNonSelectString("root");
        encoding.setNonSelectString("inherit");
    }

    private void onNewMode(final ClickEvent e) {
        onNewMode.run();
    }


    public void focus() {
        name.setFocus(true);
    }

    public String getName() {
        return name.getText();
    }

    public void setName(final String value) {
        name.setText(value);
    }

    public String getMode() {
        final String value = mode.getValue();
        return value == null
                ? ""
                : value;
    }

    public void setMode(final String value) {
        mode.setValue(value == null || value.isEmpty()
                ? null
                : value, false);
    }

    public void setModes(final List<String> values) {
        final String current = mode.getValue();
        mode.clear();
        mode.setNonSelectString("root");
        mode.addItems(values);
        mode.setValue(current, false);
    }

    public String getColour() {
        return colour.getValue();
    }

    public void setColour(final String override, final String auto) {
        colour.setValue(override, auto);
    }

    public String getParams() {
        return params.getText();
    }

    public void setParams(final String value) {
        params.setText(value);
    }

    public String getEncoding() {
        final String value = encoding.getValue();
        return value == null
                ? ""
                : value;
    }

    public void setEncoding(final String value) {
        encoding.setValue(value == null || value.isEmpty()
                ? null
                : value, false);
    }

    public void setEncodings(final List<String> values) {
        final String current = encoding.getValue();
        encoding.clear();
        encoding.setNonSelectString("inherit");
        encoding.addItems(values);
        encoding.setValue(current, false);
    }

    public boolean isIgnoreErrors() {
        return ignoreErrors.getValue();
    }

    public void setIgnoreErrors(final boolean value) {
        ignoreErrors.setValue(value);
    }


    /// @param onNewMode Told when newmode.
    public void setOnNewMode(final Runnable onNewMode) {
        this.onNewMode = onNewMode;
    }

    interface Binder extends UiBinder<Widget, TemplateEdit> {

    }
}
