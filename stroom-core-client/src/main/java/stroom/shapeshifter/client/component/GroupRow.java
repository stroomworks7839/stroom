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

import stroom.shapeshifter.client.model.GroupRowData;
import stroom.widget.form.client.TextBox;
import stroom.widget.text.client.Label;
import stroom.widget.util.client.CssProperties;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/// One row of the groups panel: chip · `$n` · → · the declaration's name. The name commits
/// when the field is left or Enter is pressed — the same live convention as everything else.
public class GroupRow extends Composite {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    FlowPanel row;
    @UiField
    Label chip;
    @UiField
    Label index;
    @UiField
    TextBox name;

    private final GroupRowData data;
    private BiConsumer<Integer, String> onName = (at, name) -> {
    };
    private IntConsumer onSelect = at -> {
    };
    private String committed;

    public GroupRow(final GroupRowData data, final boolean enabled) {
        this.data = data;
        initWidget(BINDER.createAndBindUi(this));
        chip.addClickHandler(event -> onChip(event));
        index.addClickHandler(event -> onIndex(event));
        name.addBlurHandler(event -> onBlur(event));
        name.addKeyDownHandler(event -> onKeyDown(event));
        CssProperties.set(chip.getElement(), "--chip__colour", data.getHue());
        index.setText("$" + data.getIndex());
        committed = data.getBoundName() == null
                ? ""
                : data.getBoundName();
        name.setText(committed);
        name.getElement().setPropertyString("placeholder", data.getSyntaxName() == null
                ? "declaration"
                : data.getSyntaxName());
        name.setEnabled(enabled);
        if (data.isSelected()) {
            row.addStyleName("group-row--selected");
        }
    }

    private void onChip(final ClickEvent e) {
        onSelect.accept(data.getIndex());
    }

    private void onIndex(final ClickEvent e) {
        onSelect.accept(data.getIndex());
    }

    private void onBlur(final BlurEvent e) {
        commit();
    }

    private void onKeyDown(final KeyDownEvent e) {
        if (e.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
            e.preventDefault();
            commit();
        }
    }

    private void commit() {
        final String typed = name.getText().trim();
        if (typed.equals(committed)) {
            return;
        }
        committed = typed;
        onName.accept(data.getIndex(), typed);
    }

    /// Told when the name bound to this group is committed, with the group's index.
    ///
    /// @param onName The handler.
    public void setOnName(final BiConsumer<Integer, String> onName) {
        this.onName = onName;
    }

    /// Told when this group is chosen, with its index.
    ///
    /// @param onSelect The handler.
    public void setOnSelect(final IntConsumer onSelect) {
        this.onSelect = onSelect;
    }

    interface Binder extends UiBinder<Widget, GroupRow> {

    }
}
