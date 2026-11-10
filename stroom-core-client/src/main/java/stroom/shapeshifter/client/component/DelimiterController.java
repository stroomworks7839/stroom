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

import stroom.shapeshifter.client.model.ControlEscapes;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.Template;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

/**
 * The delimiter form: the separator, its escape and the container that makes it literal — the
 * CSV case, generalised. Each field commits as it is left; a blank delimiter is refused in place
 * rather than written, since a delimiter match without one means nothing. The fields spell
 * control characters as {@link ControlEscapes} does, so a newline — the usual separator — has
 * a spelling a text box can hold.
 */
public class DelimiterController
        extends Controller
        implements MatchEditorController.MatchForm {

    private final Delimiter view;

    /** What a template gets when its match becomes a delimiter: one field per line. */
    static final MatchExpression.Delimiter DEFAULT = new MatchExpression.Delimiter("\n", null, null, null);

    private ProjectHost host;
    private String templateId;

    @Inject
    public DelimiterController(final EventBus eventBus, final Delimiter view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
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
        view.setEnabled(!host.isReadOnly());
        view.setDelimiter(ControlEscapes.escape(d.delimiter()), ControlEscapes.escape(d.escape()),
                ControlEscapes.escape(d.containerStart()), ControlEscapes.escape(d.containerEnd()));
        view.setError(null);
    }

    public void onChange() {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        final String delimiter = ControlEscapes.unescape(view.getDelimiter());
        if (delimiter.isEmpty()) {
            view.setError("A delimiter match needs its delimiter");
            return;
        }
        view.setError(null);
        final MatchExpression match = new MatchExpression.Delimiter(delimiter,
                blankToNull(ControlEscapes.unescape(view.getEscape())),
                blankToNull(ControlEscapes.unescape(view.getContainerStart())),
                blankToNull(ControlEscapes.unescape(view.getContainerEnd())));
        if (!match.equals(template.match())) {
            host.replace(host.withTemplate(Templates.withMatch(template, match)));
        }
    }

    private static String blankToNull(final String text) {
        return text.isEmpty()
                ? null
                : text;
    }

}
