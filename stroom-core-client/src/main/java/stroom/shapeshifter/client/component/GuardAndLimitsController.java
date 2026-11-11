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
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.EngineVars;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.MatchLimits;
import stroom.shapeshifter.config.Template.ParamDecl;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.ProjectJson;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Guard and limits (design 18 §5.6): mechanism-independent — the engine's dispatch order is
/// mode → guard → match → limits whatever the match kind — so they belong to the workbench, not
/// to any kind of match. The guard is clause rows (variable · operator · value) joined by *and*, the
/// names in scope offered; a guard the rows cannot express is edited as its wire form. Limits
/// are three fields with their semantics stated inline. Everything commits as a field is left.
/// The live verdict against the current frame waits for the trace (phase B).
public class GuardAndLimitsController extends Controller {

    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    private final GuardAndLimits view;

    private ProjectHost host;
    private String templateId;

    @Inject
    public GuardAndLimitsController(final EventBus eventBus, final GuardAndLimits view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            applyReadOnly();
        });
        view.setOnClauseChange(this::onClauseChange);
        view.setOnClauseRemove(this::onClauseRemove);
        view.setOnClauseAdd(this::onClauseAdd);
        view.setOnGuardJson(this::onGuardJson);
        view.setOnLimitsChange(this::onLimitsChange);
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
        applyReadOnly();
        view.setNames(namesInScope(template));
        final List<GuardClause> clauses = GuardClause.read(template.guard());
        if (clauses != null) {
            // The rows are rebuilt only when the model disagrees with them: a commit of the
            // rows' own state - the blur before a click on ✕, say - must not replace the widgets
            // under the pointer, and a row added and not yet filled is not in the model to
            // rebuild from.
            if (!sameTemplate || view.isWireForm() || !clauses.equals(filled(view.getClauses()))) {
                view.setClauses(clauses);
            }
        } else {
            view.setGuardJson(JsonText.printPretty(ProjectJson.writeCondition(template.guard())));
        }
        view.setError(null);
        final MatchLimits limits = template.matchLimits();
        view.setLimits(limits.minMatch() == 0
                ? ""
                : String.valueOf(limits.minMatch()), limits.maxMatch() == MatchLimits.UNLIMITED
                ? ""
                : String.valueOf(limits.maxMatch()), only(limits));
    }

    /// What a clause's variable can name: the template's declarations and params, and the functions.
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

    public void onClauseChange(final int index, final GuardClause clause) {
        final List<GuardClause> clauses = view.getClauses();
        if (index < 0 || index >= clauses.size()) {
            return;
        }
        clauses.set(index, clause);
        commitClauses(clauses);
    }

    public void onClauseRemove(final int index) {
        final List<GuardClause> clauses = view.getClauses();
        if (index < 0 || index >= clauses.size()) {
            return;
        }
        clauses.remove(index);
        // The row goes whether or not the guard changes: a row never filled was never in it.
        view.setClauses(clauses);
        commitClauses(clauses);
    }

    public void onClauseAdd() {
        final List<GuardClause> clauses = view.getClauses();
        clauses.add(new GuardClause("", GuardClause.Op.EQ, "", null));
        // An empty clause is not yet a condition: show the row, commit when it is filled in.
        view.setClauses(clauses);
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
        if (template == null || readOnly) {
            return;
        }
        // A row just added and not yet touched is not a clause: it is skipped, not refused, and
        // an unchanged guard is not written back.
        final List<GuardClause> filled = filled(clauses);
        final Condition guard;
        try {
            guard = GuardClause.write(filled);
        } catch (final ConfigException e) {
            view.setError(e.getMessage());
            return;
        }
        view.setError(null);
        if (Objects.equals(guard, template.guard())) {
            return;
        }
        host.replace(host.withTemplate(Templates.withGuard(template, guard)));
    }

    public void onGuardJson(final String json) {
        final Template template = host.template(templateId);
        if (template == null || readOnly) {
            return;
        }
        final Condition guard;
        try {
            guard = json.trim().isEmpty()
                    ? null
                    : ProjectJson.readCondition(JsonText.parse(json));
        } catch (final ConfigException e) {
            view.setError(e.getMessage());
            return;
        }
        view.setError(null);
        host.replace(host.withTemplate(Templates.withGuard(template, guard)));
    }

    public void onLimitsChange(final String min, final String max, final String only) {
        final Template template = host.template(templateId);
        if (template == null || readOnly) {
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
            view.setError("Limits are whole numbers: min, max, and the only indexes separated by commas");
            return;
        } catch (final ConfigException e) {
            view.setError(e.getMessage());
            return;
        }
        if (limits.equals(template.matchLimits())) {
            return;
        }
        view.setError(null);
        host.replace(host.withTemplate(Templates.withLimits(template, limits)));
    }

    // One place that says what read-only means here, called when the context changes and
    // when the pane is filled, so a lock change alone reaches the view.
    private void applyReadOnly() {
        view.setReadOnly(readOnly);
    }

}
