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

import stroom.shapeshifter.config.Dispatch;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.Locale;

public class SourceSettings extends Component {

    private Runnable onChange = () -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    TextBox name;
    @UiField
    TextBox version;
    @UiField
    TextBox bufferSize;
    @UiField
    TickBox ignoreErrors;
    @UiField
    SelectionBox<String> encoding;
    @UiField
    SelectionBox<String> dispatch;
    @UiField
    TickBox strictValues;
    @UiField
    TextBox maxSequenceEntries;
    @UiField
    Label error;
    @UiField
    DisclosureButton advancedToggle;
    @UiField
    FlowPanel advanced;
    // The author's own fold of Advanced, once they have made one, or null: it is kept over the
    // opening a set dispatch or strict values asks for, which the settings are told again after
    // every edit
    private Boolean advancedChosen;

    public SourceSettings() {
        initWidget(BINDER.createAndBindUi(this));
        name.addValueChangeHandler(event -> onName(event));
        version.addValueChangeHandler(event -> onVersion(event));
        bufferSize.addValueChangeHandler(event -> onBufferSize(event));
        ignoreErrors.addValueChangeHandler(event -> onIgnoreErrors(event));
        encoding.addValueChangeHandler(event -> onEncoding(event));
        dispatch.addValueChangeHandler(event -> onDispatch(event));
        strictValues.addValueChangeHandler(event -> onStrictValues(event));
        maxSequenceEntries.addValueChangeHandler(event -> onMaxSequenceEntries(event));
        dispatch.setNonSelectString("by version");
        for (final Dispatch value : Dispatch.values()) {
            dispatch.addItem(value.name().toLowerCase(Locale.ROOT));
        }
        advancedToggle.setExpanded(false);
        advancedToggle.addClickHandler(event -> {
            advancedChosen = !advancedToggle.isExpanded();
            showAdvanced(advancedChosen);
        });
        setError(null);
    }

    // The tuning and compatibility settings, shown or folded away under their heading
    private void showAdvanced(final boolean shown) {
        advancedToggle.setExpanded(shown);
        advanced.setVisible(shown);
    }

    // Opened for a setting that changes what the project does, unless the author has folded it
    private void openAdvanced() {
        if (advancedChosen == null) {
            showAdvanced(true);
        }
    }

    private void onName(final ValueChangeEvent<String> e) {
        changed();
    }

    private void onVersion(final ValueChangeEvent<String> e) {
        changed();
    }

    private void onBufferSize(final ValueChangeEvent<String> e) {
        changed();
    }

    private void onIgnoreErrors(final ValueChangeEvent<Boolean> e) {
        changed();
    }

    private void onEncoding(final ValueChangeEvent<String> e) {
        changed();
    }

    private void onDispatch(final ValueChangeEvent<String> e) {
        changed();
    }

    private void onStrictValues(final ValueChangeEvent<Boolean> e) {
        changed();
    }

    private void onMaxSequenceEntries(final ValueChangeEvent<String> e) {
        changed();
    }

    private void changed() {
        onChange.run();
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        name.setEnabled(!readOnly);
        version.setEnabled(!readOnly);
        bufferSize.setEnabled(!readOnly);
        ignoreErrors.setEnabled(!readOnly);
        encoding.setEnabled(!readOnly);
        dispatch.setEnabled(!readOnly);
        strictValues.setEnabled(!readOnly);
        maxSequenceEntries.setEnabled(!readOnly);
    }

    public void setError(final String text) {
        error.setText(text == null
                ? ""
                : text);
        error.setVisible(text != null);
    }

    public String getName() {
        return name.getText();
    }

    public void setName(final String value) {
        name.setText(value);
    }

    public int getVersion() {
        return Integer.parseInt(version.getText().trim());
    }

    public void setVersion(final int value) {
        version.setText(String.valueOf(value));
    }

    public int getBufferSize() {
        return Integer.parseInt(bufferSize.getText().trim());
    }

    public void setBufferSize(final int value) {
        bufferSize.setText(String.valueOf(value));
    }

    public boolean isIgnoreErrors() {
        return ignoreErrors.getValue();
    }

    public void setIgnoreErrors(final boolean value) {
        ignoreErrors.setValue(value);
    }

    public String getEncoding() {
        final String value = encoding.getValue();
        return value == null
                ? ""
                : value;
    }

    public void setEncoding(final String value) {
        encoding.setValue(value == null || value.isEmpty()
                ? "auto"
                : value, false);
    }

    public void setEncodings(final List<String> values) {
        // The engine's list has "auto", which is what a blank source encoding means, so there
        // is no non-select item: one row says it.
        final String current = encoding.getValue();
        encoding.clear();
        encoding.addItems(values);
        encoding.setValue(current, false);
    }

    public Dispatch getDispatch() {
        final String value = dispatch.getValue();
        return value == null
                ? null
                : Dispatch.valueOf(value.toUpperCase(Locale.ROOT));
    }

    public void setDispatch(final Dispatch value) {
        if (value != null) {
            // A choice that changes what the project does is not folded away out of sight
            openAdvanced();
        }
        dispatch.setValue(value == null
                ? null
                : value.name().toLowerCase(Locale.ROOT), false);
    }

    public boolean isStrictValues() {
        return strictValues.getValue();
    }

    public void setStrictValues(final boolean value) {
        if (value) {
            // As a dispatch strategy: it changes what the project does
            openAdvanced();
        }
        strictValues.setValue(value);
    }

    public int getMaxSequenceEntries() {
        return Integer.parseInt(maxSequenceEntries.getText().trim());
    }

    public void setMaxSequenceEntries(final int value) {
        maxSequenceEntries.setText(String.valueOf(value));
    }


    /// @param onChange Told when change.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    interface Binder extends UiBinder<Widget, SourceSettings> {

    }
}
