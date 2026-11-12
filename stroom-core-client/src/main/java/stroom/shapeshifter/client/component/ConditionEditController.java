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

import stroom.alert.client.event.AlertEvent;
import stroom.shapeshifter.client.model.GuardClause;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.ProjectJson;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;
import stroom.widget.popup.client.event.HidePopupRequestEvent;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;

/// A condition on its own, for a `when` branch: the clause editor in a dialog. Rows are
/// kept as rows until OK, so a half-built branch never reaches the model (design 18 §5.6's
/// Escape-reverts rule, in dialog form).
public class ConditionEditController extends Controller {

    private final ConditionEdit view;

    private List<GuardClause> clauses = new ArrayList<>();
    private String json;

    @Inject
    public ConditionEditController(final EventBus eventBus, final ConditionEdit view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        view.getClauses().setOnChange((index, clause) -> {
            if (index >= 0 && index < clauses.size()) {
                clauses.set(index, clause);
            }
        });
        view.getClauses().setOnRemove(index -> {
            if (index >= 0 && index < clauses.size()) {
                clauses.remove(index);
                view.getClauses().setClauses(clauses, view.getClauses().getJoin());
            }
        });
        view.getClauses().setOnAdd(() -> {
            clauses = view.getClauses().getClauses();
            clauses.add(new GuardClause("", GuardClause.Op.EQ, "", null));
            view.getClauses().setClauses(clauses, view.getClauses().getJoin());
        });
        view.getClauses().setOnJson(text -> json = text);
    }

    public void read(final Condition condition, final List<String> names) {
        view.getClauses().setNames(names);
        view.getClauses().setError(null);
        final GuardClause.Rows read = GuardClause.read(condition);
        if (read != null) {
            clauses = new ArrayList<>(read.rows());
            json = null;
            view.getClauses().setClauses(clauses, read.join());
        } else {
            clauses = new ArrayList<>();
            json = JsonText.printPretty(ProjectJson.writeCondition(condition));
            view.getClauses().setJson(json);
        }
    }

    /// The condition, or null after telling the user what is wrong; a condition is required.
    public Condition write() {
        try {
            final Condition condition;
            if (view.getClauses().isWireForm()) {
                condition = ProjectJson.readCondition(JsonText.parse(view.getClauses().getJson()));
            } else {
                final List<GuardClause> filled = new ArrayList<>();
                for (final GuardClause clause : view.getClauses().getClauses()) {
                    if (!clause.isBlank()) {
                        filled.add(clause);
                    }
                }
                condition = GuardClause.write(filled, view.getClauses().getJoin());
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

    /// @param opener  What shows it, which owns it until it is hidden.
    /// @param caption The dialog's caption.
    /// @param handler Told when OK or Cancel is pressed.
    public void show(final HasControllerNode opener,
                     final String caption,
                     final HidePopupRequestEvent.Handler handler) {
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(620, 320))
                .caption(caption)
                .onHideRequest(handler)
                .fire();
    }

}
