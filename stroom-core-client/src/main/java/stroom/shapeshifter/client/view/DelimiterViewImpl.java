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

import stroom.shapeshifter.client.presenter.DelimiterPresenter.DelimiterView;
import stroom.shapeshifter.client.presenter.DelimiterUiHandlers;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

public class DelimiterViewImpl
        extends ViewWithUiHandlers<DelimiterUiHandlers>
        implements DelimiterView {

    private final Widget widget;

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

    @Inject
    public DelimiterViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        setError(null);
    }

    @Override
    public Widget asWidget() {
        return widget;
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

    @Override
    public void setEnabled(final boolean enabled) {
        delimiter.setEnabled(enabled);
        escape.setEnabled(enabled);
        containerStart.setEnabled(enabled);
        containerEnd.setEnabled(enabled);
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

    public interface Binder extends UiBinder<Widget, DelimiterViewImpl> {

    }
}
