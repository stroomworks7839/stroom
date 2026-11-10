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

import stroom.alert.client.event.AlertEvent;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.model.Modes;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.ParamDecl;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;
import stroom.widget.popup.client.event.HidePopupRequestEvent;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;

/**
 * A template's identity — name, colour, mode, params, encoding — the same fields whether
 * creating or editing (design 18 §5.6: mode has exactly one home). What the match is <i>for</i>
 * is not identity and is not here: the strip owns it (design 44 §5f), and it is carried through
 * untouched by an edit. Colour is presentation and
 * lives beside the project in the document, not in it. A blank mode is the root: the templates
 * the document itself dispatches to. The encodings offered are the engine's, fetched once.
 */
public class TemplateEditController extends Controller {

    private final TemplateEdit view;

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final Provider<NameEditorController> namePromptProvider;

    private ProjectHost host;
    private Template template;

    @Inject
    public TemplateEditController(final EventBus eventBus,
                                 final TemplateEdit view,
                                 final RestFactory restFactory,
                                 final Provider<NameEditorController> namePromptProvider) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        view.setOnNewMode(this::onNewMode);
        this.namePromptProvider = namePromptProvider;
        restFactory
                .create(RESOURCE)
                .method(ShapeshifterResource::encodings)
                .onSuccess(view::setEncodings)
                .owner(this)
                .exec();
    }

    /** Show a template's fields; {@code override} is the author's colour, or null for the palette's. */
    public void read(final ProjectHost host, final Template template, final String override) {
        this.host = host;
        this.template = template;
        view.setColour(override, autoColour(template));
        view.setModes(host.modes());
        view.setName(template.name());
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

    /** The edited template, or null after telling the user what is missing. */
    public Template write() {
        final String name = view.getName().trim();
        if (name.isEmpty()) {
            AlertEvent.fireWarn(this, "A template needs a name", null);
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
                AlertEvent.fireWarn(this, "A param is 'name' or 'name = default': " + line, null);
                return null;
            }
            params.add(new ParamDecl(paramName, eq < 0
                    ? null
                    : line.substring(eq + 1).trim()));
        }
        final String encoding = view.getEncoding().trim();
        return Templates.withIdentity(template, name, mode.isEmpty()
                ? null
                : mode, template.consume(), params, encoding.isEmpty()
                ? null
                : encoding, view.isIgnoreErrors());
    }

    /** The colour chosen for the template, or null for the palette's. */
    public String getColour() {
        return view.getColour();
    }

    /** What auto means for this template: the palette at its position, or the next position for a new one. */
    private String autoColour(final Template template) {
        final List<Template> templates = host.getProject().templates();
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).id().equals(template.id())) {
                return Templates.colour(i);
            }
        }
        return Templates.colour(templates.size());
    }

    public void onNewMode() {
        namePromptProvider.get().prompt(this,
                "New Mode", "mode", Modes.HELP, "", host.modes(), name -> {
                    host.declareMode(name);
                    view.setModes(host.modes());
                    view.setMode(name);
                });
    }

    /// @param opener  What shows it, which owns it until it is hidden.
    /// @param caption The dialog's caption.
    /// @param handler Told when OK or Cancel is pressed.
    public void show(final HasControllerNode opener,
                     final String caption,
                     final HidePopupRequestEvent.Handler handler) {
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(600, 600))
                .caption(caption)
                .onShow(e -> view.focus())
                .onHideRequest(handler)
                .fire();
    }

}
