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

package stroom.shapeshifter.client.component;

import stroom.widget.button.client.Toolbar;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.DoubleClickEvent;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.function.IntConsumer;

public class Parts extends Component {

    private IntConsumer onSelect = index -> {
    };
    private IntConsumer onOpen = index -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);

    private static final String INDEX = "data-index";


    @UiField
    Toolbar buttonPanel;
    @UiField
    Label error;
    @UiField
    Html parts;

    public Parts() {
        initWidget(BINDER.createAndBindUi(this));
        setError(null);
        parts.addDomHandler(event -> {
            final int index = indexOf(event.getNativeEvent());
            if (index >= 0) {
                onSelect.accept(index);
            }
        }, ClickEvent.getType());
        parts.addDomHandler(event -> {
            final int index = indexOf(event.getNativeEvent());
            if (index >= 0) {
                onOpen.accept(index);
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


    /// @return The toolbar its controller adds its actions to.
    public Toolbar getToolbar() {
        return buttonPanel;
    }

    public void setParts(final SafeHtml html) {
        parts.setHTML(html);
    }

    public void setError(final String text) {
        error.setText(text == null
                ? ""
                : text);
        error.setVisible(text != null);
    }


    /// @param onSelect Told when select.
    public void setOnSelect(final IntConsumer onSelect) {
        this.onSelect = onSelect;
    }

    /// @param onOpen Told when open.
    public void setOnOpen(final IntConsumer onOpen) {
        this.onOpen = onOpen;
    }

    interface Binder extends UiBinder<Widget, Parts> {

    }
}
