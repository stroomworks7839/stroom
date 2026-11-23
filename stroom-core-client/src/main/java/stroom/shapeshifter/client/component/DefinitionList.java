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

import stroom.widget.button.client.LinkButton;
import stroom.widget.text.client.InlineLabel;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.IntConsumer;

/// The names a template defines, as a section of the template under its own heading: its
/// declarations, or its captures. A heading with the way to add beside it, then a row per name
/// — the name, and what it is — that is clicked to edit and carries its own remove.
///
/// These were two grids in a strip of their own under the template, 170px whether or not either
/// held anything, and in most templates neither does. As sections of the template they take a
/// line each when empty and scroll with the rest of it (the owner, 2026-11-18).
///
/// ```
/// <ss:DefinitionList ui:field="declarations" heading="Declarations" kind="declaration"/>
/// ```
public class DefinitionList extends Composite {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label heading;
    @UiField
    LinkButton add;
    @UiField
    Label none;
    @UiField
    FlowPanel rows;

    private String kind = "";
    private boolean enabled;
    private boolean removable = true;
    private int count;
    private Runnable onAdd = () -> {
    };
    private IntConsumer onEdit = index -> {
    };
    private IntConsumer onRemove = index -> {
    };
    private Runnable onShown = () -> {
    };

    public DefinitionList() {
        initWidget(BINDER.createAndBindUi(this));
        add.setOnClick(() -> onAdd.run());
        show();
    }

    /// @param heading What the section is called, e.g. `Declarations`.
    public void setHeading(final String heading) {
        this.heading.setText(heading);
    }

    /// @param kind What one of its rows is, e.g. `declaration`: the add link and the rows' tips
    ///             are worded with it.
    public void setKind(final String kind) {
        this.kind = kind;
        add.setText("+ " + kind);
    }

    /// @param rows What to list, in order.
    public void setRows(final List<Row> rows) {
        this.rows.clear();
        count = rows.size();
        for (int i = 0; i < rows.size(); i++) {
            this.rows.add(row(i, rows.get(i)));
        }
        show();
    }

    /// @param enabled Whether rows can be added, edited and removed.
    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        show();
    }

    /// @param removable Whether a row carries its own remove. A mode's templates do not: a
    ///                  template is removed from the panel that lists them all.
    public void setRemovable(final boolean removable) {
        this.removable = removable;
    }

    /// @return Whether it lists nothing.
    public boolean isEmpty() {
        return count == 0;
    }

    /// @return Whether rows can be added, edited and removed.
    public boolean isEnabled() {
        return enabled;
    }

    /// Adds a row, as the add link does: for a line elsewhere that adds to this section while it
    /// is not shown, because it is empty (design 48 C1).
    public void add() {
        if (enabled) {
            onAdd.run();
        }
    }

    /// @param onShown Told when what it lists, or whether it can be added to, changes: what
    ///                decides whether an empty section is shown at all.
    public void setOnShown(final Runnable onShown) {
        this.onShown = onShown;
    }

    /// Puts the keyboard on the add link.
    public void focusAdd() {
        add.getElement().focus();
    }

    /// @param onAdd Told when the add link is pressed.
    public void setOnAdd(final Runnable onAdd) {
        this.onAdd = onAdd;
    }

    /// @param onEdit Told when a row is pressed, with its position.
    public void setOnEdit(final IntConsumer onEdit) {
        this.onEdit = onEdit;
    }

    /// @param onRemove Told when a row's remove is pressed, with its position.
    public void setOnRemove(final IntConsumer onRemove) {
        this.onRemove = onRemove;
    }

    // What is on the heading's line: the way to add when rows can be added, and otherwise
    // "none" when there are none, so that an empty section read only still says it is empty.
    private void show() {
        add.setVisible(enabled);
        none.setVisible(!enabled && count == 0);
        rows.setStyleName("definition-list__rows--locked", !enabled);
        onShown.run();
    }

    private FlowPanel row(final int index, final Row row) {
        final FlowPanel panel = new FlowPanel();
        panel.setStyleName("definition-list__row");
        // The whole row opens it; the name is a button so that the keyboard can
        panel.addDomHandler(event -> {
            if (enabled) {
                onEdit.accept(index);
            }
        }, ClickEvent.getType());
        final LinkButton name = new LinkButton(row.name());
        name.addStyleName("definition-list__name");
        name.setTitle("Edit this " + kind);
        panel.add(name);
        final InlineLabel detail = new InlineLabel(row.detail());
        detail.setStyleName("definition-list__detail");
        panel.add(detail);
        if (!removable) {
            return panel;
        }
        final LinkButton remove = new LinkButton("✕");
        remove.addStyleName("item-action item-action--delete definition-list__remove");
        remove.setTitle("Remove this " + kind);
        remove.setAriaLabel("✕ remove " + kind + " " + row.name());
        remove.addClickHandler(event -> {
            // Not the row's click as well: removing is not opening
            event.stopPropagation();
            if (enabled) {
                onRemove.accept(index);
            }
        });
        panel.add(remove);
        return panel;
    }

    /// One row: a name, and what it is.
    ///
    /// @param name The name, e.g. `heading`.
    /// @param detail What it is, e.g. `list`, or `← $1 as number`.
    public record Row(String name, String detail) {

    }

    interface Binder extends UiBinder<Widget, DefinitionList> {

    }
}
