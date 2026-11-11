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

import stroom.shapeshifter.client.model.MatchKind;
import stroom.widget.component.client.Component;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.function.Consumer;

public class MatchEditor extends Component {

    private Consumer<MatchKind> onKind = kind -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    FlowPanel kindRow;
    @UiField
    Label note;
    @UiField
    SelectionBox<MatchKind> kind;
    @UiField
    SimplePanel body;

    public MatchEditor() {
        initWidget(BINDER.createAndBindUi(this));
        kind.addValueChangeHandler(event -> onKind(event));
        kind.setDisplayValueFunction(MatchKind::label);
        kind.addItems(MatchKind.values());
    }


    private void onKind(final ValueChangeEvent<MatchKind> e) {
        if (e.getValue() != null) {
            onKind.accept(e.getValue());
        }
    }

    public void setEnabled(final boolean enabled) {
        kind.setEnabled(enabled);
    }

    public void setKind(final MatchKind value) {
        kind.setValue(value, false);
        note.setText(value == null
                ? ""
                : value.note());
    }

    public void showKind(final boolean shown) {
        kindRow.setVisible(shown);
    }

    public void setBody(final IsWidget form) {
        body.setWidget(form.asWidget());
    }

    public void setNote(final String text) {
        final Label note = new Label(text);
        note.setStyleName("workbench-form__section workbench-form__lint");
        body.setWidget(note);
    }


    /// @param onKind Told when kind.
    public void setOnKind(final Consumer<MatchKind> onKind) {
        this.onKind = onKind;
    }

    interface Binder extends UiBinder<Widget, MatchEditor> {

    }
}
