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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.client.presenter.BuiltInTextPresenter.BuiltInTextView;
import stroom.shapeshifter.shared.Template;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;
import java.util.Map;

/// The popup that loads a built-in text into one of a step's texts (design 05 §3): the built-ins whose
/// variables the slot allows, chosen from a list with the text shown, so that a person sees what they
/// are about to load before it replaces the document's own words.
public class BuiltInTextPresenter extends MyPresenterWidget<BuiltInTextView> {

    private Map<Template, String> builtIns = Map.of();

    @Inject
    public BuiltInTextPresenter(final EventBus eventBus, final BuiltInTextView view) {
        super(eventBus, view);
        view.setOnChoice(this::preview);
    }

    /// @param slot     The text being loaded into, offered first.
    /// @param suitable The built-ins whose variables the slot allows, the slot's own among them.
    /// @param builtIns The built-in texts the node serves.
    public void read(final Template slot, final List<Template> suitable, final Map<Template, String> builtIns) {
        this.builtIns = builtIns;
        getView().setChoices(suitable);
        getView().setChoice(slot);
        preview(slot);
    }

    /// The built-in text chosen, or null where none was.
    public String write() {
        final Template chosen = getView().getChoice();
        return chosen == null
                ? null
                : builtIns.getOrDefault(chosen, "");
    }

    private void preview(final Template chosen) {
        getView().setPreview(chosen == null
                ? ""
                : builtIns.getOrDefault(chosen, ""));
    }


    // --------------------------------------------------------------------------------


    public interface BuiltInTextView extends View {

        void setChoices(List<Template> templates);

        Template getChoice();

        void setChoice(Template template);

        void setPreview(String text);

        void setOnChoice(java.util.function.Consumer<Template> onChoice);
    }
}
