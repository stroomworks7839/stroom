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
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.presenter.ShapeshifterDesignPresenter.ShapeshifterDesignView;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterMessage;
import stroom.shapeshifter.shared.ShapeshifterPreviewRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.shapeshifter.shared.ShapeshifterTrace;
import stroom.util.client.DelayedUpdate;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.logical.shared.HasValueChangeHandlers;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.event.shared.LegacyHandlerWrapper;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Design tab's root (design 43 §4): owns the {@link Project}, the selection, and the one
 * place an edit lands. Every child edits through {@link ProjectHost#replace}; the root re-renders
 * the children, tells the document presenter (a {@link ValueChangeEvent} of the new project), and
 * asks the engine what it thinks of it on a debounce.
 *
 * <p>The frame is the mockup's (43 §4.1): the template panel; the breadcrumb, input and
 * variables cells over the strip and output cells, and the pattern workbench opening in place
 * of the crumb, input, variables and strip when a match chip is clicked.
 *
 * <p>Selection is two things owned here: the selected template's id, or null for the project,
 * pushed to the strip and the open workbench; and the cursor — the selected frame of the last
 * run's trace (design 18 §5.1) — which the crumb, the input, variables and output panes follow.
 * Selecting a frame selects its template, so the strip shows the definition the cursor is an
 * instance of.
 *
 * <p>The run (phase B): a sample is pasted into the input pane's empty state (design 18 Q2's
 * first door), and every edit runs the project over it again on a short debounce (Q6), so the
 * trace is never far behind the definition; between the edit and the answer it is stale.
 */
