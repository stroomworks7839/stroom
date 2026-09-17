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
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.client.presenter.ShapeshifterAiLearningPresenter.ShapeshifterAiLearningView;
import stroom.shapeshifter.shared.SampleRedaction;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.List;

/**
 * The Learning tab: what the stage learns and binds on (A29), what it may ask a model and within what
 * limits — the model and its instructions, the elements the chain question may choose from (A21),
 * candidates and budgets (A5) — and how samples are prepared (A17).
 */
public class ShapeshifterAiLearningPresenter
        extends DocPresenter<ShapeshifterAiLearningView, ShapeshifterAiDoc>
        implements ShapeshifterAiSettingsUiHandlers {

    private final DocSelectionBoxPresenter modelPresenter;
    private final LearningKeyPresenter learningKeyPresenter;

    @Inject
    public ShapeshifterAiLearningPresenter(final EventBus eventBus,
                                              final ShapeshifterAiLearningView view,
                                              final DocSelectionBoxPresenter modelPresenter,
                                              final LearningKeyPresenter learningKeyPresenter) {
        super(eventBus, view);
        this.modelPresenter = modelPresenter;
        this.learningKeyPresenter = learningKeyPresenter;
        view.setUiHandlers(this);

        modelPresenter.setIncludedTypes(OpenAIModelDoc.TYPE);
        modelPresenter.setRequiredPermissions(DocumentPermission.USE);
        view.setModelView(modelPresenter.getView());
        learningKeyPresenter.setUiHandlers(this);
        view.setLearningKeyView(learningKeyPresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(modelPresenter.addDataSelectionHandler(e -> onChange()));
    }

    @Override
    protected void onRead(final DocRef docRef, final ShapeshifterAiDoc doc, final boolean readOnly) {
        final ShapeshifterAiLearningView view = getView();
        modelPresenter.setSelectedEntityReference(doc.getModel(), true);
        modelPresenter.setEnabled(!readOnly);
        learningKeyPresenter.read(doc.getLearningKey());
        learningKeyPresenter.setReadOnly(readOnly);
        view.setRelearnThreshold(doc.getRelearnThreshold());
        view.setAllowedElements(doc.getAllowedElements());
        view.setInstructions(doc.getInstructions());
        view.setMaxAttempts(doc.getMaxAttempts());
        view.setAttemptBudgetMs(doc.getAttemptBudgetMs());
        view.setTokenBudget(doc.getTokenBudget());
        view.setSampleRedaction(doc.getSampleRedaction());
        view.setSampleSizeLimit(doc.getSampleSizeLimit());
    }

    @Override
    protected ShapeshifterAiDoc onWrite(final ShapeshifterAiDoc doc) {
        final ShapeshifterAiLearningView view = getView();
        return doc
                .copy()
                .model(modelPresenter.getSelectedEntityReference())
                .learningKey(learningKeyPresenter.write())
                .relearnThreshold(view.getRelearnThreshold())
                .allowedElements(view.getAllowedElements())
                .instructions(view.getInstructions())
                .maxAttempts(view.getMaxAttempts())
                .attemptBudgetMs(view.getAttemptBudgetMs())
                .tokenBudget(view.getTokenBudget())
                .sampleRedaction(view.getSampleRedaction())
                .sampleSizeLimit(view.getSampleSizeLimit())
                .build();
    }


    // --------------------------------------------------------------------------------


    public interface ShapeshifterAiLearningView
            extends View, ReadOnlyChangeHandler, HasUiHandlers<ShapeshifterAiSettingsUiHandlers> {

        void setModelView(View view);

        void setLearningKeyView(View view);

        /**
         * A fraction in [0, 1]; the widget shows it as a whole percentage.
         */
        double getRelearnThreshold();

        void setRelearnThreshold(double relearnThreshold);

        /**
         * One element type name per line in the widget; never null, blank lines dropped.
         */
        List<String> getAllowedElements();

        void setAllowedElements(List<String> allowedElements);

        String getInstructions();

        void setInstructions(String instructions);

        int getMaxAttempts();

        void setMaxAttempts(int maxAttempts);

        long getAttemptBudgetMs();

        void setAttemptBudgetMs(long attemptBudgetMs);

        /**
         * Null when unlimited; the widget shows unlimited as zero.
         */
        Long getTokenBudget();

        void setTokenBudget(Long tokenBudget);

        SampleRedaction getSampleRedaction();

        void setSampleRedaction(SampleRedaction sampleRedaction);

        int getSampleSizeLimit();

        void setSampleSizeLimit(int sampleSizeLimit);
    }
}
