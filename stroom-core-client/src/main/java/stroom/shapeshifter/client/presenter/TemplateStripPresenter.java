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

import stroom.shapeshifter.client.presenter.TemplateStripPresenter.TemplateStripView;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.ApplyTemplates;
import stroom.shapeshifter.config.OutputNode.Holder;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.MatchLimits;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The template strip (design 18 §5.6): "what it is", beneath the panes that say what
 * happened. Its title line is the template's identity — swatch, name, mode, where it is
 * dispatched from, computed from the mode graph — and its body opens with the match as a
 * read-only chip that opens the pattern workbench, then guard and limits as a summary. The
 * body cards follow in phase B; until then the declarations and captures grids sit beneath.
 * For the project row the strip shows the document: its name and source settings.
 */
public class TemplateStripPresenter
        extends MyPresenterWidget<TemplateStripView>
        implements TemplateStripUiHandlers {

    private final SourceConfigPresenter sourceConfig;
    private final DeclarationsPresenter declarations;
    private final CapturesPresenter captures;

    private ProjectHost host;
    private Listener listener;
    private String templateId;

    @Inject
    public TemplateStripPresenter(final EventBus eventBus,
                                  final TemplateStripView view,
                                  final SourceConfigPresenter sourceConfig,
                                  final DeclarationsPresenter declarations,
                                  final CapturesPresenter captures) {
        super(eventBus, view);
        this.sourceConfig = sourceConfig;
        this.declarations = declarations;
        this.captures = captures;
        view.setUiHandlers(this);
        view.setDetails(declarations.getView(), captures.getView());
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        sourceConfig.setHost(host);
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
            getView().setHeader("document", null, project == null
                    ? ""
                    : project.name(), "", "the root frame: every template descends from here");
            getView().setMatchVisible(false);
            getView().setDetailsVisible(false);
            sourceConfig.refresh();
            getView().setContent(sourceConfig.getView());
            return;
        }
        final int index = project.templates().indexOf(template);
        getView().setHeader("template", Templates.colour(index), template.name(), template.mode() == null
                ? "root"
                : "mode " + template.mode(), "dispatched from " + dispatchedFrom(project, template));
        getView().setMatch(Templates.kind(template.match()), Templates.describe(template.match()),
                guardSummary(template.guard()) + " · " + limitsSummary(template.matchLimits()));
        getView().setMatchVisible(true);
        getView().setContent(null);
        declarations.setTemplate(id);
        captures.setTemplate(id);
        getView().setDetailsVisible(true);
    }

    public String getTemplateId() {
        return templateId;
    }

    @Override
    public void onEditIdentity() {
        if (listener != null && templateId != null) {
            listener.editIdentity();
        }
    }

    @Override
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

    public interface TemplateStripView extends View, HasUiHandlers<TemplateStripUiHandlers> {

        /** The identity line: the pane title, the swatch colour (null for none), name, mode, note. */
        void setHeader(String title, String colour, String name, String mode, String note);

        void setMatch(String kind, String summary, String guardAndLimits);

        void setMatchVisible(boolean visible);

        /** What fills the strip's body: the source settings for the document, nothing for a template yet. */
        void setContent(View view);

        void setDetails(View declarations, View captures);

        void setDetailsVisible(boolean visible);
    }
}
