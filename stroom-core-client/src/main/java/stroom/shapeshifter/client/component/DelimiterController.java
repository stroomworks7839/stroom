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

import stroom.shapeshifter.client.model.ControlEscapes;
import stroom.shapeshifter.client.model.MatchLine;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.Template;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

/// The delimiter form: the separator, its escape and the container that makes it literal — the
/// CSV case, generalised. Each field commits as it is left; a blank delimiter is refused in place
/// rather than written, since a delimiter match without one means nothing. The fields spell
/// control characters as [ControlEscapes] does, so a newline — the usual separator — has
/// a spelling a text box can hold.
public class DelimiterController
        extends Controller
        implements MatchEditorController.MatchForm {

    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    private final Delimiter view;

    private ProjectHost host;
    private String templateId;

    @Inject
    public DelimiterController(final EventBus eventBus, final Delimiter view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            applyReadOnly();
        });
        view.setOnChange(this::onChange);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    @Override
    public void setTemplate(final String id) {
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null || !(template.match() instanceof MatchExpression.Delimiter d)) {
            return;
        }
        applyReadOnly();
        view.setDelimiter(ControlEscapes.escape(d.delimiter()), ControlEscapes.escape(d.escape()),
                ControlEscapes.escape(d.containerStart()), ControlEscapes.escape(d.containerEnd()));
        view.setError(null);
    }

    public void onChange() {
        final Template template = host.template(templateId);
        if (template == null || readOnly) {
            return;
        }
        final MatchExpression match = MatchLine.delimiter(view.getDelimiter(), view.getEscape(),
                view.getContainerStart(), view.getContainerEnd());
        if (match == null) {
            view.setError(MatchLine.NO_DELIMITER);
            return;
        }
        view.setError(null);
        if (!match.equals(template.match())) {
            host.replace(host.withTemplate(Templates.withMatch(template, match)));
        }
    }

    // One place that says what read-only means here, called when the context changes and
    // when the pane is filled, so a lock change alone reaches the view.
    private void applyReadOnly() {
        view.setReadOnly(readOnly);
    }

}
