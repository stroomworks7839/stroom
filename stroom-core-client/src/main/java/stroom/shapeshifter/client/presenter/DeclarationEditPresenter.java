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
import stroom.shapeshifter.client.presenter.DeclarationEditPresenter.DeclarationEditView;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.Declaration.Entry;
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
import java.util.List;

/**
 * One declaration: name, type, and for a map its fixed entries, one {@code from = to} per line.
 */
public class DeclarationEditPresenter extends MyPresenterWidget<DeclarationEditView> {

    @Inject
    public DeclarationEditPresenter(final EventBus eventBus, final DeclarationEditView view) {
        super(eventBus, view);
    }

    public void read(final Declaration declaration) {
        getView().setName(declaration.name());
        getView().setType(declaration.type());
        final StringBuilder sb = new StringBuilder();
        for (final Entry entry : declaration.entries()) {
            sb.append(entry.from()).append(" = ").append(entry.to()).append('\n');
        }
        getView().setEntries(sb.toString());
    }

    /** The edited declaration, or null after telling the user what is wrong. */
    public Declaration write() {
        try {
            final List<Entry> entries = new ArrayList<>();
            if (getView().getType() == Declaration.Type.MAP) {
                for (final String line : getView().getEntries().split("\n")) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    final int eq = line.indexOf('=');
                    if (eq < 0) {
                        throw new ConfigException("A map entry is 'from = to': " + line);
                    }
                    entries.add(new Entry(line.substring(0, eq).trim(), line.substring(eq + 1).trim()));
                }
            }
            return new Declaration(getView().getName().trim(), getView().getType(), entries);
        } catch (final ConfigException e) {
            AlertEvent.fireWarn(this, e.getMessage(), null);
            return null;
        }
    }

    public void show(final String caption, final HidePopupRequestEvent.Handler handler) {
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(460, 360))
                .caption(caption)
                .onShow(e -> getView().focus())
                .onHideRequest(handler)
                .fire();
    }

    public interface DeclarationEditView extends View, Focus {

        String getName();

        void setName(String name);

        Declaration.Type getType();

        void setType(Declaration.Type type);

        String getEntries();

        void setEntries(String entries);
    }
}
