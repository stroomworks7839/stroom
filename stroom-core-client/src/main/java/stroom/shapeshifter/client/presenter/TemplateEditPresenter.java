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
import stroom.shapeshifter.client.presenter.TemplateEditPresenter.TemplateEditView;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.ParamDecl;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A template's identity — name, colour, mode, whether it consumes — the same fields whether
 * creating or editing (design 18 §5.6: mode has exactly one home). Colour is presentation and
 * lives beside the project in the document, not in it. A blank mode is the root: the templates
 * the document itself dispatches to.
 */
public class TemplateEditPresenter extends MyPresenterWidget<TemplateEditView> {

    private Template template;

    @Inject
    public TemplateEditPresenter(final EventBus eventBus, final TemplateEditView view) {
        super(eventBus, view);
    }

    public void read(final Project project, final Template template, final String colour) {
        this.template = template;
        getView().setColour(colour);
        getView().setModes(new LinkedHashSet<>(Modes.of(project)));
        getView().setName(template.name());
        getView().setMode(template.mode() == null
                ? ""
                : template.mode());
        getView().setConsume(template.consume());
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
                : mode, getView().isConsume(), params, encoding.isEmpty()
                ? null
                : encoding, getView().isIgnoreErrors());
    }

    /** The colour chosen for the template, or null for the palette's. */
    public String getColour() {
        return getView().getColour();
    }

    public void show(final String caption, final HidePopupRequestEvent.Handler handler) {
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(460, 520))
                .caption(caption)
                .onShow(e -> getView().focus())
                .onHideRequest(handler)
                .fire();
    }

    public interface TemplateEditView extends View, Focus {

        String getName();

        void setName(String name);

        String getMode();

        void setMode(String mode);

        /** The modes the project already has, offered as suggestions beside the free text. */
        void setModes(Set<String> modes);

        boolean isConsume();

        void setConsume(boolean consume);

        String getColour();

        void setColour(String colour);

        /** Params as lines: {@code name} or {@code name = default}. */
        String getParams();

        void setParams(String params);

        String getEncoding();

        void setEncoding(String encoding);

        boolean isIgnoreErrors();

        void setIgnoreErrors(boolean ignoreErrors);
    }
}
