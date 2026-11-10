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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.client.model.GuardClause;
import stroom.shapeshifter.client.model.Hot;
import stroom.shapeshifter.client.model.MatchRole;
import stroom.shapeshifter.client.model.Profile;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.client.model.TraceModel;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.ApplyTemplates;
import stroom.shapeshifter.config.OutputNode.Holder;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.MatchLimits;
import stroom.shapeshifter.shared.ShapeshifterTrace.Timing;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The template strip (design 18 §5.6): "what it is", beneath the panes that say what
 * happened. Its title line is the template's identity — swatch, name, mode, where it is
 * dispatched from, computed from the mode graph — and its body opens with the match as a
 * read-only chip that opens the pattern workbench, then guard and limits as a summary, then the
 * body as cards; the declarations and captures grids sit beneath.
 * For the project row the strip shows the document: its name and source settings.
 */
public class TemplateStripController extends Controller {

    private final TemplateStrip view;

    private final SourceSettingsController sourceConfig;
    private final BodyController bodyPresenter;
    private final DeclarationsController declarations;
    private final CapturesController captures;

    private ProjectHost host;
    private Listener listener;
    private String templateId;

    @Inject
    public TemplateStripController(final EventBus eventBus,
                                  final TemplateStrip view,
                                  final SourceSettingsController sourceConfig,
                                  final BodyController bodyPresenter,
                                  final DeclarationsController declarations,
                                  final CapturesController captures) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        view.setOnEditIdentity(this::onEditIdentity);
        view.setOnRole(this::onRole);
        view.setOnOpenWorkbench(this::onOpenWorkbench);
        this.sourceConfig = own(sourceConfig);
        this.bodyPresenter = own(bodyPresenter);
        this.declarations = own(declarations);
        this.captures = own(captures);
        view.setDetails(declarations, captures);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        sourceConfig.setHost(host);
        bodyPresenter.setHost(host);
        declarations.setHost(host);
        captures.setHost(host);
    }

    public void setListener(final Listener listener) {
        this.listener = listener;
    }

    /** Show a template, or the project for null; called again after every edit, so it is also refresh. */
    public void setTemplate(final String id) {
        this.templateId = id;
        final Project project = host.getProject();
        final Template template = host.template(id);
        if (template == null) {
            view.setHeader("document", null, project == null
                    ? ""
                    : project.name(), "", "the root frame: every template descends from here");
            view.setRole(false, MatchRole.RECORD, false, "", false);
            view.setMatchVisible(false);
            view.setDetailsVisible(false);
            sourceConfig.refresh();
            view.setContent(sourceConfig);
            return;
        }
        view.setHeader("template", host.colour(template.id()), template.name(), template.mode() == null
                ? "root"
                : "mode " + template.mode(),
                "dispatched from " + dispatchedFrom(project, template) + runNote(template));
        view.setMatch(Templates.kind(template.match()), Templates.describe(template.match()),
                guardSummary(template.guard()) + guardVerdict(template) + " · "
                + limitsSummary(template.matchLimits()));
        view.setRole(true, MatchRole.of(template.consume()), !host.isReadOnly(), roleNote(template),
                template.consume() && !template.captures().isEmpty());
        view.setMatchVisible(true);
        bodyPresenter.setTemplate(id);
        view.setContent(bodyPresenter);
        declarations.setTemplate(id);
        captures.setTemplate(id);
        view.setDetailsVisible(true);
    }

    public void setHot(final Hot hot) {
        bodyPresenter.setHot(hot);
    }

    /** After a run, what it made of this template: how many matches, or where it was tried for none. */
    private String runNote(final Template template) {
        final TraceModel trace = host.trace();
        final Timing timing = trace == null
                ? null
                : trace.timing(template.id());
        if (trace == null) {
            return "";
        }
        if (timing == null || timing.getAttempts() == 0) {
            return " · not tried in this run";
        }
        if (timing.getMatched() == 0) {
            return " · no matches · " + Profile.describe(trace, template.id());
        }
        return " · " + Profile.describe(trace, template.id());
    }

    public String getTemplateId() {
        return templateId;
    }

    public void onEditIdentity() {
        if (listener != null && templateId != null) {
            listener.editIdentity();
        }
    }

    /**
     * What the picked role means, said beside the picker — {@link MatchRole#note()}, except where
     * the role and the captures contradict each other. Captures are not merely ignored by an
     * advance-only match but refused at compile time, so the author is told at the moment they
     * cause it rather than at the next run.
     */
    private String roleNote(final Template template) {
        final int captures = template.captures().size();
        if (template.consume() && captures > 0) {
            return "this template binds " + captures + (captures == 1
                    ? " capture"
                    : " captures") + ", and an advance-only match has no index to bind at:"
                   + " the project will not compile until they go or the role does";
        }
        return MatchRole.of(template.consume()).note();
    }

    /**
     * The role is the one identity field the strip writes itself: it is a single bit, it changes
     * whether this template records anything at all, and it is the answer when a template matches
     * and the navigator shows nothing (design 44 §5d, §5f).
     */
    public void onRole(final MatchRole role) {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly() || template.consume() == role.consume()) {
            return;
        }
        host.replace(host.withTemplate(Templates.withRole(template, role.consume())));
    }

    public void onOpenWorkbench() {
        if (listener != null && templateId != null) {
            listener.openWorkbench(templateId);
        }
    }

    /**
     * The static complement to breadcrumb ancestry: the sites whose apply-templates dispatch
     * into this template's mode — "record (body pos 3)" — or the document for the root.
     */
    static String dispatchedFrom(final Project project, final Template template) {
        final List<String> sites = new ArrayList<>();
        for (final Template t : project.templates()) {
            // "body pos" is the instruction's position among the body's top-level instructions,
            // whether the site is that instruction or sits inside its branches.
            for (int i = 0; i < t.body().size(); i++) {
                if (appliesInto(t.body().get(i), template.mode())) {
                    sites.add(t.name() + " (body pos " + (i + 1) + ")");
                }
            }
        }
        if (template.mode() == null) {
            sites.add(0, "the document");
        }
        return sites.isEmpty()
                ? "nothing applies into mode " + template.mode()
                : String.join(", ", sites);
    }

    private static boolean appliesInto(final OutputNode node, final String mode) {
        if (node instanceof ApplyTemplates apply) {
            return Objects.equals(apply.directive().mode(), mode);
        }
        if (node instanceof Holder holder) {
            for (final List<OutputNode> body : holder.bodies()) {
                for (final OutputNode inner : body) {
                    if (appliesInto(inner, mode)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The guard's verdicts from the run (design 18 §5.6): at the cursor, when the cursor's body
     * dispatched to this template's mode; and over the run, how often it held and was refused.
     */
    private String guardVerdict(final Template template) {
        final TraceModel trace = host.trace();
        if (template.guard() == null || trace == null) {
            return "";
        }
        final StringBuilder text = new StringBuilder();
        final Boolean atCursor = trace.guard(trace.cursorOf(), template.id());
        if (atCursor != null) {
            text.append(" — ").append(atCursor
                    ? "held"
                    : "refused").append(" at ").append(trace.label(trace.cursorOf()));
        }
        final int[] counts = trace.guardCounts(template.id());
        if (counts[0] + counts[1] > 0) {
            text.append(atCursor == null
                    ? " — "
                    : "; ").append("held in ").append(counts[0]).append(", refused in ").append(counts[1])
                    .append(counts[0] + counts[1] == 1
                            ? " frame"
                            : " frames");
        }
        return text.toString();
    }

    static String guardSummary(final Condition guard) {
        return GuardClause.describe(guard);
    }

    static String limitsSummary(final MatchLimits limits) {
        final List<String> parts = new ArrayList<>();
        if (limits.minMatch() > 0) {
            parts.add("min " + limits.minMatch());
        }
        if (limits.maxMatch() != MatchLimits.UNLIMITED) {
            parts.add("max " + limits.maxMatch());
        }
        if (limits.onlyMatch() != null && !limits.onlyMatch().isEmpty()) {
            parts.add("only " + limits.onlyMatch());
        }
        return parts.isEmpty()
                ? "no limits"
                : String.join(" · ", parts);
    }

    /** What the strip asks of the root: the dialogs and the workbench are the root's to open. */
    public interface Listener {

        void editIdentity();

        void openWorkbench(String templateId);
    }

}
