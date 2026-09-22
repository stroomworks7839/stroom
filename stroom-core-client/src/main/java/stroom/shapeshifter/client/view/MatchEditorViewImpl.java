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
import stroom.shapeshifter.client.presenter.MatchEditorPresenter.MatchEditorView;
import stroom.shapeshifter.client.presenter.MatchEditorUiHandlers;
import stroom.shapeshifter.client.presenter.MatchKind;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

public class MatchEditorViewImpl
        extends ViewWithUiHandlers<MatchEditorUiHandlers>
        implements MatchEditorView {

    private final Widget widget;

    @UiField
    DockLayoutPanel layout;
    @UiField
    FlowPanel kindRow;
    @UiField
    Label note;
    @UiField
    SelectionBox<MatchKind> kind;
    @UiField
    SimplePanel body;

    @Inject
    public MatchEditorViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        kind.setDisplayValueFunction(MatchKind::label);
        kind.addItems(MatchKind.values());
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @UiHandler("kind")
    void onKind(final ValueChangeEvent<MatchKind> e) {
        if (getUiHandlers() != null && e.getValue() != null) {
            getUiHandlers().onKind(e.getValue());
        }
    }

    @Override
    public void setEnabled(final boolean enabled) {
        kind.setEnabled(enabled);
    }

    @Override
    public void setKind(final MatchKind value) {
        kind.setValue(value, false);
        note.setText(value == null
                ? ""
                : value.note());
    }

    @Override
    public void showKind(final boolean shown) {
        layout.setWidgetHidden(kindRow, !shown);
    }

    @Override
    public void setBody(final View form) {
        body.setWidget(form.asWidget());
    }

    @Override
    public void setNote(final String text) {
        final Label note = new Label(text);
        note.setStyleName("ss-wb-sec ss-wb-lint");
        body.setWidget(note);
    }

    public interface Binder extends UiBinder<Widget, MatchEditorViewImpl> {

    }
}
