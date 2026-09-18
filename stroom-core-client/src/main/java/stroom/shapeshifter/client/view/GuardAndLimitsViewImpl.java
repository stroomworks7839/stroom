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

import stroom.shapeshifter.client.presenter.GuardAndLimitsPresenter.GuardAndLimitsView;
import stroom.shapeshifter.client.presenter.GuardAndLimitsUiHandlers;
import stroom.shapeshifter.client.presenter.GuardClause;

import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.List;

public class GuardAndLimitsViewImpl
        extends ViewWithUiHandlers<GuardAndLimitsUiHandlers>
        implements GuardAndLimitsView, ClauseListPanel.Listener {

    private final Widget widget;

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

    @Inject
    public GuardAndLimitsViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        clauses.setListener(this);
        clauses.setEmptyText("(no guard — the template is tried whenever its mode applies)");
        setError(null);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @UiHandler("min")
    void onMin(final BlurEvent e) {
        limitsChanged();
    }

    @UiHandler("max")
    void onMax(final BlurEvent e) {
        limitsChanged();
    }

    @UiHandler("only")
    void onOnly(final BlurEvent e) {
        limitsChanged();
    }

    private void limitsChanged() {
        if (getUiHandlers() != null) {
            getUiHandlers().onLimitsChange(min.getText(), max.getText(), only.getText());
        }
    }

    @Override
    public void onClauseChange(final int index, final GuardClause clause) {
        if (getUiHandlers() != null) {
            getUiHandlers().onClauseChange(index, clause);
        }
    }

    @Override
    public void onClauseRemove(final int index) {
        if (getUiHandlers() != null) {
            getUiHandlers().onClauseRemove(index);
        }
    }

    @Override
    public void onClauseAdd() {
        if (getUiHandlers() != null) {
            getUiHandlers().onClauseAdd();
        }
    }

    @Override
    public void onJson(final String json) {
        if (getUiHandlers() != null) {
            getUiHandlers().onGuardJson(json);
        }
    }

    @Override
    public void setEnabled(final boolean enabled) {
        clauses.setEnabled(enabled);
        min.setEnabled(enabled);
        max.setEnabled(enabled);
        only.setEnabled(enabled);
    }

    @Override
    public void setNames(final List<String> names) {
        clauses.setNames(names);
    }

    @Override
    public void setClauses(final List<GuardClause> list) {
        clauses.setClauses(list);
    }

    @Override
    public List<GuardClause> getClauses() {
        return clauses.getClauses();
    }

    @Override
    public void setGuardJson(final String json) {
        clauses.setJson(json);
    }

    @Override
    public void setLimits(final String minText, final String maxText, final String onlyText) {
        min.setText(minText);
        max.setText(maxText);
        only.setText(onlyText);
    }

    @Override
    public void setError(final String text) {
        error.setText(text == null
                ? ""
                : text);
        error.setVisible(text != null);
    }

    public interface Binder extends UiBinder<Widget, GuardAndLimitsViewImpl> {

    }
}
