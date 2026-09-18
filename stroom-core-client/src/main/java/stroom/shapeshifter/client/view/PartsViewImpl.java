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

import stroom.shapeshifter.client.presenter.PartsPresenter.PartsView;
import stroom.shapeshifter.client.presenter.PartsUiHandlers;
import stroom.svg.client.Preset;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.DoubleClickEvent;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

public class PartsViewImpl
        extends ViewWithUiHandlers<PartsUiHandlers>
        implements PartsView {

    private static final String INDEX = "data-index";

    private final Widget widget;

    @UiField
    ButtonPanel buttonPanel;
    @UiField
    Label error;
    @UiField
    HTML parts;
    @UiField
    SimplePanel editor;

    @Inject
    public PartsViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        setError(null);
        parts.addDomHandler(event -> {
            final int index = indexOf(event.getNativeEvent());
            if (index >= 0 && getUiHandlers() != null) {
                getUiHandlers().onSelect(index);
            }
        }, ClickEvent.getType());
        parts.addDomHandler(event -> {
            final int index = indexOf(event.getNativeEvent());
            if (index >= 0 && getUiHandlers() != null) {
                getUiHandlers().onOpen(index);
            }
        }, DoubleClickEvent.getType());
    }

    private int indexOf(final NativeEvent event) {
        if (!Element.is(event.getEventTarget())) {
            return -1;
        }
        Element element = Element.as(event.getEventTarget());
        while (element != null && element != parts.getElement()) {
            if (element.hasAttribute(INDEX)) {
                return Integer.parseInt(element.getAttribute(INDEX));
            }
            element = element.getParentElement();
        }
        return -1;
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public ButtonView addButton(final Preset preset) {
        return buttonPanel.addButton(preset);
    }

    @Override
    public void setParts(final SafeHtml html) {
        parts.setHTML(html);
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

    public interface Binder extends UiBinder<Widget, PartsViewImpl> {

    }
}
