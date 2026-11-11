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

import stroom.shapeshifter.client.component.PartEditController.Kind;
import stroom.shapeshifter.client.component.PartEditController.LengthKind;
import stroom.shapeshifter.config.BinaryCast;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.FormGroup;
import stroom.widget.form.client.TextArea;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

public class PartEdit extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);


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
    TickBox absolute;
    @UiField
    FormGroup castGroup;
    @UiField
    SelectionBox<BinaryCast> cast;

    public PartEdit() {
        initWidget(BINDER.createAndBindUi(this));
        kind.addValueChangeHandler(event -> onKind(event));
        kind.setDisplayValueFunction(Kind::spelling);
        kind.addItems(Kind.values());
        lengthKind.setDisplayValueFunction(LengthKind::spelling);
        lengthKind.addItems(LengthKind.values());
        cast.setDisplayValueFunction(BinaryCast::label);
        cast.addItems(BinaryCast.values());
        showKind(Kind.TAKE);
    }


    public void focus() {
        length.setFocus(true);
    }

    private void onKind(final ValueChangeEvent<Kind> e) {
        showKind(e.getValue());
    }

    private void showKind(final Kind value) {
        patternGroup.setVisible(value == Kind.PATTERN);
        lengthGroup.setVisible(value == Kind.TAKE || value == Kind.SEEK);
        labelGroup.setVisible(value == Kind.TAKE || value == Kind.READ);
        absoluteGroup.setVisible(value == Kind.SEEK);
        castGroup.setVisible(value == Kind.READ);
    }

    public Kind getKind() {
        return kind.getValue();
    }

    public void setKind(final Kind value) {
        kind.setValue(value, false);
        showKind(value);
    }

    public String getPattern() {
        return pattern.getText();
    }

    public void setPattern(final String json) {
        pattern.setText(json);
    }

    public LengthKind getLengthKind() {
        return lengthKind.getValue();
    }

    public void setLengthKind(final LengthKind value) {
        lengthKind.setValue(value, false);
    }

    public String getLength() {
        return length.getText();
    }

    public void setLength(final String value) {
        length.setText(value);
    }

    public String getLabel() {
        return label.getText();
    }

    public void setLabel(final String value) {
        label.setText(value);
    }

    public boolean isAbsolute() {
        return absolute.getValue();
    }

    public void setAbsolute(final boolean value) {
        absolute.setValue(value);
    }

    public BinaryCast getCast() {
        return cast.getValue();
    }

    public void setCast(final BinaryCast value) {
        cast.setValue(value, false);
    }

    interface Binder extends UiBinder<Widget, PartEdit> {

    }
}
