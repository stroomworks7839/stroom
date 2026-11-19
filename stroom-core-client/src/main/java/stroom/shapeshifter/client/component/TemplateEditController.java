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

import stroom.alert.client.event.AlertEvent;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.ParamDecl;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;

/// A template's identity other than its name — colour, mode, params, encoding — shown under
/// its title while the title is being edited (the owner, 2026-11-18); the name is the title's
/// own field, and is given to [#write]. What the match is *for* is not identity and is not
/// here: the strip owns it (design 44 §5f), and it is carried through untouched by an edit.
/// Colour is presentation and lives beside the project in the document, not in it. A blank
/// mode is the root: the templates the document itself dispatches to. The encodings offered
/// are the engine's, fetched once.
public class TemplateEditController extends Controller {

    private final TemplateEdit view;

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private ProjectHost host;

    @Inject
    public TemplateEditController(final EventBus eventBus,
                                 final TemplateEdit view,
                                 final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        restFactory
                .create(RESOURCE)
                .method(ShapeshifterResource::encodings)
                .onSuccess(view::setEncodings)
                .owner(this)
                .exec();
    }

    /// Show a template's fields; `override` is the author's colour, or null for the palette's.
    public void read(final ProjectHost host, final Template template, final String override) {
        this.host = host;
        view.setColour(override, autoColour(template));
        view.setModes(host.modes());
        view.setMode(template.mode() == null
                ? ""
                : template.mode());
        view.setEncoding(template.encoding() == null
                ? ""
                : template.encoding());
        view.setIgnoreErrors(template.ignoreErrors());
        final StringBuilder params = new StringBuilder();
        for (final ParamDecl param : template.param()) {
            params.append(param.name());
            if (param.defaultValue() != null) {
                params.append(" = ").append(param.defaultValue());
            }
            params.append('\n');
        }
        view.setParams(params.toString());
    }

    /// The template with the name given and the settings as they stand here.
    ///
    /// @param current The template as the project has it now. What is written is that template
    ///                with its identity changed: anything else done to it since these settings
    ///                were opened — a card added to its body — is kept.
    /// @param typed The name, as the title's field holds it.
    /// @param quiet Whether to say nothing when it cannot be written: for settings being left
    ///              behind rather than taken, where there is no one to tell.
    /// @return The edited template, or null when it cannot be written — it has no name, a name
    ///         another template has, or a param that is not one — after telling the user why,
    ///         unless quiet.
    public Template write(final Template current, final String typed, final boolean quiet) {
        final String name = typed.trim();
        if (name.isEmpty()) {
            warn("A template needs a name", quiet);
            return null;
        }
        if (Templates.nameTaken(host.getProject(), name, current.id())) {
            warn("There is already a template named '" + name + "'", quiet);
            return null;
        }
        final String mode = view.getMode().trim();
        final List<ParamDecl> params = new ArrayList<>();
        for (final String line : view.getParams().split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            final int eq = line.indexOf('=');
            final String paramName = (eq < 0
                    ? line
                    : line.substring(0, eq)).trim();
            if (paramName.isEmpty()) {
                warn("A param is 'name' or 'name = default': " + line, quiet);
                return null;
            }
            params.add(new ParamDecl(paramName, eq < 0
                    ? null
                    : line.substring(eq + 1).trim()));
        }
        final String encoding = view.getEncoding().trim();
        return Templates.withIdentity(current, name, mode.isEmpty()
                ? null
                : mode, current.consume(), params, encoding.isEmpty()
                ? null
                : encoding, view.isIgnoreErrors());
    }

    private void warn(final String message, final boolean quiet) {
        if (!quiet) {
            AlertEvent.fireWarn(this, message, null);
        }
    }

    /// @param onCommit Told when the settings are to be taken.
    public void setOnCommit(final Runnable onCommit) {
        view.setOnCommit(onCommit);
    }

    /// @param onCancel Told when the settings are to be dropped.
    public void setOnCancel(final Runnable onCancel) {
        view.setOnCancel(onCancel);
    }

    /// The colour chosen for the template, or null for the palette's.
    public String getColour() {
        return view.getColour();
    }

    /// What auto means for this template: the palette at its position, or the next position for a new one.
    private String autoColour(final Template template) {
        final List<Template> templates = host.getProject().templates();
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).id().equals(template.id())) {
                return Templates.colour(i);
            }
        }
        return Templates.colour(templates.size());
    }

}
