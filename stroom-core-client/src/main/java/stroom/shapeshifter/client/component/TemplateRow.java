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

import stroom.shapeshifter.client.model.Mark;
import stroom.shapeshifter.client.model.TemplateRowData;
import stroom.widget.text.client.Label;
import stroom.widget.util.client.CssProperties;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.DoubleClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.event.dom.client.MouseOverEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.Widget;

/// One row of the template panel: swatch, name, count. A focusable row — click or Enter selects,
/// double-click opens — so the panel is a list the keyboard can walk (design 18 §5.9).
public class TemplateRow extends Composite {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    FocusPanel row;
    @UiField
    Label chip;
    @UiField
    Label name;
    @UiField
    Label flag;
    @UiField
    Label count;
    @UiField
    FlowPanel heat;
    @UiField
    Label heatBar;

    private final TemplateRowData data;
    private final Listener listener;

    public TemplateRow(final TemplateRowData data, final Listener listener) {
        this.data = data;
        this.listener = listener;
        initWidget(BINDER.createAndBindUi(this));
        row.addMouseOverHandler(event -> onMouseOver(event));
        row.addMouseOutHandler(event -> onMouseOut(event));
        row.addClickHandler(event -> onClick(event));
        row.addDoubleClickHandler(event -> onDoubleClick(event));
        row.addKeyDownHandler(event -> onKeyDown(event));
        // A section has no swatch, and must not reserve room for one: the chip is what pushed
        // SETTINGS and DATA right of TEMPLATES, which is a plain label (design 44 §5m).
        chip.setVisible(!data.isSection());
        CssProperties.set(chip.getElement(), "--chip__colour", data.getColour());
        if ("transparent".equals(data.getColour())) {
            chip.addStyleName("chip--outline");
        }
        name.setText(data.getName());
        count.setText(data.getCount());
        if (data.isZero()) {
            row.addStyleName("template-row--zero");
        }
        // The severity mark (design 18 §5.8), as stepping colours its pipeline tree: a glyph in
        // the severity's colour, so it is not colour alone and it survives the row's selection.
        final String severity = Mark.flagOf(data.getSeverity());
        flag.setVisible(severity != null);
        if (severity != null) {
            flag.setText("▲");
            flag.addStyleName("severity-flag");
            flag.addStyleName(severityClass(severity));
            flag.setTitle("A run said something at " + severity + " level in a match of this template");
        }
        row.setTitle(data.getId() == null
                ? "The document: its name and source settings"
                : data.getName() + " — " + (data.getProfile() == null
                        ? data.getCount()
                        : data.getProfile()));
        // The heat bar (design 18 §5.8): length is the share of the run, colour the cost per
        // attempt against the run's - two metrics, one glyph.
        heat.setVisible(data.getProfile() != null);
        if (data.getProfile() != null) {
            heatBar.setWidth(Math.max(1, Math.round(data.getShare() * 100)) + "%");
            heatBar.addStyleName(data.getCost() == 0
                    ? "template-row__heat-bar--cool"
                    : data.getCost() == 1
                            ? "template-row__heat-bar--warm"
                            : "template-row__heat-bar--hot");
        }
    }

    public TemplateRowData getData() {
        return data;
    }

    public void setSelected(final boolean selected) {
        if (selected) {
            row.addStyleName("template-row--selected");
        } else {
            row.removeStyleName("template-row--selected");
        }
    }

    public void setHot(final boolean hot) {
        if (hot) {
            row.addStyleName(Marks.HOT_CLASS);
        } else {
            row.removeStyleName(Marks.HOT_CLASS);
        }
    }

    private void onMouseOver(final MouseOverEvent e) {
        if (data.getId() != null) {
            listener.onHover(data.getId());
        }
    }

    private void onMouseOut(final MouseOutEvent e) {
        listener.onHover(null);
    }

    private void onClick(final ClickEvent e) {
        listener.onSelect(data.getId());
    }

    private void onDoubleClick(final DoubleClickEvent e) {
        listener.onOpen(data.getId());
    }

    private void onKeyDown(final KeyDownEvent e) {
        if (e.getNativeKeyCode() == KeyCodes.KEY_ENTER || e.getNativeKeyCode() == ' ') {
            e.preventDefault();
            listener.onSelect(data.getId());
        }
    }

    /// The severity's own modifier, built rather than written as a literal so that the class name
    /// read by the stylesheet tests is a whole name and not the prefix it is made from.
    private static String severityClass(final String severity) {
        return "severity-flag--" + severity;
    }

    public interface Listener {

        void onSelect(String id);

        void onOpen(String id);

        void onHover(String id);
    }

    interface Binder extends UiBinder<Widget, TemplateRow> {

    }
}
