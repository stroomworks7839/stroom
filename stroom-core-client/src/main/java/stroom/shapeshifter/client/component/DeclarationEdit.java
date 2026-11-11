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

import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.Declaration.Type;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextArea;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.Locale;

public class DeclarationEdit extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    TextBox name;
    @UiField
    SelectionBox<Type> type;
    @UiField
    TextArea entries;

    public DeclarationEdit() {
        initWidget(BINDER.createAndBindUi(this));
        type.addValueChangeHandler(event -> onType(event));
        type.setDisplayValueFunction(t -> t.name().toLowerCase(Locale.ROOT));
        type.addItems(Declaration.Type.values());
    }


    public void focus() {
        name.setFocus(true);
    }

    private void onType(final ValueChangeEvent<Type> e) {
        entries.setEnabled(e.getValue() == Type.MAP);
    }

    public String getName() {
        return name.getText();
    }

    public void setName(final String value) {
        name.setText(value);
    }

    public Type getType() {
        return type.getValue();
    }

    public void setType(final Type value) {
        type.setValue(value, false);
        entries.setEnabled(value == Type.MAP);
    }

    public String getEntries() {
        return entries.getText();
    }

    public void setEntries(final String value) {
        entries.setText(value);
    }

    interface Binder extends UiBinder<Widget, DeclarationEdit> {

    }
}
