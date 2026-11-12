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
import stroom.shapeshifter.client.model.GuardClause.Join;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextBox;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class GuardAndLimits
        extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    private BiConsumer<Integer, GuardClause> onClauseChange = (index, clause) -> {
    };
    private IntConsumer onClauseRemove = index -> {
    };
    private Runnable onClauseAdd = () -> {
    };
    private Consumer<String> onGuardJson = json -> {
    };
    private LimitsChangeHandler limitsChangeHandler = (min, max, only) -> {
    };


    @UiField
    ClauseListPanel clauses;
    @UiField
    Label error;
    @UiField
    TextBox min;
    @UiField
    TextBox max;
    @UiField
    TextBox only;

    public GuardAndLimits() {
        initWidget(BINDER.createAndBindUi(this));
        min.addBlurHandler(event -> onMin(event));
        max.addBlurHandler(event -> onMax(event));
        only.addBlurHandler(event -> onOnly(event));
        clauses.setOnChange((index, clause) -> onClauseChange.accept(index, clause));
        clauses.setOnRemove(index -> onClauseRemove.accept(index));
        clauses.setOnAdd(() -> onClauseAdd.run());
        clauses.setOnJson(json -> onGuardJson.accept(json));
        clauses.setEmptyText("(no guard — the template is tried whenever its mode applies)");
        setError(null);
    }


    private void onMin(final BlurEvent e) {
        limitsChanged();
    }

    private void onMax(final BlurEvent e) {
        limitsChanged();
    }

    private void onOnly(final BlurEvent e) {
        limitsChanged();
    }

    private void limitsChanged() {
        limitsChangeHandler.onLimitsChange(min.getText(), max.getText(), only.getText());
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        clauses.setReadOnly(readOnly);
        min.setEnabled(!readOnly);
        max.setEnabled(!readOnly);
        only.setEnabled(!readOnly);
    }

    public void setNames(final List<String> names) {
        clauses.setNames(names);
    }

    public void setClauses(final List<GuardClause> list, final Join join) {
        clauses.setClauses(list, join);
    }

    public Join getJoin() {
        return clauses.getJoin();
    }

    /// @param onJoin Told when the join changes.
    public void setOnJoin(final java.util.function.Consumer<Join> onJoin) {
        clauses.setOnJoin(onJoin);
    }

    public List<GuardClause> getClauses() {
        return clauses.getClauses();
    }

    public boolean isWireForm() {
        return clauses.isWireForm();
    }

    public void setGuardJson(final String json) {
        clauses.setJson(json);
    }

    public void setLimits(final String minText, final String maxText, final String onlyText) {
        min.setText(minText);
        max.setText(maxText);
        only.setText(onlyText);
    }

    public void setError(final String text) {
        error.setText(text == null
                ? ""
                : text);
        error.setVisible(text != null);
    }


    /// @param onClauseChange Told when clausechange.
    public void setOnClauseChange(final BiConsumer<Integer, GuardClause> onClauseChange) {
        this.onClauseChange = onClauseChange;
    }

    /// @param onClauseRemove Told when clauseremove.
    public void setOnClauseRemove(final IntConsumer onClauseRemove) {
        this.onClauseRemove = onClauseRemove;
    }

    /// @param onClauseAdd Told when clauseadd.
    public void setOnClauseAdd(final Runnable onClauseAdd) {
        this.onClauseAdd = onClauseAdd;
    }

    /// @param onGuardJson Told when guardjson.
    public void setOnGuardJson(final Consumer<String> onGuardJson) {
        this.onGuardJson = onGuardJson;
    }

    /// @param limitsChangeHandler Told when limitschange.
    public void setOnLimitsChange(final LimitsChangeHandler limitsChangeHandler) {
        this.limitsChangeHandler = limitsChangeHandler;
    }

    interface Binder extends UiBinder<Widget, GuardAndLimits> {

    }

    /// What [#setOnLimitsChange] is given.
    public interface LimitsChangeHandler {

        /// @param min The min.
        /// @param max The max.
        /// @param only The only.

        void onLimitsChange(final String min, final String max, final String only);
    }

}
