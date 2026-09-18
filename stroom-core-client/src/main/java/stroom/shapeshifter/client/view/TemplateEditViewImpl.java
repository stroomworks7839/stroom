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

import stroom.shapeshifter.client.presenter.TemplateEditPresenter.TemplateEditView;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.Set;

public class TemplateEditViewImpl extends ViewImpl implements TemplateEditView {

    private final Widget widget;

    @UiField
    TextBox name;
    @UiField
    TextBox mode;
    @UiField
    Label modes;
    @UiField
    CustomCheckBox consume;

    @Inject
    public TemplateEditViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
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
        return mode.getText();
    }

    @Override
    public void setMode(final String value) {
        mode.setText(value);
    }

    @Override
    public void setModes(final Set<String> values) {
        modes.setText(values.isEmpty()
                ? "No modes yet; blank is the root."
                : "Existing: " + String.join(", ", values) + ". Blank is the root.");
    }

    @Override
    public boolean isConsume() {
        return consume.getValue();
    }

    @Override
    public void setConsume(final boolean value) {
        consume.setValue(value);
    }

    public interface Binder extends UiBinder<Widget, TemplateEditViewImpl> {

    }
}
