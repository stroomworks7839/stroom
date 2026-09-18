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
import stroom.shapeshifter.client.presenter.OtherMatchPresenter.Kind;
import stroom.shapeshifter.client.presenter.OtherMatchPresenter.OtherMatchView;
import stroom.shapeshifter.client.presenter.OtherMatchUiHandlers;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

public class OtherMatchViewImpl
        extends ViewWithUiHandlers<OtherMatchUiHandlers>
        implements OtherMatchView {

    private final Widget widget;

    @UiField
    SelectionBox<Kind> kind;
    @UiField
    FlowPanel delimiterFields;
    @UiField
    TextBox delimiter;
    @UiField
    TextBox escape;
    @UiField
    TextBox containerStart;
    @UiField
    TextBox containerEnd;
    @UiField
    Label error;
    @UiField
    SimplePanel editor;

    @Inject
    public OtherMatchViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        kind.setDisplayValueFunction(Kind::spelling);
        kind.addItems(Kind.values());
        setError(null);
        showKind(Kind.OTHER);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @UiHandler("kind")
    void onKind(final ValueChangeEvent<Kind> e) {
        showKind(e.getValue());
        changed();
    }

    @UiHandler("delimiter")
    void onDelimiter(final ValueChangeEvent<String> e) {
        changed();
    }

    @UiHandler("escape")
    void onEscape(final ValueChangeEvent<String> e) {
        changed();
    }

    @UiHandler("containerStart")
    void onContainerStart(final ValueChangeEvent<String> e) {
        changed();
    }

    @UiHandler("containerEnd")
    void onContainerEnd(final ValueChangeEvent<String> e) {
        changed();
    }

    private void changed() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    private void showKind(final Kind value) {
        delimiterFields.setVisible(value == Kind.DELIMITER);
    }

    @Override
    public void setEnabled(final boolean enabled) {
        kind.setEnabled(enabled);
        delimiter.setEnabled(enabled);
        escape.setEnabled(enabled);
        containerStart.setEnabled(enabled);
        containerEnd.setEnabled(enabled);
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
    public String getDelimiter() {
        return delimiter.getText();
    }

    @Override
    public String getEscape() {
        return escape.getText();
    }

    @Override
    public String getContainerStart() {
        return containerStart.getText();
    }

    @Override
    public String getContainerEnd() {
        return containerEnd.getText();
    }

    @Override
    public void setDelimiter(final String d, final String e, final String start, final String end) {
        delimiter.setText(d == null
                ? ""
                : d);
        escape.setText(e == null
                ? ""
                : e);
        containerStart.setText(start == null
                ? ""
                : start);
        containerEnd.setText(end == null
                ? ""
                : end);
    }

    @Override
    public void setError(final String text) {
        error.setText(text == null
                ? ""
                : text);
        error.setVisible(text != null);
    }

    @Override
    public void setEditor(final View view) {
        editor.setWidget(view.asWidget());
    }

    public interface Binder extends UiBinder<Widget, OtherMatchViewImpl> {

    }
}
