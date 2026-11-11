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

import stroom.shapeshifter.client.model.SortKey;
import stroom.shapeshifter.config.Cast;
import stroom.shapeshifter.config.OutputNode.Order;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Widget;

import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/// One sort key: *then by* · expression · order · as · ✕. Commits when a field is left.
public class SortKeyRow extends Composite {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label joiner;
    @UiField
    TextBox by;
    @UiField
    SelectionBox<Order> order;
    @UiField
    SelectionBox<Cast> as;
    @UiField
    Label remove;

    private final int index;
    private BiConsumer<Integer, SortKey> onChange = (at, key) -> {
    };
    private IntConsumer onRemove = at -> {
    };
    private SortKey committed;

    public SortKeyRow(final int index, final SortKey key,
                      final boolean enabled) {
        this.index = index;
        initWidget(BINDER.createAndBindUi(this));
        by.addBlurHandler(event -> onByBlur(event));
        by.addKeyDownHandler(event -> onByKey(event));
        order.addValueChangeHandler(event -> onOrder(event));
        as.addValueChangeHandler(event -> onAs(event));
        remove.addClickHandler(event -> onRemoveClick(event));
        // The keys run in order, and the second onwards only decide what the first left level.
        joiner.setText(index == 0
                ? "by"
                : "then");
        order.setDisplayValueFunction(o -> o.name().toLowerCase(Locale.ROOT));
        order.addItems(Order.values());
        // Uncast is the string reading, which is the one total order and so the model's default.
        as.setNonSelectString("as text");
        as.setDisplayValueFunction(c -> c.name().toLowerCase(Locale.ROOT));
        as.addItems(Cast.values());
        committed = key;
        by.setText(key.getBy());
        order.setValue(key.getOrder(), false);
        as.setValue(key.getAs(), false);
        by.setEnabled(enabled);
        order.setEnabled(enabled);
        as.setEnabled(enabled);
        remove.setVisible(enabled);
    }

    private void onByBlur(final BlurEvent e) {
        commit();
    }

    private void onByKey(final KeyDownEvent e) {
        if (e.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
            e.preventDefault();
            commit();
        }
    }

    private void onOrder(final ValueChangeEvent<Order> e) {
        commit();
    }

    private void onAs(final ValueChangeEvent<Cast> e) {
        commit();
    }

    private void onRemoveClick(final ClickEvent e) {
        onRemove.accept(index);
    }

    /// The row as it stands.
    public SortKey getKey() {
        return new SortKey(by.getText(), order.getValue(), as.getValue());
    }

    private void commit() {
        final SortKey now = getKey();
        if (now.same(committed)) {
            return;
        }
        committed = now;
        onChange.accept(index, now);
    }

    /// Told when this row's key changes, with the row's index and the key it now holds.
    ///
    /// @param onChange The handler.
    public void setOnChange(final BiConsumer<Integer, SortKey> onChange) {
        this.onChange = onChange;
    }

    /// Told when this row's remove is pressed, with the row's index.
    ///
    /// @param onRemove The handler.
    public void setOnRemove(final IntConsumer onRemove) {
        this.onRemove = onRemove;
    }

    interface Binder extends UiBinder<Widget, SortKeyRow> {

    }
}
