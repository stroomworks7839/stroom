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

package stroom.pipeline.stepping.client.presenter;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Provider;
import javax.inject.Singleton;

/**
 * Which pipeline elements show a pane of their own in place of the code pane, and what shows it (A30).
 * <p>
 * The stepper has no knowledge of any particular element's decisions: a feature registers its element
 * types and a presenter for them as its plugin loads, and the stepper asks here for whatever element the
 * person selected. An element nothing is registered for keeps the panes it has always had.
 * <p>
 * Keyed by element type rather than by the details' type because the stepper's layout is built once,
 * when the element is loaded, and the details do not arrive until a step is taken. What the pane is for
 * has to be known before there is anything to put in it.
 */
@Singleton
public class ElementStepDetailsPresenterRegistry {

    private final Map<String, Provider<ElementStepDetailsPresenter>> presenters = new HashMap<>();

    public void register(final String elementType, final Provider<ElementStepDetailsPresenter> presenter) {
        presenters.put(elementType, presenter);
    }

    /**
     * A new presenter for this element type, or null where nothing is registered for it.
     */
    public ElementStepDetailsPresenter get(final String elementType) {
        final Provider<ElementStepDetailsPresenter> presenter = presenters.get(elementType);
        return presenter == null
                ? null
                : presenter.get();
    }
}
