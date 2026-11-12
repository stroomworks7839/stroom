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

import stroom.editor.client.component.CodeEditor;
import stroom.shapeshifter.client.component.SampleSourcePickerController.Kind;
import stroom.widget.component.client.Component;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.function.Consumer;

public class SampleSourcePicker extends Component {

    private Consumer<Kind> onKind = kind -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    SelectionBox<Kind> kind;
    @UiField
    SimplePanel body;
    @UiField
    Label keeping;
    @UiField
    CodeEditor pasted;

    private Widget streamView;

    public SampleSourcePicker() {
        initWidget(BINDER.createAndBindUi(this));
        kind.addValueChangeHandler(event -> onKind(event));
        kind.setDisplayValueFunction(Kind::label);
        kind.addItems(Kind.values());
    }


    private void onKind(final ValueChangeEvent<Kind> e) {
        show(e.getValue());
        if (e.getValue() != null) {
            onKind.accept(e.getValue());
        }
    }

    /// @return The paste box, for its controller to attach to.
    public CodeEditor getPasted() {
        return pasted;
    }

    public void setStreamView(final Widget view) {
        streamView = view;
        show(getKind());
    }

    private void show(final Kind which) {
        if (which == Kind.PASTED) {
            body.setWidget(pasted);
        } else if (streamView != null) {
            body.setWidget(streamView);
        }
    }

    public Kind getKind() {
        return kind.getValue() == null
                ? Kind.STREAM
                : kind.getValue();
    }

    public void setKeeping(final String text) {
        keeping.setText(text);
    }

    public void setKind(final Kind value) {
        kind.setValue(value, false);
        show(value);
    }


    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        // Where the data comes from is the page's one edit; the paste box is its controller's,
        // which has more to turn off than the text being read only.
        kind.setEnabled(!readOnly);
    }


    /// @param onKind Told when kind.
    public void setOnKind(final Consumer<Kind> onKind) {
        this.onKind = onKind;
    }

    interface Binder extends UiBinder<Widget, SampleSourcePicker> {

    }
}
