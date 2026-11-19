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

import stroom.shapeshifter.client.component.CaptureEditController.SourceKind;
import stroom.shapeshifter.config.Cast;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.FormGroup;
import stroom.widget.form.client.TextArea;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.Locale;

public class CaptureEdit extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);


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

    public CaptureEdit() {
        initWidget(BINDER.createAndBindUi(this));
        kind.addValueChangeHandler(event -> onKind(event));
        kind.setDisplayValueFunction(SourceKind::tag);
        kind.addItems(SourceKind.values());
        cast.setNonSelectString("string (uncast)");
        cast.setDisplayValueFunction(c -> c.name().toLowerCase(Locale.ROOT));
        cast.addItems(Cast.values());
        showKind(SourceKind.GROUP);
    }


    public void focus() {
        name.setFocus(true);
    }

    private void onKind(final ValueChangeEvent<SourceKind> e) {
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

    public String getName() {
        return name.getText();
    }

    public void setName(final String value) {
        name.setText(value);
    }

    public void setDeclared(final List<String> names) {
        declared.setText(names.isEmpty()
                ? "Nothing is declared yet: declare the name, with its type, on the template it lives for."
                : "Declared: " + String.join(", ", names));
    }

    public SourceKind getKind() {
        return kind.getValue();
    }

    public void setKind(final SourceKind value) {
        kind.setValue(value, false);
        showKind(value);
    }

    public String getGroup() {
        return group.getText();
    }

    public void setGroup(final String value) {
        group.setText(value);
    }

    public String getLabel() {
        return label.getText();
    }

    public void setLabel(final String value) {
        label.setText(value);
    }

    public String getSelect() {
        return select.getText();
    }

    public void setSelect(final String json) {
        select.setText(json);
    }

    public Cast getCast() {
        return cast.getValue();
    }

    public void setCast(final Cast value) {
        cast.setValue(value, false);
    }

    interface Binder extends UiBinder<Widget, CaptureEdit> {

    }
}
