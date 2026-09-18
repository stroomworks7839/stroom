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
 * <p>Selection is one object — the selected template's id, or null for the source, the document
 * itself — owned here and pushed to the workbench; the template panel is its only author today.
 */
public class ShapeshifterDesignPresenter
        extends MyPresenterWidget<ShapeshifterDesignView>
        implements ProjectHost, HasValueChangeHandlers<Project> {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final RestFactory restFactory;
    private final TemplatePanelPresenter templatePanel;
    private final TemplateWorkbenchPresenter workbench;
    private final SourceConfigPresenter sourceConfig;
    private final MessagesPresenter messages;
    private final DelayedUpdate validate;

    private Project project;
    private String sourceError;
    private boolean readOnly = true;
    private List<ShapeshifterMessage> lastMessages;

    @Inject
    public ShapeshifterDesignPresenter(final EventBus eventBus,
                                       final ShapeshifterDesignView view,
                                       final RestFactory restFactory,
                                       final TemplatePanelPresenter templatePanel,
                                       final TemplateWorkbenchPresenter workbench,
                                       final SourceConfigPresenter sourceConfig,
                                       final MessagesPresenter messages) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.templatePanel = templatePanel;
        this.workbench = workbench;
        this.sourceConfig = sourceConfig;
        this.messages = messages;
        this.validate = new DelayedUpdate(400, this::validate);
        templatePanel.setHost(this);
        workbench.setHost(this);
        sourceConfig.setHost(this);
        view.setTemplatePanel(templatePanel.getView());
        view.setMessages(messages.getView());
        view.setCentre(sourceConfig.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(templatePanel.addSelectionHandler(event -> onSelect(templatePanel.getSelectedTemplateId())));
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
        getView().setBanner(sourceError);
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
        if (templateId == null || template(templateId) == null) {
            sourceConfig.refresh();
            getView().setCentre(sourceConfig.getView());
        } else {
            workbench.setTemplate(templateId);
            getView().setCentre(workbench.getView());
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

        void setCentre(View view);

        void setMessages(View view);

        /** A line above everything, or nothing: the Source tab's syntax error while it has one. */
        void setBanner(String text);
    }
}
