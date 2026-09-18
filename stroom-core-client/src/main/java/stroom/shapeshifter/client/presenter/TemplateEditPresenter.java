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
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A template's identity — name, mode, whether it consumes — the same fields whether creating or
 * editing (design 18 §5.6: mode has exactly one home). A blank mode is the root: the templates
 * the document itself dispatches to.
 */
public class TemplateEditPresenter extends MyPresenterWidget<TemplateEditView> {

    private Template template;

    @Inject
    public TemplateEditPresenter(final EventBus eventBus, final TemplateEditView view) {
        super(eventBus, view);
    }

    public void read(final Project project, final Template template) {
        this.template = template;
        final Set<String> modes = new LinkedHashSet<>();
        for (final Template t : project.templates()) {
            if (t.mode() != null && !t.mode().isEmpty()) {
                modes.add(t.mode());
            }
        }
        getView().setModes(modes);
        getView().setName(template.name());
        getView().setMode(template.mode() == null
                ? ""
                : template.mode());
        getView().setConsume(template.consume());
    }

    /** The edited template, or null after telling the user what is missing. */
    public Template write() {
        final String name = getView().getName().trim();
        if (name.isEmpty()) {
            AlertEvent.fireWarn(this, "A template needs a name", null);
            return null;
        }
        final String mode = getView().getMode().trim();
        return Templates.withIdentity(template, name, mode.isEmpty()
                ? null
                : mode, getView().isConsume());
    }

    public void show(final String caption, final HidePopupRequestEvent.Handler handler) {
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(420, 260))
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
    }
}
