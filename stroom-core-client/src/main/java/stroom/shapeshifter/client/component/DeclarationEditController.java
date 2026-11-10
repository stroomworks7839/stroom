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
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.Declaration.Entry;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;
import stroom.widget.popup.client.event.HidePopupRequestEvent;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;

/**
 * One declaration: name, type, and for a map its fixed entries, one {@code from = to} per line.
 */
public class DeclarationEditController extends Controller {

    private final DeclarationEdit view;

    @Inject
    public DeclarationEditController(final EventBus eventBus, final DeclarationEdit view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
    }

    public void read(final Declaration declaration) {
        view.setName(declaration.name());
        view.setType(declaration.type());
        final StringBuilder sb = new StringBuilder();
        for (final Entry entry : declaration.entries()) {
            sb.append(entry.from()).append(" = ").append(entry.to()).append('\n');
        }
        view.setEntries(sb.toString());
    }

    /** The edited declaration, or null after telling the user what is wrong. */
    public Declaration write() {
        try {
            final List<Entry> entries = new ArrayList<>();
            if (view.getType() == Declaration.Type.MAP) {
                for (final String line : view.getEntries().split("\n")) {
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
            return new Declaration(view.getName().trim(), view.getType(), entries);
        } catch (final ConfigException e) {
            AlertEvent.fireWarn(this, e.getMessage(), null);
            return null;
        }
    }

    /// @param opener  What shows it, which owns it until it is hidden.
    /// @param caption The dialog's caption.
    /// @param handler Told when OK or Cancel is pressed.
    public void show(final HasControllerNode opener,
                     final String caption,
                     final HidePopupRequestEvent.Handler handler) {
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(460, 360))
                .caption(caption)
                .onShow(e -> view.focus())
                .onHideRequest(handler)
                .fire();
    }

}
