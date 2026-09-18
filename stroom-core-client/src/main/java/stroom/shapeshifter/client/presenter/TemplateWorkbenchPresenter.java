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

import stroom.shapeshifter.client.presenter.TemplateWorkbenchPresenter.TemplateWorkbenchView;
import stroom.shapeshifter.config.Template;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

/**
 * The selected template (design 43 §4): its match in the workbench's tabs, then its declarations
 * and captures side by side. Guard, limits and the body are later phases and are edited in the
 * Source tab until then.
 */
public class TemplateWorkbenchPresenter extends MyPresenterWidget<TemplateWorkbenchView> {

    private final MatchEditorPresenter matchEditor;
    private final DeclarationsPresenter declarations;
    private final CapturesPresenter captures;

    private ProjectHost host;
    private String templateId;

    @Inject
    public TemplateWorkbenchPresenter(final EventBus eventBus,
                                      final TemplateWorkbenchView view,
                                      final MatchEditorPresenter matchEditor,
                                      final DeclarationsPresenter declarations,
                                      final CapturesPresenter captures) {
        super(eventBus, view);
        this.matchEditor = matchEditor;
        this.declarations = declarations;
        this.captures = captures;
        view.setMatchEditor(matchEditor.getView());
        view.setDeclarations(declarations.getView());
        view.setCaptures(captures.getView());
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        matchEditor.setHost(host);
        declarations.setHost(host);
        captures.setHost(host);
    }

    /** Show a template; called again with the same id after every edit, so it is also refresh. */
    public void setTemplate(final String id) {
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null) {
            getView().setHeader("", "");
            return;
        }
        getView().setHeader(template.name(), (template.mode() == null
                ? "root"
                : "mode " + template.mode()) + (template.consume()
                ? " · consumes"
                : ""));
        matchEditor.setTemplate(id);
        declarations.setTemplate(id);
        captures.setTemplate(id);
    }

    public String getTemplateId() {
        return templateId;
    }

    public interface TemplateWorkbenchView extends View {

        void setHeader(String name, String detail);

        void setMatchEditor(View view);

        void setDeclarations(View view);

        void setCaptures(View view);
    }
}
