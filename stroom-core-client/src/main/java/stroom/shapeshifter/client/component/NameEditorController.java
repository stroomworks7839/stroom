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
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.Collection;
import java.util.function.Consumer;

/// Asks for a name ([NameEditor]): a mode's or a pattern part's, new or renamed. A mode is
/// created the moment it is needed (design 18 §5.6) — from the template dialog's mode field or
/// the mode editor — and a part from the nav panel or by extracting a node (design 44 §3);
/// either way the question is one word, refused blank or taken.
public class NameEditorController extends Controller {

    private final NameEditor view;

    /// Made by GIN, with its content.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The content.
    @Inject
    public NameEditorController(final EventBus eventBus, final NameEditor view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
    }

    /// Asks, refusing a blank or a name in `existing` other than `initial` itself; `onNamed`
    /// gets the trimmed name when it is new. The dialog stays open while the name is refused.
    ///
    /// @param opener   What shows it, and owns it until it is hidden.
    /// @param caption  The dialog's caption.
    /// @param what     The kind of thing named, for the messages: "mode" or "pattern part".
    /// @param help     What a name of that kind means, shown beneath the field.
    /// @param initial  The name to start from, which is never refused as taken.
    /// @param existing The names already used, which this one may not take.
    /// @param onNamed  Given the new name when one is entered and accepted.
    public void prompt(final HasControllerNode opener,
                       final String caption,
                       final String what,
                       final String help,
                       final String initial,
                       final Collection<String> existing,
                       final Consumer<String> onNamed) {
        view.setName(initial);
        view.setHelp(help);
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(350, 190, 350, 190))
                .caption(caption)
                .onShow(e -> view.focus())
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final String name = view.getName().trim();
                    if (name.isEmpty()) {
                        AlertEvent.fireWarn(this, "A " + what + " needs a name", e::reset);
                    } else if (name.equals(initial)) {
                        e.hide();
                    } else if (existing.contains(name)) {
                        AlertEvent.fireWarn(this,
                                "There is already a " + what + " named '" + name + "'", e::reset);
                    } else {
                        onNamed.accept(name);
                        e.hide();
                    }
                })
                .fire();
    }
}
