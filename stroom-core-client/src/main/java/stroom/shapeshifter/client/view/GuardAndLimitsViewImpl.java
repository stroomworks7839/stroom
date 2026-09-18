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
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.ArrayList;
import java.util.List;

public class GuardAndLimitsViewImpl
        extends ViewWithUiHandlers<GuardAndLimitsUiHandlers>
        implements GuardAndLimitsView, GuardClauseRow.Listener {

    private final Widget widget;
    private final List<GuardClauseRow> rows = new ArrayList<>();
    private List<String> names = List.of();
    private boolean enabled = true;
    private String committedJson = "";

    @UiField
    FlowPanel clauses;
    @UiField
    Label noGuard;
    @UiField
    Label addClause;
    @UiField
    FlowPanel jsonPanel;
    @UiField
    TextArea guardJson;
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
        setError(null);
        jsonPanel.setVisible(false);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @UiHandler("addClause")
    void onAdd(final ClickEvent e) {
        if (enabled && getUiHandlers() != null) {
            getUiHandlers().onClauseAdd();
        }
    }

    @UiHandler("guardJson")
    void onJsonBlur(final BlurEvent e) {
        final String text = guardJson.getText();
        if (!text.equals(committedJson) && getUiHandlers() != null) {
            committedJson = text;
            getUiHandlers().onGuardJson(text);
        }
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
    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        addClause.setStyleDependentName("disabled", !enabled);
        guardJson.setEnabled(enabled);
        min.setEnabled(enabled);
        max.setEnabled(enabled);
        only.setEnabled(enabled);
    }

    @Override
    public void setNames(final List<String> names) {
        this.names = names;
    }

    @Override
    public void setClauses(final List<GuardClause> list) {
        clauses.clear();
        rows.clear();
        for (int i = 0; i < list.size(); i++) {
            final GuardClauseRow row = new GuardClauseRow(i, list.get(i), names, this, enabled);
            rows.add(row);
            clauses.add(row);
        }
        noGuard.setVisible(list.isEmpty());
        clauses.setVisible(true);
        addClause.setVisible(true);
        jsonPanel.setVisible(false);
    }

    @Override
    public List<GuardClause> getClauses() {
        final List<GuardClause> list = new ArrayList<>();
        for (final GuardClauseRow row : rows) {
            list.add(row.getClause());
        }
        return list;
    }

    @Override
    public void setGuardJson(final String json) {
        clauses.clear();
        rows.clear();
        noGuard.setVisible(false);
        clauses.setVisible(false);
        addClause.setVisible(false);
        jsonPanel.setVisible(true);
        committedJson = json;
        guardJson.setText(json);
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
