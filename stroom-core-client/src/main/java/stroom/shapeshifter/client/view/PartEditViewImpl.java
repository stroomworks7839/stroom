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
import stroom.shapeshifter.client.presenter.PartEditPresenter.Kind;
import stroom.shapeshifter.client.presenter.PartEditPresenter.LengthKind;
import stroom.shapeshifter.client.presenter.PartEditPresenter.PartEditView;
import stroom.shapeshifter.config.BinaryCast;
import stroom.widget.form.client.FormGroup;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

public class PartEditViewImpl extends ViewImpl implements PartEditView {

    private final Widget widget;

    @UiField
    SelectionBox<Kind> kind;
    @UiField
    FormGroup patternGroup;
    @UiField
    TextArea pattern;
    @UiField
    FormGroup lengthGroup;
    @UiField
    SelectionBox<LengthKind> lengthKind;
    @UiField
    TextBox length;
    @UiField
    FormGroup labelGroup;
    @UiField
    TextBox label;
    @UiField
    FormGroup absoluteGroup;
    @UiField
    CustomCheckBox absolute;
    @UiField
    FormGroup castGroup;
    @UiField
    SelectionBox<BinaryCast> cast;

    @Inject
    public PartEditViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        kind.setDisplayValueFunction(Kind::spelling);
        kind.addItems(Kind.values());
        lengthKind.setDisplayValueFunction(LengthKind::spelling);
        lengthKind.addItems(LengthKind.values());
        cast.setDisplayValueFunction(BinaryCast::label);
        cast.addItems(BinaryCast.values());
        showKind(Kind.TAKE);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void focus() {
        length.setFocus(true);
    }

    @UiHandler("kind")
    void onKind(final ValueChangeEvent<Kind> e) {
        showKind(e.getValue());
    }

    private void showKind(final Kind value) {
        patternGroup.setVisible(value == Kind.PATTERN);
        lengthGroup.setVisible(value == Kind.TAKE || value == Kind.SEEK);
        labelGroup.setVisible(value == Kind.TAKE || value == Kind.READ);
        absoluteGroup.setVisible(value == Kind.SEEK);
        castGroup.setVisible(value == Kind.READ);
    }

    @Override
    public Kind getKind() {
        return kind.getValue();
    }

    @Override
    public void setKind(final Kind value) {
        kind.setValue(value, false);
        showKind(value);
    }

    @Override
    public String getPattern() {
        return pattern.getText();
    }

    @Override
    public void setPattern(final String json) {
        pattern.setText(json);
    }

    @Override
    public LengthKind getLengthKind() {
        return lengthKind.getValue();
    }

    @Override
    public void setLengthKind(final LengthKind value) {
        lengthKind.setValue(value, false);
    }

    @Override
    public String getLength() {
        return length.getText();
    }

    @Override
    public void setLength(final String value) {
        length.setText(value);
    }

    @Override
    public String getLabel() {
        return label.getText();
    }

    @Override
    public void setLabel(final String value) {
        label.setText(value);
    }

    @Override
    public boolean isAbsolute() {
        return absolute.getValue();
    }

    @Override
    public void setAbsolute(final boolean value) {
        absolute.setValue(value);
    }

    @Override
    public BinaryCast getCast() {
        return cast.getValue();
    }

    @Override
    public void setCast(final BinaryCast value) {
        cast.setValue(value, false);
    }

    public interface Binder extends UiBinder<Widget, PartEditViewImpl> {

    }
}
