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

import stroom.alert.client.event.AlertEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.client.presenter.ShapeshifterAiLearningPresenter.ShapeshifterAiLearningView;
import stroom.shapeshifter.shared.LearningPlan;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.PlanStep;
import stroom.shapeshifter.shared.SampleRedaction;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.shapeshifter.shared.Template;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The Learning tab: what the stage learns and binds on (A29), what it may ask a model and within what
 * limits — the model and its instructions, the elements the chain question may choose from (A21),
 * candidates and budgets (A5) — and how samples are prepared (A17).
 */
public class ShapeshifterAiLearningPresenter
        extends DocPresenter<ShapeshifterAiLearningView, ShapeshifterAiDoc>
        implements ShapeshifterAiSettingsUiHandlers {

    private static final ShapeshifterAiResource RESOURCE = GWT.create(ShapeshifterAiResource.class);

    private final DocSelectionBoxPresenter modelPresenter;
    private final LearningKeyPresenter learningKeyPresenter;
    private final RestFactory restFactory;
    /**
     * The plan as read, kept so that steps the view cannot parse leave the saved ones standing.
     */
    private LearningPlan plan = LearningPlan.of(PlanExample.DIRECT);

    @Inject
    public ShapeshifterAiLearningPresenter(final EventBus eventBus,
                                              final ShapeshifterAiLearningView view,
                                              final DocSelectionBoxPresenter modelPresenter,
                                              final LearningKeyPresenter learningKeyPresenter,
                                              final RestFactory restFactory) {
        super(eventBus, view);
        this.modelPresenter = modelPresenter;
        this.learningKeyPresenter = learningKeyPresenter;
        this.restFactory = restFactory;
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
        // The built-in text of every template, so an override is edited from the words it replaces.
        restFactory
                .create(RESOURCE)
                .method(ShapeshifterAiResource::templates)
                .onSuccess(builtIns -> getView().setBuiltInTemplates(builtIns.getTemplates(), builtIns.getVersion()))
                .taskMonitorFactory(this)
                .exec();
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
        plan = doc.getPlan();
        view.setPlanSteps(plan.getSteps().stream().map(PlanStep::format).collect(Collectors.joining("\n")));
        view.setTemplateOverrides(plan.getTemplates(), plan.getBuiltInVersion());
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
                .plan(plan())
                .maxAttempts(view.getMaxAttempts())
                .attemptBudgetMs(view.getAttemptBudgetMs())
                .tokenBudget(view.getTokenBudget())
                .sampleRedaction(view.getSampleRedaction())
                .sampleSizeLimit(view.getSampleSizeLimit())
                .build();
    }


    /**
     * The plan as the tab shows it. Steps the view cannot parse are reported and the saved steps kept, so
     * a slip in one line does not lose the rest; the store checks the order and the variables on save.
     */
    private LearningPlan plan() {
        final ShapeshifterAiLearningView view = getView();
        List<PlanStep> steps = plan.getSteps();
        final String text = view.getPlanSteps();
        final List<PlanStep> parsed = new ArrayList<>();
        try {
            for (final String line : (text == null
                    ? ""
                    : text).split("\n")) {
                if (!line.trim().isEmpty()) {
                    parsed.add(PlanStep.parse(line));
                }
            }
            // An empty box — cleared to be retyped — keeps the saved steps; the store refuses a plan without
            // CHAIN and CONFIGURE on save, so nothing is lost by not shouting here on every change.
            if (!parsed.isEmpty()) {
                steps = parsed;
            }
        } catch (final IllegalArgumentException e) {
            AlertEvent.fireError(this, "The plan's steps were not saved: " + e.getMessage(), null);
        }
        return new LearningPlan(steps, view.getTemplateOverrides(), plan.getBuiltInVersion());
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

        /**
         * One step per line in {@link PlanStep#format()}'s form.
         */
        String getPlanSteps();

        void setPlanSteps(String steps);

        Map<Template, String> getTemplateOverrides();

        void setTemplateOverrides(Map<Template, String> templates, Integer savedAgainstVersion);

        void setBuiltInTemplates(Map<Template, String> templates, int version);

        int getSampleSizeLimit();

        void setSampleSizeLimit(int sampleSizeLimit);
    }
}
