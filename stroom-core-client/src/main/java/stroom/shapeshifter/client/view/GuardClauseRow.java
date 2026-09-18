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

import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.GuardClause;
import stroom.shapeshifter.client.presenter.GuardClause.Op;
import stroom.shapeshifter.config.Cast;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.Locale;

/** One guard clause: <i>and</i> · variable · operator · value · as · ✕. Commits when a field is left. */
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
    private final Listener listener;
    private GuardClause committed;

    public GuardClauseRow(final int index, final GuardClause clause, final List<String> names,
                          final Listener listener, final boolean enabled) {
        this.index = index;
        this.listener = listener;
        initWidget(BINDER.createAndBindUi(this));
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

    @UiHandler("variable")
    void onVariable(final ValueChangeEvent<String> e) {
        commit();
    }

    @UiHandler("op")
    void onOp(final ValueChangeEvent<Op> e) {
        showOp(e.getValue());
        commit();
    }

    @UiHandler("value")
    void onValueBlur(final BlurEvent e) {
        commit();
    }

    @UiHandler("value")
    void onValueKey(final KeyDownEvent e) {
        if (e.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
            e.preventDefault();
            commit();
        }
    }

    @UiHandler("as")
    void onAs(final ValueChangeEvent<Cast> e) {
        commit();
    }

    @UiHandler("remove")
    void onRemove(final ClickEvent e) {
        listener.onClauseRemove(index);
    }

    /** The row as it stands. */
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
        listener.onClauseChange(index, now);
    }

    private static boolean same(final GuardClause a, final GuardClause b) {
        return a.getVariable().equals(b.getVariable())
               && a.getOp() == b.getOp()
               && a.getValue().equals(b.getValue())
               && a.getAs() == b.getAs();
    }

    public interface Listener {

        void onClauseChange(int index, GuardClause clause);

        void onClauseRemove(int index);
    }

    interface Binder extends UiBinder<Widget, GuardClauseRow> {

    }
}
