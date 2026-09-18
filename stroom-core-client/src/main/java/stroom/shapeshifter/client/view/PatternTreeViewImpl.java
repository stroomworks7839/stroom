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

import stroom.shapeshifter.client.presenter.PatternTreePresenter.PatternTreeView;
import stroom.shapeshifter.client.presenter.PatternTreeUiHandlers;
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

public class PatternTreeViewImpl
        extends ViewWithUiHandlers<PatternTreeUiHandlers>
        implements PatternTreeView {

    private static final String PATH = "data-path";

    private final Widget widget;

    @UiField
    ButtonPanel buttonPanel;
    @UiField
    Label regex;
    @UiField
    Label error;
    @UiField
    HTML tree;
    @UiField
    HTML library;
    @UiField
    SimplePanel editor;

    @Inject
    public PatternTreeViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        setError(null);
        tree.addDomHandler(event -> {
            final String path = pathOf(event.getNativeEvent());
            if (path != null && getUiHandlers() != null) {
                getUiHandlers().onSelect(path);
            }
        }, ClickEvent.getType());
        tree.addDomHandler(event -> {
            final String path = pathOf(event.getNativeEvent());
            if (path != null && getUiHandlers() != null) {
                getUiHandlers().onOpen(path);
            }
        }, DoubleClickEvent.getType());
    }

    /** The path of the innermost node row the event landed in, or null outside every row. */
    private String pathOf(final NativeEvent event) {
        if (!Element.is(event.getEventTarget())) {
            return null;
        }
        Element element = Element.as(event.getEventTarget());
        while (element != null && element != tree.getElement()) {
            if (element.hasAttribute(PATH)) {
                return element.getAttribute(PATH);
            }
            element = element.getParentElement();
        }
        return null;
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
    public void setTree(final SafeHtml html) {
        tree.setHTML(html);
    }

    @Override
    public void setRegex(final String text) {
        regex.setText(text == null
                ? ""
                : text);
    }

    @Override
    public void setLibrary(final SafeHtml html) {
        library.setHTML(html);
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

    public interface Binder extends UiBinder<Widget, PatternTreeViewImpl> {

    }
}
