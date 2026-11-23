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

import java.util.function.Consumer;

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
    Label called;
    @UiField
    Label flag;
    @UiField
    Label count;
    @UiField
    Label limit;
    @UiField
    FlowPanel heat;
    @UiField
    Label heatBar;

    private final TemplateRowData data;
    private Consumer<String> onSelect = id -> {
    };
    private Consumer<String> onOpen = id -> {
    };
    private Consumer<String> onHover = id -> {
    };

    public TemplateRow(final TemplateRowData data) {
        this.data = data;
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
        called.setVisible(data.getCalled() != null);
        if (data.getCalled() != null) {
            called.setText("· " + data.getCalled());
            row.addStyleName("template-row--called");
        }
        count.setText(data.getCount());
        limit.setText(data.getLimit() == null
                ? ""
                : "· " + data.getLimit());
        limit.setVisible(data.getLimit() != null);
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
                : tooltip(data));
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

    // What the row is, what it is called and limited to, and what the run made of it or its kind
    private static String tooltip(final TemplateRowData data) {
        final StringBuilder sb = new StringBuilder(data.getName());
        if (data.getCalled() != null) {
            sb.append(" · ").append(data.getCalled());
        }
        if (data.getLimit() != null) {
            sb.append(", ").append(data.getLimit());
        }
        final String result = data.getProfile() == null
                ? data.getCount()
                : data.getProfile();
        if (result != null && !result.isEmpty()) {
            sb.append(" — ").append(result);
        }
        return sb.toString();
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
            onHover.accept(data.getId());
        }
    }

    private void onMouseOut(final MouseOutEvent e) {
        onHover.accept(null);
    }

    private void onClick(final ClickEvent e) {
        onSelect.accept(data.getId());
    }

    private void onDoubleClick(final DoubleClickEvent e) {
        onOpen.accept(data.getId());
    }

    private void onKeyDown(final KeyDownEvent e) {
        if (e.getNativeKeyCode() == KeyCodes.KEY_ENTER || e.getNativeKeyCode() == ' ') {
            e.preventDefault();
            onSelect.accept(data.getId());
        }
    }

    /// The severity's own modifier, built rather than written as a literal so that the class name
    /// read by the stylesheet tests is a whole name and not the prefix it is made from.
    private static String severityClass(final String severity) {
        return "severity-flag--" + severity;
    }

    /// Told when the row is chosen, with the template's id.
    ///
    /// @param onSelect The handler.
    public void setOnSelect(final Consumer<String> onSelect) {
        this.onSelect = onSelect;
    }

    /// Told when the row is opened, with the template's id.
    ///
    /// @param onOpen The handler.
    public void setOnOpen(final Consumer<String> onOpen) {
        this.onOpen = onOpen;
    }

    /// Told when the pointer enters or leaves the row, with the template's id or null.
    ///
    /// @param onHover The handler.
    public void setOnHover(final Consumer<String> onHover) {
        this.onHover = onHover;
    }

    interface Binder extends UiBinder<Widget, TemplateRow> {

    }
}
