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

import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.presenter.ShapeshifterDesignPresenter.ShapeshifterDesignView;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.shared.ShapeshifterMessage;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.util.client.DelayedUpdate;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.logical.shared.HasValueChangeHandlers;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.event.shared.LegacyHandlerWrapper;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;

/**
 * The Design tab's root (design 43 §4): owns the {@link Project}, the selection, and the one
 * place an edit lands. Every child edits through {@link ProjectHost#replace}; the root re-renders
 * the children, tells the document presenter (a {@link ValueChangeEvent} of the new project), and
 * asks the engine what it thinks of it on a debounce.
 *
 * <p>The frame is the mockup's (43 §4.1): the template panel; the breadcrumb, input and
 * variables cells over the strip and output cells — the trace cells present with their empty
 * states until phase B — and the pattern workbench opening in place of the crumb, input,
 * variables and strip when a match chip is clicked.
 *
 * <p>Selection is one object — the selected template's id, or null for the project — owned here
 * and pushed to the strip and the open workbench; the template panel is its only author today.
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
    private final DelayedUpdate validate;

    private Project project;
    private String sourceError;
    private boolean readOnly = true;
    private boolean workbenchOpen;
    private List<ShapeshifterMessage> lastMessages;

    @Inject
    public ShapeshifterDesignPresenter(final EventBus eventBus,
                                       final ShapeshifterDesignView view,
                                       final RestFactory restFactory,
                                       final TemplatePanelPresenter templatePanel,
                                       final TemplateStripPresenter strip,
                                       final PatternWorkbenchPresenter workbench,
                                       final MessagesPresenter messages,
                                       final Provider<TracePanePresenter> paneProvider) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.templatePanel = templatePanel;
        this.strip = strip;
        this.workbench = workbench;
        this.messages = messages;
        this.validate = new DelayedUpdate(400, this::validate);
        templatePanel.setHost(this);
        strip.setHost(this);
        strip.setListener(this);
        workbench.setHost(this);
        workbench.setOnClose(this::closeWorkbench);
        view.setTemplatePanel(templatePanel.getView());
        view.setStrip(strip.getView());
        view.setWorkbench(workbench.getView());
        view.setMessages(messages.getView());
        // Design 18 §5.7: the empty states are the front door - each cell says how data arrives.
        view.setCrumb(paneProvider.get().as(null,
                "No run yet — the breadcrumb follows a run: pick a sample stream, or step a record through a pipeline.")
                .getView());
        view.setInput(paneProvider.get().as("Input",
                "The selected match's content, with its captures tinted, after a run.").getView());
        view.setVariables(paneProvider.get().as("Variables",
                "Every name in scope at the selected frame, innermost first, after a run.").getView());
        view.setOutput(paneProvider.get().as("Output",
                "What the selected frame wrote, attributed to the instruction that wrote it, after a run.")
                .getView());
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
    public void read(final Project project, final String sourceError, final boolean readOnly) {
        this.readOnly = readOnly;
        this.sourceError = sourceError;
        if (project != null) {
            this.project = project;
        }
        getView().setBanner(sourceError == null
                ? null
                : "The Source tab does not parse, so this tab shows the last good project read-only: " + sourceError);
        refresh();
        if (project != null) {
            validate.update();
        } else {
            messages.setMessages(sourceError, lastMessages);
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

    @Override
    public void replace(final Project next) {
        if (next == null || isReadOnly()) {
            return;
        }
        project = next;
        refresh();
        ValueChangeEvent.fire(this, next);
        validate.update();
    }

    private void refresh() {
        templatePanel.refresh();
        onSelect(templatePanel.getSelectedTemplateId());
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
