/*
 * Copyright 2016-2026 Crown Copyright
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

import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.shapeshifter.client.presenter.ShapeshifterAiSettingsPresenter.ShapeshifterAiSettingsView;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

/**
 * The Settings tab: how the stage runs — where learning executes (A5), whether the model may be called
 * at all (§11) and when a feed's failures open the breaker (A24). What it learns with, how it scores,
 * when it promotes and where it routes each have a tab of their own. The replay unit is not here: it is
 * a property of the fragment a rule binds (A1 as revised).
 */
public class ShapeshifterAiSettingsPresenter
        extends DocPresenter<ShapeshifterAiSettingsView, ShapeshifterAiDoc>
        implements ShapeshifterAiSettingsUiHandlers {

    @Inject
    public ShapeshifterAiSettingsPresenter(final EventBus eventBus,
                                              final ShapeshifterAiSettingsView view) {
        super(eventBus, view);
        view.setUiHandlers(this);
    }

    @Override
    protected void onRead(final DocRef docRef, final ShapeshifterAiDoc doc, final boolean readOnly) {
        getView().setExecutionMode(doc.getExecutionMode());
        getView().setLearningMode(doc.getLearningMode());
        getView().setErrorModeAfter(doc.getErrorModeAfter());
    }

    @Override
    protected ShapeshifterAiDoc onWrite(final ShapeshifterAiDoc doc) {
        return doc
                .copy()
                .executionMode(getView().getExecutionMode())
                .learningMode(getView().getLearningMode())
                .errorModeAfter(getView().getErrorModeAfter())
                .build();
    }


    // --------------------------------------------------------------------------------


    public interface ShapeshifterAiSettingsView
            extends View, ReadOnlyChangeHandler, HasUiHandlers<ShapeshifterAiSettingsUiHandlers> {

        ExecutionMode getExecutionMode();

        void setExecutionMode(ExecutionMode executionMode);

        LearningMode getLearningMode();

        void setLearningMode(LearningMode learningMode);

        int getErrorModeAfter();

        void setErrorModeAfter(int errorModeAfter);
    }
}
