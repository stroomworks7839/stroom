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
import stroom.widget.form.client.TextBox;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

public class Delimiter extends Component {

    private Runnable onChange = () -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    TextBox delimiter;
    @UiField
    TextBox escape;
    @UiField
    TextBox containerStart;
    @UiField
    TextBox containerEnd;
    @UiField
    Label error;

    public Delimiter() {
        initWidget(BINDER.createAndBindUi(this));
        delimiter.addValueChangeHandler(event -> onDelimiter(event));
        escape.addValueChangeHandler(event -> onEscape(event));
        containerStart.addValueChangeHandler(event -> onContainerStart(event));
        containerEnd.addValueChangeHandler(event -> onContainerEnd(event));
        setError(null);
    }


    private void onDelimiter(final ValueChangeEvent<String> e) {
        changed();
    }

    private void onEscape(final ValueChangeEvent<String> e) {
        changed();
    }

    private void onContainerStart(final ValueChangeEvent<String> e) {
        changed();
    }

    private void onContainerEnd(final ValueChangeEvent<String> e) {
        changed();
    }

    private void changed() {
        onChange.run();
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        delimiter.setEnabled(!readOnly);
        escape.setEnabled(!readOnly);
        containerStart.setEnabled(!readOnly);
        containerEnd.setEnabled(!readOnly);
    }

    public String getDelimiter() {
        return delimiter.getText();
    }

    public String getEscape() {
        return escape.getText();
    }

    public String getContainerStart() {
        return containerStart.getText();
    }

    public String getContainerEnd() {
        return containerEnd.getText();
    }

    public void setDelimiter(final String d, final String e, final String start, final String end) {
        delimiter.setText(d == null
                ? ""
                : d);
        escape.setText(e == null
                ? ""
                : e);
        containerStart.setText(start == null
                ? ""
                : start);
        containerEnd.setText(end == null
                ? ""
                : end);
    }

    public void setError(final String text) {
        error.setText(text == null
                ? ""
                : text);
        error.setVisible(text != null);
    }


    /// @param onChange Told when change.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    interface Binder extends UiBinder<Widget, Delimiter> {

    }
}
