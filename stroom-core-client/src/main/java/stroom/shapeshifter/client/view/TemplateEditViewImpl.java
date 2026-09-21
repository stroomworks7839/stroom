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
import stroom.shapeshifter.client.presenter.TemplateEditPresenter.TemplateEditView;
import stroom.shapeshifter.client.presenter.TemplateEditUiHandlers;
import stroom.widget.button.client.Button;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.List;

public class TemplateEditViewImpl
        extends ViewWithUiHandlers<TemplateEditUiHandlers>
        implements TemplateEditView {

    private final Widget widget;

    @UiField
    TextBox name;
    @UiField
    SelectionBox<String> mode;
    @UiField
    Button newMode;
    @UiField
    CustomCheckBox consume;
    @UiField
    ColourPalette colour;
    @UiField
    TextArea params;
    @UiField
    SelectionBox<String> encoding;
    @UiField
    CustomCheckBox ignoreErrors;

    @Inject
    public TemplateEditViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        mode.setNonSelectString("root");
        encoding.setNonSelectString("inherit");
    }

    @UiHandler("newMode")
    void onNewMode(final ClickEvent e) {
        if (getUiHandlers() != null) {
            getUiHandlers().onNewMode();
        }
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void focus() {
        name.setFocus(true);
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
    public String getMode() {
        final String value = mode.getValue();
        return value == null
                ? ""
                : value;
    }

    @Override
    public void setMode(final String value) {
        mode.setValue(value == null || value.isEmpty()
                ? null
                : value, false);
    }

    @Override
    public void setModes(final List<String> values) {
        final String current = mode.getValue();
        mode.clear();
        mode.setNonSelectString("root");
        mode.addItems(values);
        mode.setValue(current, false);
    }

    @Override
    public boolean isConsume() {
        return consume.getValue();
    }

    @Override
    public void setConsume(final boolean value) {
        consume.setValue(value);
    }

    @Override
    public String getColour() {
        return colour.getValue();
    }

    @Override
    public void setColour(final String override, final String auto) {
        colour.setValue(override, auto);
    }

    @Override
    public String getParams() {
        return params.getText();
    }

    @Override
    public void setParams(final String value) {
        params.setText(value);
    }

    @Override
    public String getEncoding() {
        final String value = encoding.getValue();
        return value == null
                ? ""
                : value;
    }

    @Override
    public void setEncoding(final String value) {
        encoding.setValue(value == null || value.isEmpty()
                ? null
                : value, false);
    }

    @Override
    public void setEncodings(final List<String> values) {
        final String current = encoding.getValue();
        encoding.clear();
        encoding.setNonSelectString("inherit");
        encoding.addItems(values);
        encoding.setValue(current, false);
    }

    @Override
    public boolean isIgnoreErrors() {
        return ignoreErrors.getValue();
    }

    @Override
    public void setIgnoreErrors(final boolean value) {
        ignoreErrors.setValue(value);
    }

    public interface Binder extends UiBinder<Widget, TemplateEditViewImpl> {

    }
}
