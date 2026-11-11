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

import stroom.shapeshifter.client.model.TemplateRowData;
import stroom.widget.button.client.Toolbar;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public class TemplatePanel
        extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    private Consumer<String> onSelect = id -> {
    };
    private Consumer<String> onOpen = id -> {
    };
    private Consumer<String> onHover = id -> {
    };

    private final List<TemplateRow> rows = new ArrayList<>();

    @UiField
    Toolbar toolbar;
    @UiField
    FlowPanel list;

    public TemplatePanel() {
        initWidget(BINDER.createAndBindUi(this));
    }


    /// @return The toolbar its controller adds its actions to.
    public Toolbar getToolbar() {
        return toolbar;
    }

    public void setRows(final List<TemplateRowData> data) {
        list.clear();
        rows.clear();
        String section = null;
        for (final TemplateRowData row : data) {
            if (row.isSection()) {
                // A section heading. SETTINGS and DATA are destinations and so are rows that
                // render as headings; TEMPLATES heads the list and is a label (design 44 §5m).
                section = null;
                if (row.getId() == null) {
                    final Label head = new Label(row.getName());
                    head.setStyleName("template-panel__section");
                    list.add(head);
                } else {
                    final TemplateRow widget = newRow(row);
                    widget.addStyleName("template-panel__section template-panel__section--link");
                    rows.add(widget);
                    list.add(widget);
                }
                continue;
            }
            if (row.getSection() != null && !Objects.equals(section, row.getSection())) {
                section = row.getSection();
                final Label head = new Label(section);
                head.setStyleName("template-panel__mode-head");
                list.add(head);
            }
            final TemplateRow widget = newRow(row);
            rows.add(widget);
            list.add(widget);
        }
    }

    public void setHot(final String id) {
        for (final TemplateRow row : rows) {
            row.setHot(id != null && Objects.equals(row.getData().getId(), id));
        }
    }

    public void setSelected(final String id) {
        for (final TemplateRow row : rows) {
            row.setSelected(Objects.equals(row.getData().getId(), id));
        }
    }


    // One place where a row is made and wired, for the two call sites that make them
    private TemplateRow newRow(final TemplateRowData row) {
        final TemplateRow widget = new TemplateRow(row);
        widget.setOnSelect(id -> onSelect.accept(id));
        widget.setOnOpen(id -> onOpen.accept(id));
        widget.setOnHover(id -> onHover.accept(id));
        return widget;
    }

    /// @param onSelect Told when select.
    public void setOnSelect(final Consumer<String> onSelect) {
        this.onSelect = onSelect;
    }

    /// @param onOpen Told when open.
    public void setOnOpen(final Consumer<String> onOpen) {
        this.onOpen = onOpen;
    }

    /// @param onHover Told when hover.
    public void setOnHover(final Consumer<String> onHover) {
        this.onHover = onHover;
    }

    interface Binder extends UiBinder<Widget, TemplatePanel> {

    }
}
