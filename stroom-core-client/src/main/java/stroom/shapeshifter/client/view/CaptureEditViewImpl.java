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
import stroom.shapeshifter.client.presenter.CaptureEditPresenter.CaptureEditView;
import stroom.shapeshifter.client.presenter.CaptureEditPresenter.SourceKind;
import stroom.shapeshifter.config.Cast;
import stroom.widget.form.client.FormGroup;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.List;
import java.util.Locale;

public class CaptureEditViewImpl extends ViewImpl implements CaptureEditView {

    private final Widget widget;

    @UiField
    TextBox name;
    @UiField
    Label declared;
    @UiField
    SelectionBox<SourceKind> kind;
    @UiField
    FormGroup groupGroup;
    @UiField
    TextBox group;
    @UiField
    FormGroup labelGroup;
    @UiField
    TextBox label;
    @UiField
    FormGroup selectGroup;
    @UiField
    TextArea select;
    @UiField
    SelectionBox<Cast> cast;

    @Inject
    public CaptureEditViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        kind.setDisplayValueFunction(SourceKind::tag);
        kind.addItems(SourceKind.values());
        cast.setNonSelectString("string (uncast)");
        cast.setDisplayValueFunction(c -> c.name().toLowerCase(Locale.ROOT));
        cast.addItems(Cast.values());
        showKind(SourceKind.GROUP);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void focus() {
        name.setFocus(true);
    }

    @UiHandler("kind")
    void onKind(final ValueChangeEvent<SourceKind> e) {
        showKind(e.getValue());
    }

    private void showKind(final SourceKind value) {
        groupGroup.setVisible(value == SourceKind.GROUP);
        labelGroup.setVisible(value == SourceKind.LABEL);
        selectGroup.setVisible(value == SourceKind.SELECT || value == SourceKind.KEY_VALUE);
        selectGroup.setLabel(value == SourceKind.KEY_VALUE
                ? "Key and value references"
                : "Reference");
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
    public void setDeclared(final List<String> names) {
        declared.setText(names.isEmpty()
                ? "The template declares nothing yet."
                : "Declared: " + String.join(", ", names));
    }

    @Override
    public SourceKind getKind() {
        return kind.getValue();
    }

    @Override
    public void setKind(final SourceKind value) {
        kind.setValue(value, false);
        showKind(value);
    }

    @Override
    public String getGroup() {
        return group.getText();
    }

    @Override
    public void setGroup(final String value) {
        group.setText(value);
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
    public String getSelect() {
        return select.getText();
    }

    @Override
    public void setSelect(final String json) {
        select.setText(json);
    }

    @Override
    public Cast getCast() {
        return cast.getValue();
    }

    @Override
    public void setCast(final Cast value) {
        cast.setValue(value, false);
    }

    public interface Binder extends UiBinder<Widget, CaptureEditViewImpl> {

    }
}
