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
import stroom.shapeshifter.client.presenter.TemplateEditPresenter.TemplateEditView;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.ParamDecl;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

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
public class TemplateEditPresenter
        extends MyPresenterWidget<TemplateEditView>
        implements TemplateEditUiHandlers {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final NamePresenter namePrompt;

    private ProjectHost host;
    private Template template;

    @Inject
    public TemplateEditPresenter(final EventBus eventBus,
                                 final TemplateEditView view,
                                 final RestFactory restFactory,
                                 final NamePresenter namePrompt) {
        super(eventBus, view);
        this.namePrompt = namePrompt;
        view.setUiHandlers(this);
        restFactory
                .create(RESOURCE)
                .method(ShapeshifterResource::encodings)
                .onSuccess(view::setEncodings)
                .taskMonitorFactory(this)
                .exec();
    }

    /** Show a template's fields; {@code override} is the author's colour, or null for the palette's. */
    public void read(final ProjectHost host, final Template template, final String override) {
        this.host = host;
        this.template = template;
        getView().setColour(override, autoColour(template));
        getView().setModes(host.modes());
        getView().setName(template.name());
        getView().setMode(template.mode() == null
                ? ""
                : template.mode());
        getView().setEncoding(template.encoding() == null
                ? ""
                : template.encoding());
        getView().setIgnoreErrors(template.ignoreErrors());
        final StringBuilder params = new StringBuilder();
        for (final ParamDecl param : template.param()) {
            params.append(param.name());
            if (param.defaultValue() != null) {
                params.append(" = ").append(param.defaultValue());
            }
            params.append('\n');
        }
        getView().setParams(params.toString());
    }

    /** The edited template, or null after telling the user what is missing. */
    public Template write() {
        final String name = getView().getName().trim();
        if (name.isEmpty()) {
            AlertEvent.fireWarn(this, "A template needs a name", null);
            return null;
        }
        final String mode = getView().getMode().trim();
        final List<ParamDecl> params = new ArrayList<>();
        for (final String line : getView().getParams().split("\n")) {
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
        final String encoding = getView().getEncoding().trim();
        return Templates.withIdentity(template, name, mode.isEmpty()
                ? null
                : mode, template.consume(), params, encoding.isEmpty()
                ? null
                : encoding, getView().isIgnoreErrors());
    }

    /** The colour chosen for the template, or null for the palette's. */
    public String getColour() {
        return getView().getColour();
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

    @Override
    public void onNewMode() {
        namePrompt.show("New Mode", "mode", Modes.HELP, "", host.modes(), name -> {
            host.declareMode(name);
            getView().setModes(host.modes());
            getView().setMode(name);
        });
    }

    public void show(final String caption, final HidePopupRequestEvent.Handler handler) {
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(600, 600))
                .caption(caption)
                .onShow(e -> getView().focus())
                .onHideRequest(handler)
                .fire();
    }

    public interface TemplateEditView extends View, Focus, HasUiHandlers<TemplateEditUiHandlers> {

        String getName();

        void setName(String name);

        String getMode();

        void setMode(String mode);

        /** The modes to pick from: the project's and the declared, in the host's order. */
        void setModes(List<String> modes);

        String getColour();

        /** The author's colour or null for auto, and the colour auto resolves to. */
        void setColour(String override, String auto);

        /** Params as lines: {@code name} or {@code name = default}. */
        String getParams();

        void setParams(String params);

        String getEncoding();

        void setEncoding(String encoding);

        /** The engine's encodings by label; blank inherits and is always offered. */
        void setEncodings(List<String> encodings);

        boolean isIgnoreErrors();

        void setIgnoreErrors(boolean ignoreErrors);
    }
}
