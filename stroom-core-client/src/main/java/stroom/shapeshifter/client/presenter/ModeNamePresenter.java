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
import stroom.shapeshifter.client.presenter.ModeNamePresenter.ModeNameView;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * Ask for a mode's name (design 18 §5.6): a new one, or a new name for one. A mode is created
 * the moment it is needed - from the template dialog's mode field or the mode editor - and
 * declared with the host until a template takes it, so it is offered everywhere a mode is picked.
 */
public class ModeNamePresenter extends MyPresenterWidget<ModeNameView> {

    @Inject
    public ModeNamePresenter(final EventBus eventBus, final ModeNameView view) {
        super(eventBus, view);
    }

    /**
     * Ask, refusing a blank or a name in {@code existing} other than {@code initial} itself;
     * {@code onNamed} gets the trimmed name when it is new.
     */
    public void show(final String caption,
                     final String initial,
                     final Collection<String> existing,
                     final Consumer<String> onNamed) {
        getView().setName(initial);
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(350, 190, 350, 190))
                .caption(caption)
                .onShow(e -> getView().focus())
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final String name = getView().getName().trim();
                    if (name.isEmpty()) {
                        AlertEvent.fireWarn(this, "A mode needs a name; the root is not a mode", e::reset);
                    } else if (name.equals(initial)) {
                        e.hide();
                    } else if (existing.contains(name)) {
                        AlertEvent.fireWarn(this, "There is already a mode named '" + name + "'", e::reset);
                    } else {
                        onNamed.accept(name);
                        e.hide();
                    }
                })
                .fire();
    }

    public interface ModeNameView extends View {

        String getName();

        void setName(String name);

        void focus();
    }
}