public class ShapeshifterDesignPresenter
        extends MyPresenterWidget<ShapeshifterDesignView>
        implements ProjectHost, HasValueChangeHandlers<Project>, TemplateStripPresenter.Listener {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final RestFactory restFactory;
    private final TemplatePanelPresenter templatePanel;
    private final TemplateStripPresenter strip;
    private final PatternWorkbenchPresenter workbench;
    private final MessagesPresenter messages;
    private final BreadcrumbPresenter crumb;
    private final ContentPanePresenter input;
    private final VariablesPanePresenter variables;
    private final OutputPanePresenter output;
    private final DelayedUpdate validate;
    private final DelayedUpdate rerun;

    private Project project;
    private final Map<String, String> colours = new HashMap<>();
    private String sourceError;
    private boolean readOnly = true;
    private boolean workbenchOpen;
    private List<ShapeshifterMessage> lastMessages;

    private String sample;
    private TraceModel trace;
    private long cursor = TraceModel.ROOT;
    private boolean stale;
    private boolean running;
    private boolean runAgain;

    @Inject
    public ShapeshifterDesignPresenter(final EventBus eventBus,
                                       final ShapeshifterDesignView view,
                                       final RestFactory restFactory,
                                       final TemplatePanelPresenter templatePanel,
                                       final TemplateStripPresenter strip,
                                       final PatternWorkbenchPresenter workbench,
                                       final MessagesPresenter messages,
                                       final BreadcrumbPresenter crumb,
                                       final ContentPanePresenter input,
                                       final VariablesPanePresenter variables,
                                       final OutputPanePresenter output) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.templatePanel = templatePanel;
        this.strip = strip;
        this.workbench = workbench;
        this.messages = messages;
        this.crumb = crumb;
        this.input = input;
        this.variables = variables;
        this.output = output;
        this.validate = new DelayedUpdate(400, this::validate);
        this.rerun = new DelayedUpdate(600, this::run);
        templatePanel.setHost(this);
        strip.setHost(this);
        strip.setListener(this);
        workbench.setHost(this);
        workbench.setOnClose(this::closeWorkbench);
        crumb.setHost(this);
        crumb.setOnSample(input::editSample);
        input.setHost(this);
        variables.setHost(this);
        output.setHost(this);
        view.setTemplatePanel(templatePanel.getView());
        view.setStrip(strip.getView());
        view.setWorkbench(workbench.getView());
        view.setMessages(messages.getView());
        view.setCrumb(crumb.getView());
        view.setInput(input.getView());
        view.setVariables(variables.getView());
        view.setOutput(output.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(templatePanel.addValueChangeHandler(event -> onSelect(event.getValue())));
    }

    /**
     * The document as read, or as the Source tab last parsed it. A null project with a source
     * error keeps the previous project on screen, read-only, under the error.
     */
    public void read(final Project project, final Map<String, String> colours, final String sourceError,
                     final boolean readOnly) {
        this.readOnly = readOnly;
        this.sourceError = sourceError;
        if (project != null) {
            this.project = project;
        }
        if (colours != null) {
            this.colours.clear();
            this.colours.putAll(colours);
        }
        getView().setBanner(sourceError == null
                ? null
                : "The Source tab does not parse, so this tab shows the last good project read-only: " + sourceError);
        refresh();
        if (project != null) {
            check();
        } else {
            messages.setMessages(sourceError, lastMessages);
        }
    }

    /** The engine's opinion of the project: a run when there is a sample, a validation otherwise. */
    private void check() {
        if (sample != null) {
            stale = true;
            refreshTrace();
            rerun.update();
        } else {
            validate.update();
        }
    }

    @Override
    public Project getProject() {
        return project;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly || sourceError != null;
    }

    /** The colour overrides as they stand, for the document to keep: only for templates that still exist. */
    public Map<String, String> getColours() {
        final Map<String, String> kept = new HashMap<>();
        for (final Map.Entry<String, String> entry : colours.entrySet()) {
            if (template(entry.getKey()) != null) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        return kept;
    }

    @Override
    public String colour(final String templateId) {
        final String override = colours.get(templateId);
        if (override != null) {
            return override;
        }
        final List<Template> templates = project == null
                ? List.of()
                : project.templates();
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).id().equals(templateId)) {
                return Templates.colour(i);
            }
        }
        return "transparent";
    }

    @Override
    public void setColour(final String templateId, final String colour) {
        if (isReadOnly() || templateId == null) {
            return;
        }
        final String before = colours.get(templateId);
        if (Objects.equals(before, colour)) {
            return;
        }
        if (colour == null) {
            colours.remove(templateId);
        } else {
            colours.put(templateId, colour);
        }
        refresh();
        // The document is dirty for it; the project is unchanged, and the run never stale for a colour.
        ValueChangeEvent.fire(this, project);
    }

    @Override
    public void replace(final Project next) {
        if (next == null || isReadOnly()) {
            return;
        }
        project = next;
        refresh();
        ValueChangeEvent.fire(this, next);
        check();
    }

    private void refresh() {
        templatePanel.refresh();
        onSelect(templatePanel.getSelectedTemplateId());
    }

    // ---- the run and the cursor ----

    @Override
    public String getSample() {
        return sample;
    }

    @Override
    public void setSample(final String sample) {
        this.sample = sample == null || sample.isEmpty()
                ? null
                : sample;
        if (this.sample == null) {
            trace = null;
            cursor = TraceModel.ROOT;
            stale = false;
            refreshTrace();
            validate.update();
        } else {
            run();
        }
    }

    @Override
    public void run() {
        if (project == null || sample == null) {
            return;
        }
        if (running) {
            // One answer at a time; the edit that arrived meanwhile runs when this one lands.
            runAgain = true;
            return;
        }
        running = true;
        stale = true;
        crumb.refresh();
        final ShapeshifterPreviewRequest request = new ShapeshifterPreviewRequest(ProjectText.print(project), sample);
        restFactory
                .create(RESOURCE)
                .method(res -> res.preview(request))
                .onSuccess(result -> {
                    running = false;
                    trace = new TraceModel(result);
                    stale = false;
                    lastMessages = result.getMessages();
                    messages.setMessages(sourceError, lastMessages);
                    if (!trace.has(cursor)) {
                        cursor = TraceModel.ROOT;
                    }
                    refreshTrace();
                    if (runAgain) {
                        runAgain = false;
                        run();
                    }
                })
                .onFailure(error -> {
                    // No trace to be stale against; the crumb and the pane say the run failed.
                    running = false;
                    runAgain = false;
                    stale = false;
                    AlertEvent.fireError(this, "The run failed: " + error.getMessage(), null);
                    refreshTrace();
                })
                .taskMonitorFactory(this)
                .exec();
    }

    @Override
    public TraceModel trace() {
        return trace;
    }

    @Override
    public boolean isStale() {
        return stale || running;
    }

    @Override
    public long cursor() {
        return cursor;
    }

    @Override
    public void setCursor(final long frameId) {
        if (trace == null || !trace.has(frameId)) {
            return;
        }
        cursor = frameId;
        // A frame selects its template (design 18 §5.1): the strip shows what the cursor is an instance of.
        final ShapeshifterTrace.Frame frame = trace.frame(frameId);
        templatePanel.select(frame == null
                ? null
                : frame.getTemplateId());
        refreshTrace();
    }

    /** Everything that reads the trace or the cursor, after either changes. */
    private void refreshTrace() {
        templatePanel.refresh();
        strip.setTemplate(strip.getTemplateId());
        crumb.refresh();
        input.refresh();
        variables.refresh();
        output.refresh();
    }

    private void onSelect(final String templateId) {
        final String id = template(templateId) == null
                ? null
                : templateId;
        strip.setTemplate(id);
        if (workbenchOpen) {
            // The workbench follows the selection (design 18 §5.6: retargeted in place); the
            // document has no match to edit, so selecting it closes the workbench.
            if (id == null) {
                closeWorkbench();
            } else {
                workbench.setTemplate(id);
            }
        }
    }

    @Override
    public void editIdentity() {
        templatePanel.editSelected();
    }

    @Override
    public void openWorkbench(final String templateId) {
        if (template(templateId) == null) {
            return;
        }
        workbenchOpen = true;
        workbench.setTemplate(templateId);
        getView().showWorkbench(true);
    }

    private void closeWorkbench() {
        if (workbenchOpen) {
            workbenchOpen = false;
            getView().showWorkbench(false);
        }
    }

    private void validate() {
        if (project == null) {
            return;
        }
        final String text = ProjectText.print(project);
        restFactory
                .create(RESOURCE)
                .method(res -> res.validate(text))
                .onSuccess(result -> {
                    lastMessages = result.getMessages();
                    messages.setMessages(sourceError, lastMessages);
                })
                .taskMonitorFactory(this)
                .exec();
    }

    @Override
    public HandlerRegistration addValueChangeHandler(final ValueChangeHandler<Project> handler) {
        return new LegacyHandlerWrapper(addHandlerToSource(ValueChangeEvent.getType(), handler));
    }

    public interface ShapeshifterDesignView extends View {

        void setTemplatePanel(View view);

        void setCrumb(View view);

        void setInput(View view);

        void setVariables(View view);

        void setOutput(View view);

        void setStrip(View view);

        void setWorkbench(View view);

        /** The workbench in place of the crumb, input, variables and strip; or those back. */
        void showWorkbench(boolean open);

        void setMessages(View view);

        /** A line above everything, or nothing: the Source tab's syntax error while it has one. */
        void setBanner(String text);
    }
}
