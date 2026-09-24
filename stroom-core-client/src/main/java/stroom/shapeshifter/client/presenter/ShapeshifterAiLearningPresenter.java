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
    private final PlanStepListPresenter planStepListPresenter;
    /**
     * The plan as read, for what the list does not hold: the template overrides and the built-in
     * version they were saved against.
     */
    private LearningPlan plan = LearningPlan.of(PlanExample.DIRECT);

    @Inject
    public ShapeshifterAiLearningPresenter(final EventBus eventBus,
                                              final ShapeshifterAiLearningView view,
                                              final DocSelectionBoxPresenter modelPresenter,
                                              final LearningKeyPresenter learningKeyPresenter,
                                              final PlanStepListPresenter planStepListPresenter,
                                              final RestFactory restFactory) {
        super(eventBus, view);
        this.modelPresenter = modelPresenter;
        this.learningKeyPresenter = learningKeyPresenter;
        this.planStepListPresenter = planStepListPresenter;
        this.restFactory = restFactory;
        view.setUiHandlers(this);

        modelPresenter.setIncludedTypes(OpenAIModelDoc.TYPE);
        modelPresenter.setRequiredPermissions(DocumentPermission.USE);
        view.setModelView(modelPresenter.getView());
        learningKeyPresenter.setUiHandlers(this);
        view.setLearningKeyView(learningKeyPresenter.getView());
        view.setPlanStepsView(planStepListPresenter.getView());
        // The list is the plan now, so what it does is what makes the document dirty.
        planStepListPresenter.setOnChange(() -> {
            planChanged();
            onChange();
        });
    }

    /**
     * An example plan was chosen: its steps are what the list starts from (A34). A starting point and
     * not a setting — what is saved is the steps, which the person then edits.
     */
    @Override
    public void onPlanExample(final List<PlanStep> steps) {
        planStepListPresenter.read(steps, false);
        planChanged();
        onChange();
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
        planStepListPresenter.read(plan.getSteps(), readOnly);
        planChanged();
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
     * The plan changed: the text beneath the list is what the list now says, what is wrong with it is
     * shown beside it, and the document is dirty.
     */
    private void planChanged() {
        showPlanText();
        showPlanProblem();
    }

    /**
     * The plan as the tab shows it.
     *
     * <p>Nothing here can fail any more. Every step came out of a form that would not let it be written
     * wrong, so there is no line to fail to parse and no saved steps to fall back on — which is what the
     * editor is for (§12 item 24). What the store still checks on save is the shape of the plan as a
     * whole: that a CHAIN comes first and a CONFIGURE last, that SPLIT and TARGET appear at most once,
     * and that every transition names a step that is there. Those are properties of the list and not of
     * any row in it, and they are shown beside the list as it is edited.</p>
     */
    private LearningPlan plan() {
        return new LearningPlan(planStepListPresenter.write(), getView().getTemplateOverrides(),
                plan.getBuiltInVersion());
    }

    /**
     * The same plan in the grammar the harness and import/export carry, beneath the list: a person
     * reading the whole of it at once, or pasting it somewhere, wants the text.
     */
    private void showPlanText() {
        getView().setPlanText(planStepListPresenter.write().stream()
                .map(PlanStep::format)
                .collect(Collectors.joining("\n")));
    }

    /**
     * What the store will refuse on save, shown as the list is edited rather than when the save comes
     * back (§10.2): a plan is a graph, and the things that can be wrong with one are properties of the
     * whole of it rather than of any step — which is exactly why a form per step cannot catch them.
     */
    private void showPlanProblem() {
        // The plan's own check, which is the one the store runs: said in one place so that the tab and
        // the save cannot disagree about what is wrong.
        final List<String> problems = plan().problems();
        getView().setPlanProblem(problems.isEmpty()
                ? null
                : String.join("; ", problems));
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
         * The list the plan is edited in, in place of the text box it was typed in (§12 item 24).
         */
        void setPlanStepsView(View view);

        /**
         * The same plan in the grammar the harness and import/export carry, shown beneath the list and
         * not edited.
         */
        void setPlanText(String text);

        void setPlanProblem(String problem);

        Map<Template, String> getTemplateOverrides();

        void setTemplateOverrides(Map<Template, String> templates, Integer savedAgainstVersion);

        void setBuiltInTemplates(Map<Template, String> templates, int version);

        int getSampleSizeLimit();

        void setSampleSizeLimit(int sampleSizeLimit);
    }
}
