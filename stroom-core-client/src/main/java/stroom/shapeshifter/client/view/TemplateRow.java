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

import stroom.shapeshifter.client.presenter.TemplateRowData;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.DoubleClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

/**
 * One row of the template panel: swatch, name, count. A focusable row — click or Enter selects,
 * double-click opens — so the panel is a list the keyboard can walk (design 18 §5.9).
 */
public class TemplateRow extends Composite {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    FocusPanel row;
    @UiField
    Label chip;
    @UiField
    Label name;
    @UiField
    Label count;

    private final TemplateRowData data;
    private final Listener listener;

    public TemplateRow(final TemplateRowData data, final Listener listener) {
        this.data = data;
        this.listener = listener;
        initWidget(BINDER.createAndBindUi(this));
        chip.getElement().getStyle().setBackgroundColor(data.getColour());
        if ("transparent".equals(data.getColour())) {
            chip.addStyleName("ss-chip--outline");
        }
        name.setText(data.getName());
        count.setText(data.getCount());
        if (data.isZero()) {
            row.addStyleName("ss-tpl-row--zero");
        }
        row.setTitle(data.getId() == null
                ? "The document: its name and source settings"
                : data.getName() + " — " + data.getCount());
    }

    public TemplateRowData getData() {
        return data;
    }

    public void setSelected(final boolean selected) {
        if (selected) {
            row.addStyleName("ss-tpl-row--sel");
        } else {
            row.removeStyleName("ss-tpl-row--sel");
        }
    }

    @UiHandler("row")
    void onClick(final ClickEvent e) {
        listener.onSelect(data.getId());
    }

    @UiHandler("row")
    void onDoubleClick(final DoubleClickEvent e) {
        listener.onOpen(data.getId());
    }

    @UiHandler("row")
    void onKeyDown(final KeyDownEvent e) {
        if (e.getNativeKeyCode() == KeyCodes.KEY_ENTER || e.getNativeKeyCode() == ' ') {
            e.preventDefault();
            listener.onSelect(data.getId());
        }
    }

    public interface Listener {

        void onSelect(String id);

        void onOpen(String id);
    }

    interface Binder extends UiBinder<Widget, TemplateRow> {

    }
}
