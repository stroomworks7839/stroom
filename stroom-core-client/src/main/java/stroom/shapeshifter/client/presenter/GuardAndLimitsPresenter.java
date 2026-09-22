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

import stroom.shapeshifter.client.presenter.GuardAndLimitsPresenter.GuardAndLimitsView;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.EngineVars;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.MatchLimits;
import stroom.shapeshifter.config.Template.ParamDecl;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.ProjectJson;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Guard and limits (design 18 §5.6): mechanism-independent — the engine's dispatch order is
 * mode → guard → match → limits whatever the match kind — so they belong to the workbench, not
 * to any kind of match. The guard is clause rows (variable · operator · value) joined by <i>and</i>, the
 * names in scope offered; a guard the rows cannot express is edited as its wire form. Limits
 * are three fields with their semantics stated inline. Everything commits as a field is left.
 * The live verdict against the current frame waits for the trace (phase B).
 */
public class GuardAndLimitsPresenter
        extends MyPresenterWidget<GuardAndLimitsView>
        implements GuardAndLimitsUiHandlers {

    private ProjectHost host;
    private String templateId;

    @Inject
    public GuardAndLimitsPresenter(final EventBus eventBus, final GuardAndLimitsView view) {
        super(eventBus, view);
        view.setUiHandlers(this);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    public void setTemplate(final String id) {
        final boolean sameTemplate = id.equals(templateId);
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null) {
            return;
        }
        getView().setEnabled(!host.isReadOnly());
        getView().setNames(namesInScope(template));
        final List<GuardClause> clauses = GuardClause.read(template.guard());
        if (clauses != null) {
            // The rows are rebuilt only when the model disagrees with them: a commit of the
            // rows' own state - the blur before a click on ✕, say - must not replace the widgets
            // under the pointer, and a row added and not yet filled is not in the model to
            // rebuild from.
            if (!sameTemplate || getView().isWireForm() || !clauses.equals(filled(getView().getClauses()))) {
                getView().setClauses(clauses);
            }
        } else {
            getView().setGuardJson(JsonText.printPretty(ProjectJson.writeCondition(template.guard())));
        }
        getView().setError(null);
        final MatchLimits limits = template.matchLimits();
        getView().setLimits(limits.minMatch() == 0
                ? ""
                : String.valueOf(limits.minMatch()), limits.maxMatch() == MatchLimits.UNLIMITED
                ? ""
                : String.valueOf(limits.maxMatch()), only(limits));
    }

    /** What a clause's variable can name: the template's declarations and params, and the functions. */
    private static List<String> namesInScope(final Template template) {
        final Set<String> names = new LinkedHashSet<>();
        for (final Declaration declaration : template.declarations()) {
            names.add(declaration.name());
        }
        for (final ParamDecl param : template.param()) {
            names.add(param.name());
        }
        for (final EngineVars function : EngineVars.values()) {
            names.add(function.spelling());
        }
        return new ArrayList<>(names);
    }

    private static String only(final MatchLimits limits) {
        if (limits.onlyMatch() == null || limits.onlyMatch().isEmpty()) {
            return "";
        }
        final List<Integer> sorted = new ArrayList<>(limits.onlyMatch());
        sorted.sort(null);
        final StringBuilder sb = new StringBuilder();
        for (final Integer index : sorted) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(index);
        }
        return sb.toString();
    }

    @Override
    public void onClauseChange(final int index, final GuardClause clause) {
        final List<GuardClause> clauses = getView().getClauses();
        if (index < 0 || index >= clauses.size()) {
            return;
        }
        clauses.set(index, clause);
        commitClauses(clauses);
    }

    @Override
    public void onClauseRemove(final int index) {
        final List<GuardClause> clauses = getView().getClauses();
        if (index < 0 || index >= clauses.size()) {
            return;
        }
        clauses.remove(index);
        // The row goes whether or not the guard changes: a row never filled was never in it.
        getView().setClauses(clauses);
        commitClauses(clauses);
    }

    @Override
    public void onClauseAdd() {
        final List<GuardClause> clauses = getView().getClauses();
        clauses.add(new GuardClause("", GuardClause.Op.EQ, "", null));
        // An empty clause is not yet a condition: show the row, commit when it is filled in.
        getView().setClauses(clauses);
    }

    private static List<GuardClause> filled(final List<GuardClause> clauses) {
        final List<GuardClause> filled = new ArrayList<>();
        for (final GuardClause clause : clauses) {
            if (!clause.isBlank()) {
                filled.add(clause);
            }
        }
        return filled;
    }

    private void commitClauses(final List<GuardClause> clauses) {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        // A row just added and not yet touched is not a clause: it is skipped, not refused, and
        // an unchanged guard is not written back.
        final List<GuardClause> filled = filled(clauses);
        final Condition guard;
        try {
            guard = GuardClause.write(filled);
        } catch (final ConfigException e) {
            getView().setError(e.getMessage());
            return;
        }
        getView().setError(null);
        if (Objects.equals(guard, template.guard())) {
            return;
        }
        host.replace(host.withTemplate(Templates.withGuard(template, guard)));
    }

    @Override
    public void onGuardJson(final String json) {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        final Condition guard;
        try {
            guard = json.trim().isEmpty()
                    ? null
                    : ProjectJson.readCondition(JsonText.parse(json));
        } catch (final ConfigException e) {
            getView().setError(e.getMessage());
            return;
        }
        getView().setError(null);
        host.replace(host.withTemplate(Templates.withGuard(template, guard)));
    }

    @Override
    public void onLimitsChange(final String min, final String max, final String only) {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        final MatchLimits limits;
        try {
            final Set<Integer> onlySet = new LinkedHashSet<>();
            for (final String part : only.split(",")) {
                if (!part.trim().isEmpty()) {
                    onlySet.add(Integer.parseInt(part.trim()));
                }
            }
            limits = new MatchLimits(min.trim().isEmpty()
                    ? 0
                    : Integer.parseInt(min.trim()), max.trim().isEmpty()
                    ? MatchLimits.UNLIMITED
                    : Integer.parseInt(max.trim()), onlySet.isEmpty()
                    ? null
                    : onlySet);
        } catch (final NumberFormatException e) {
            getView().setError("Limits are whole numbers: min, max, and the only indexes separated by commas");
            return;
        } catch (final ConfigException e) {
            getView().setError(e.getMessage());
            return;
        }
        if (limits.equals(template.matchLimits())) {
            return;
        }
        getView().setError(null);
        host.replace(host.withTemplate(Templates.withLimits(template, limits)));
    }

    public interface GuardAndLimitsView extends View, HasUiHandlers<GuardAndLimitsUiHandlers> {

        void setEnabled(boolean enabled);

        void setNames(List<String> names);

        /** Show the guard as rows; the wire-form editor goes. */
        void setClauses(List<GuardClause> clauses);

        /** The rows as they stand, an empty list when the wire form is showing. */
        List<GuardClause> getClauses();

        /** Whether the guard is showing as its wire form rather than rows. */
        boolean isWireForm();

        /** Show the guard as its wire form; the rows go. */
        void setGuardJson(String json);

        void setLimits(String min, String max, String only);

        void setError(String error);
    }
}
