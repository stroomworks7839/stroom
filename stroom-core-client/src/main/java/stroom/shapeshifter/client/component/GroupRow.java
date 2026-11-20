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
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.event.dom.client.MouseOverEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/// One row of the groups panel: chip · `$n` · → · the declaration's name, or for `$0`, the whole
/// match, chip · `$0` · whole match. The name commits when the field is left or Enter is pressed
/// — the same live convention as everything else. Pointing at the row shows its group alone, in
/// the pattern and in the sample, and clicking it keeps it so.
public class GroupRow extends Composite {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    FlowPanel row;
    @UiField
    Label chip;
    @UiField
    Label index;
    @UiField
    Label arrow;
    @UiField
    TextBox name;
    @UiField
    Label whole;

    private final GroupRowData data;
    private BiConsumer<Integer, String> onName = (at, name) -> {
    };
    private IntConsumer onSelect = at -> {
    };
    private IntConsumer onPoint = at -> {
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
        if (data.getIndex() == 0) {
            arrow.setVisible(false);
            name.setVisible(false);
            whole.setVisible(true);
        }
        if (data.isSelected()) {
            row.addStyleName("group-row--selected");
        }
        addDomHandler(event -> onPoint.accept(data.getIndex()), MouseOverEvent.getType());
        addDomHandler(event -> {
            // Leaving one of the row's own parts for another is not leaving the row
            final EventTarget to = event.getRelatedTarget();
            if (to == null || !Element.is(to) || !getElement().isOrHasChild(Element.as(to))) {
                onPoint.accept(RegexController.NO_GROUP);
            }
        }, MouseOutEvent.getType());
    }

    /// @param lit Whether the row's group is the one shown alone: pointed at, here or in the
    ///            pattern or the sample, or kept by a click.
    public void setLit(final boolean lit) {
        row.setStyleName("group-row--lit", lit);
    }

    /// @return The group this row is.
    public int getIndex() {
        return data.getIndex();
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
    /// Colours the row's chip again, in place: a run or a step moved its variable's colour, and
    /// rebuilding the row would take the keyboard from a name being typed in it.
    ///
    /// @param hue The colour.
    public void setHue(final String hue) {
        CssProperties.set(chip.getElement(), "--chip__colour", hue);
    }

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

    /// Told when the pointer comes onto this row, with its group's index, and when it leaves,
    /// with [RegexController#NO_GROUP].
    ///
    /// @param onPoint The handler.
    public void setOnPoint(final IntConsumer onPoint) {
        this.onPoint = onPoint;
    }

    interface Binder extends UiBinder<Widget, GroupRow> {

    }
}
