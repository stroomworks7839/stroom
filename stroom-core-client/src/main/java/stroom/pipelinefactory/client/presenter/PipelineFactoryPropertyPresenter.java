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

package stroom.pipelinefactory.client.presenter;

import stroom.pipelinefactory.shared.PipelineFactoryProperty;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

/// One value a Pipeline Factory writes onto an element of each pipeline it makes (A113), as a dialog.
public class PipelineFactoryPropertyPresenter
        extends MyPresenterWidget<PipelineFactoryPropertyPresenter.PipelineFactoryPropertyView> {

    @Inject
    public PipelineFactoryPropertyPresenter(final EventBus eventBus, final PipelineFactoryPropertyView view) {
        super(eventBus, view);
    }

    /// @param property What to show; its fields may be null.
    public void read(final PipelineFactoryProperty property) {
        getView().setElementId(property.getElementId());
        getView().setName(property.getName());
        getView().setValue(property.getValue());
    }

    /// @return What the dialog holds.
    public PipelineFactoryProperty write() {
        return new PipelineFactoryProperty(getView().getElementId().trim(),
                getView().getName().trim(),
                getView().getValue());
    }


    // --------------------------------------------------------------------------------


    /// The dialog's fields.
    public interface PipelineFactoryPropertyView extends View {

        /// @return The element id.
        String getElementId();

        /// @param elementId The element id.
        void setElementId(String elementId);

        /// @return The property name.
        String getName();

        /// @param name The property name.
        void setName(String name);

        /// @return The value pattern.
        String getValue();

        /// @param value The value pattern.
        void setValue(String value);
    }
}
