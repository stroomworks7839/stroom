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
import stroom.shapeshifter.client.presenter.DeclarationEditPresenter.DeclarationEditView;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.Declaration.Type;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.Locale;

public class DeclarationEditViewImpl extends ViewImpl implements DeclarationEditView {

    private final Widget widget;

    @UiField
    TextBox name;
    @UiField
    SelectionBox<Type> type;
    @UiField
    TextArea entries;

    @Inject
    public DeclarationEditViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        type.setDisplayValueFunction(t -> t.name().toLowerCase(Locale.ROOT));
        type.addItems(Declaration.Type.values());
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void focus() {
        name.setFocus(true);
    }

    @UiHandler("type")
    void onType(final ValueChangeEvent<Type> e) {
        entries.setEnabled(e.getValue() == Type.MAP);
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
    public Type getType() {
        return type.getValue();
    }

    @Override
    public void setType(final Type value) {
        type.setValue(value, false);
        entries.setEnabled(value == Type.MAP);
    }

    @Override
    public String getEntries() {
        return entries.getText();
    }

    @Override
    public void setEntries(final String value) {
        entries.setText(value);
    }

    public interface Binder extends UiBinder<Widget, DeclarationEditViewImpl> {

    }
}
