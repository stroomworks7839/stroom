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

import stroom.shapeshifter.client.model.GuardClause;
import stroom.shapeshifter.client.model.GuardClause.Op;
import stroom.shapeshifter.config.Cast;
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

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/// One guard clause: *and* · variable · operator · value · as · ✕. Commits when a field is left.
public class GuardClauseRow extends Composite {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label joiner;
    @UiField
    SelectionBox<String> variable;
    @UiField
    SelectionBox<Op> op;
    @UiField
    TextBox value;
    @UiField
    SelectionBox<Cast> as;
    @UiField
    Label remove;

    private final int index;
    private BiConsumer<Integer, GuardClause> onChange = (at, clause) -> {
    };
    private IntConsumer onRemove = at -> {
    };
    private GuardClause committed;

    public GuardClauseRow(final int index, final GuardClause clause, final List<String> names,
                          final boolean enabled) {
        this.index = index;
        initWidget(BINDER.createAndBindUi(this));
        variable.addValueChangeHandler(event -> onVariable(event));
        op.addValueChangeHandler(event -> onOp(event));
        value.addBlurHandler(event -> onValueBlur(event));
        value.addKeyDownHandler(event -> onValueKey(event));
        as.addValueChangeHandler(event -> onAs(event));
        remove.addClickHandler(event -> onRemoveClick(event));
        joiner.setText(index == 0
                ? ""
                : "and");
        variable.setAllowTextEntry(true);
        variable.addItems(names);
        op.setDisplayValueFunction(Op::spelling);
        op.addItems(Op.values());
        as.setNonSelectString("as is");
        as.setDisplayValueFunction(c -> c.name().toLowerCase(Locale.ROOT));
        as.addItems(Cast.values());
        committed = clause;
        variable.setValue(clause.getVariable(), false);
        op.setValue(clause.getOp(), false);
        value.setText(clause.getValue());
        as.setValue(clause.getAs(), false);
        showOp(clause.getOp());
        variable.setEnabled(enabled);
        op.setEnabled(enabled);
        value.setEnabled(enabled);
        as.setEnabled(enabled);
        remove.setVisible(enabled);
    }

    private void showOp(final Op current) {
        final boolean takesVariable = current != null && current.takesVariable();
        final boolean takesValue = current != null && current.takesValue();
        variable.setVisible(takesVariable);
        value.setVisible(takesValue);
        as.setVisible(current != null && current.isComparison());
    }

    private void onVariable(final ValueChangeEvent<String> e) {
        commit();
    }

    private void onOp(final ValueChangeEvent<Op> e) {
        showOp(e.getValue());
        commit();
    }

    private void onValueBlur(final BlurEvent e) {
        commit();
    }

    private void onValueKey(final KeyDownEvent e) {
        if (e.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
            e.preventDefault();
            commit();
        }
    }

    private void onAs(final ValueChangeEvent<Cast> e) {
        commit();
    }

    private void onRemoveClick(final ClickEvent e) {
        onRemove.accept(index);
    }

    /// The row as it stands.
    public GuardClause getClause() {
        // With text entry allowed the text is the value: a pick writes it, and typing replaces it.
        return new GuardClause(variable.getText(), op.getValue(), value.getText(), as.getValue());
    }

    private void commit() {
        final GuardClause now = getClause();
        if (same(now, committed)) {
            return;
        }
        committed = now;
        onChange.accept(index, now);
    }

    private static boolean same(final GuardClause a, final GuardClause b) {
        return a.getVariable().equals(b.getVariable())
               && a.getOp() == b.getOp()
               && a.getValue().equals(b.getValue())
               && a.getAs() == b.getAs();
    }

    /// Told when this row's clause changes, with the row's index and the clause it now holds.
    ///
    /// @param onChange The handler.
    public void setOnChange(final BiConsumer<Integer, GuardClause> onChange) {
        this.onChange = onChange;
    }

    /// Told when this row's remove is pressed, with the row's index.
    ///
    /// @param onRemove The handler.
    public void setOnRemove(final IntConsumer onRemove) {
        this.onRemove = onRemove;
    }

    interface Binder extends UiBinder<Widget, GuardClauseRow> {

    }
}
