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

package stroom.shapeshifter.client.presenter;

import stroom.alert.client.event.AlertEvent;
import stroom.shapeshifter.client.presenter.ConditionEditPresenter.ConditionEditView;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.ProjectJson;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A condition on its own, for a {@code when} branch: the clause editor in a dialog. Rows are
 * kept as rows until OK, so a half-built branch never reaches the model (design 18 §5.6's
 * Escape-reverts rule, in dialog form).
 */
public class ConditionEditPresenter extends MyPresenterWidget<ConditionEditView> {

    private List<GuardClause> clauses = new ArrayList<>();
    private String json;

    @Inject
    public ConditionEditPresenter(final EventBus eventBus, final ConditionEditView view) {
        super(eventBus, view);
        view.getClauses().setListener(new stroom.shapeshifter.client.view.ClauseListPanel.Listener() {
            @Override
            public void onClauseChange(final int index, final GuardClause clause) {
                if (index >= 0 && index < clauses.size()) {
                    clauses.set(index, clause);
                }
            }

            @Override
            public void onClauseRemove(final int index) {
                if (index >= 0 && index < clauses.size()) {
                    clauses.remove(index);
                    view.getClauses().setClauses(clauses);
                }
            }

            @Override
            public void onClauseAdd() {
                clauses = view.getClauses().getClauses();
                clauses.add(new GuardClause("", GuardClause.Op.EQ, "", null));
                view.getClauses().setClauses(clauses);
            }

            @Override
            public void onJson(final String text) {
                json = text;
            }
        });
    }

    public void read(final Condition condition, final List<String> names) {
        getView().getClauses().setNames(names);
        getView().getClauses().setError(null);
        final List<GuardClause> rows = GuardClause.read(condition);
        if (rows != null) {
            clauses = new ArrayList<>(rows);
            json = null;
            getView().getClauses().setClauses(clauses);
        } else {
            clauses = new ArrayList<>();
            json = JsonText.printPretty(ProjectJson.writeCondition(condition));
            getView().getClauses().setJson(json);
        }
    }

    /** The condition, or null after telling the user what is wrong; a condition is required. */
    public Condition write() {
        try {
            final Condition condition;
            if (getView().getClauses().isWireForm()) {
                condition = ProjectJson.readCondition(JsonText.parse(getView().getClauses().getJson()));
            } else {
                final List<GuardClause> filled = new ArrayList<>();
                for (final GuardClause clause : getView().getClauses().getClauses()) {
                    if (!clause.isBlank()) {
                        filled.add(clause);
                    }
                }
                condition = GuardClause.write(filled);
            }
            if (condition == null) {
                throw new ConfigException("A branch needs a condition");
            }
            return condition;
        } catch (final ConfigException e) {
            AlertEvent.fireWarn(this, e.getMessage(), null);
            return null;
        }
    }

    public void show(final String caption, final HidePopupRequestEvent.Handler handler) {
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(620, 320))
                .caption(caption)
                .onHideRequest(handler)
                .fire();
    }

    public interface ConditionEditView extends View {

        stroom.shapeshifter.client.view.ClauseListPanel getClauses();
    }
}
