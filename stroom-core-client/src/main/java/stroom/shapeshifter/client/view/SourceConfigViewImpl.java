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

import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.SourceConfigPresenter.SourceConfigView;
import stroom.shapeshifter.client.presenter.SourceConfigUiHandlers;
import stroom.shapeshifter.config.Dispatch;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.Locale;

public class SourceConfigViewImpl
        extends ViewWithUiHandlers<SourceConfigUiHandlers>
        implements SourceConfigView {

    private final Widget widget;

    @UiField
    TextBox name;
    @UiField
    TextBox version;
    @UiField
    TextBox bufferSize;
    @UiField
    CustomCheckBox ignoreErrors;
    @UiField
    TextBox encoding;
    @UiField
    SelectionBox<String> dispatch;
    @UiField
    CustomCheckBox strictValues;
    @UiField
    TextBox maxSequenceEntries;
    @UiField
    Label error;

    @Inject
    public SourceConfigViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        dispatch.setNonSelectString("by version");
        for (final Dispatch value : Dispatch.values()) {
            dispatch.addItem(value.name().toLowerCase(Locale.ROOT));
        }
        setError(null);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @UiHandler("name")
    void onName(final ValueChangeEvent<String> e) {
        changed();
    }

    @UiHandler("version")
    void onVersion(final ValueChangeEvent<String> e) {
        changed();
    }

    @UiHandler("bufferSize")
    void onBufferSize(final ValueChangeEvent<String> e) {
        changed();
    }

    @UiHandler("ignoreErrors")
    void onIgnoreErrors(final ValueChangeEvent<Boolean> e) {
        changed();
    }

    @UiHandler("encoding")
    void onEncoding(final ValueChangeEvent<String> e) {
        changed();
    }

    @UiHandler("dispatch")
    void onDispatch(final ValueChangeEvent<String> e) {
        changed();
    }

    @UiHandler("strictValues")
    void onStrictValues(final ValueChangeEvent<Boolean> e) {
        changed();
    }

    @UiHandler("maxSequenceEntries")
    void onMaxSequenceEntries(final ValueChangeEvent<String> e) {
        changed();
    }

    private void changed() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    @Override
    public void setEnabled(final boolean enabled) {
        name.setEnabled(enabled);
        version.setEnabled(enabled);
        bufferSize.setEnabled(enabled);
        ignoreErrors.setEnabled(enabled);
        encoding.setEnabled(enabled);
        dispatch.setEnabled(enabled);
        strictValues.setEnabled(enabled);
        maxSequenceEntries.setEnabled(enabled);
    }

    @Override
    public void setError(final String text) {
        error.setText(text == null
                ? ""
                : text);
        error.setVisible(text != null);
    }

    @Override
    public String getName() {
        return name.getText();
    }

    @Override
    public void setName(final String value) {
        name.setText(value);
    }

    @Override
    public int getVersion() {
        return Integer.parseInt(version.getText().trim());
    }

    @Override
    public void setVersion(final int value) {
        version.setText(String.valueOf(value));
    }

    @Override
    public int getBufferSize() {
        return Integer.parseInt(bufferSize.getText().trim());
    }

    @Override
    public void setBufferSize(final int value) {
        bufferSize.setText(String.valueOf(value));
    }

    @Override
    public boolean isIgnoreErrors() {
        return ignoreErrors.getValue();
    }

    @Override
    public void setIgnoreErrors(final boolean value) {
        ignoreErrors.setValue(value);
    }

    @Override
    public String getEncoding() {
        return encoding.getText();
    }

    @Override
    public void setEncoding(final String value) {
        encoding.setText(value == null
                ? ""
                : value);
    }

    @Override
    public Dispatch getDispatch() {
        final String value = dispatch.getValue();
        return value == null
                ? null
                : Dispatch.valueOf(value.toUpperCase(Locale.ROOT));
    }

    @Override
    public void setDispatch(final Dispatch value) {
        dispatch.setValue(value == null
                ? null
                : value.name().toLowerCase(Locale.ROOT), false);
    }

    @Override
    public boolean isStrictValues() {
        return strictValues.getValue();
    }

    @Override
    public void setStrictValues(final boolean value) {
        strictValues.setValue(value);
    }

    @Override
    public int getMaxSequenceEntries() {
        return Integer.parseInt(maxSequenceEntries.getText().trim());
    }

    @Override
    public void setMaxSequenceEntries(final int value) {
        maxSequenceEntries.setText(String.valueOf(value));
    }

    public interface Binder extends UiBinder<Widget, SourceConfigViewImpl> {

    }
}
