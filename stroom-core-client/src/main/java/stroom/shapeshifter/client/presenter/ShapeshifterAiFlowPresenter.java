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

import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.shapeshifter.client.presenter.ShapeshifterAiFlowPresenter.ShapeshifterAiFlowView;
import stroom.shapeshifter.client.view.FlowChart;
import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowProblem;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Template;
import stroom.widget.menu.client.presenter.Item;
import stroom.widget.menu.client.presenter.MenuBuilder;
import stroom.widget.menu.client.presenter.MenuPresenter;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupPosition;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/// The Flow tab (design 05; A82): the learning flow drawn as a flowchart and edited in it. A step clicked
/// on the chart opens in the form beside it, and every change to the form redraws the chart; its
/// transitions are listed in the form, and a declared edge clicked on the chart selects its transition
/// there. Steps are added after the selected one, copied, removed and moved from the toolbar above the
/// form. The question text each kind of step is asked with sits beneath the form (A85), and the texts
/// every question shares open from Start (A86). The same flow as text sits beneath the chart, two-way
/// (A87). What is wrong with the flow paints the step it names and is said beside it (A88).
public class ShapeshifterAiFlowPresenter
        extends DocPresenter<ShapeshifterAiFlowView, ShapeshifterAiDoc>
        implements ShapeshifterAiSettingsUiHandlers {

    private static final ShapeshifterAiResource RESOURCE = GWT.create(ShapeshifterAiResource.class);
    /// The texts that belong to no step: what every question is asked under, and what the questions
    /// include by variable.
    private static final List<Template> SHARED_TEXTS = List.of(Template.SYSTEM, Template.SPLIT_RULES,
            Template.EXTRACTION_RULES, Template.TRANSFORMATION_RULES);

    private final RestFactory restFactory;
    private final FlowStepPresenter stepPresenter;
    private final MenuPresenter menuPresenter;
    private final List<FlowStep> steps = new ArrayList<>();
    /// The step open in the form, or null where Start, End or nothing is selected.
    private Integer selected;
    private String selectedTerminal;
    private boolean readingStep;
    /// The flow as read, for what the chart does not hold: the template overrides and the built-in
    /// version they were saved against.
    private LearningFlow flow = LearningFlow.of(FlowExample.DIRECT);

    @Inject
    public ShapeshifterAiFlowPresenter(final EventBus eventBus,
                                       final ShapeshifterAiFlowView view,
                                       final FlowStepPresenter stepPresenter,
                                       final MenuPresenter menuPresenter,
                                       final RestFactory restFactory) {
        super(eventBus, view);
        this.stepPresenter = stepPresenter;
        this.menuPresenter = menuPresenter;
        this.restFactory = restFactory;
        view.setUiHandlers(this);
        view.setStepFormView(stepPresenter.getView());
        stepPresenter.setOnChange(this::stepEdited);
        view.setOnStepSelected(this::select);
        view.setOnTerminalSelected(this::selectTerminal);
        view.setOnEdgeSelected((step, edge) -> {
            select(step);
            stepPresenter.selectTransition(edge);
        });
    }

    /// An example flow was chosen: its steps are what the flow starts from (A34). A starting point and
    /// not a setting, since what is saved is the steps, which the person then edits.
    @Override
    public void onFlowExample(final List<FlowStep> steps) {
        if (isReadOnly()) {
            return;
        }
        this.steps.clear();
        this.steps.addAll(steps);
        select(steps.isEmpty()
                ? null
                : 0);
        flowChanged();
        onChange();
    }

    /// The examples as a menu at the button (A34, A37); a flow that has steps is confirmed first.
    @Override
    public void onLoadExample(final int x, final int y) {
        if (isReadOnly()) {
            return;
        }
        final MenuBuilder menu = MenuBuilder.builder();
        for (final FlowExample example : FlowExample.values()) {
            menu.withSimpleMenuItem(item -> item
                    .text(example.getDisplayValue())
                    .command(() -> confirmThenLoad(example)));
        }
        final List<Item> items = menu.build();
        menuPresenter.setData(items);
        ShowPopupEvent.builder(menuPresenter)
                .popupType(PopupType.POPUP)
                .popupPosition(new PopupPosition(x, y))
                .fire();
    }

    /// A step after the selected one, or at the end: where a step sits is part of what the flow means,
    /// so a person adding one while looking at another means it to go there.
    @Override
    public void onAddStep() {
        if (isReadOnly()) {
            return;
        }
        final int at = selected == null
                ? steps.size()
                : selected + 1;
        steps.add(at, FlowStep.of(QuestionKind.CONFIGURE));
        flowChanged();
        onChange();
        select(at);
    }

    /// Without its id: two steps of one name is a flow whose transitions cannot say which they mean, and
    /// the copy is the one that should be renamed.
    @Override
    public void onCopyStep() {
        if (isReadOnly() || selected == null) {
            return;
        }
        final FlowStep original = steps.get(selected);
        steps.add(selected + 1, new FlowStep(null, original.getKind(), original.getRole(), original.getWhen(),
                original.getCandidates(), original.getKinds(), original.getChecks(), original.getTransitions()));
        flowChanged();
        onChange();
        select(selected + 1);
    }

    /// A step nothing else points at goes without ceremony; one a transition names is confirmed with
    /// what would be left pointing at nothing, since the store refuses a flow whose `goto` names no step
    /// and the refusal arrives a long way from the delete that caused it.
    @Override
    public void onDeleteStep() {
        if (isReadOnly() || selected == null) {
            return;
        }
        final int at = selected;
        final FlowStep step = steps.get(at);
        final String id = step.effectiveId();
        final List<String> pointing = new ArrayList<>();
        for (final FlowStep other : steps) {
            if (other != step && other.getTransitions().stream()
                    .anyMatch(transition -> id.equals(transition.getGoTo()))) {
                pointing.add(other.effectiveId());
            }
        }
        if (pointing.isEmpty()) {
            removeStep(at);
            return;
        }
        ConfirmEvent.fire(this,
                "Remove step '" + id + "'? " + String.join(", ", pointing)
                + " goes to it, and a flow whose transition names no step is refused on save.",
                ok -> {
                    if (ok) {
                        removeStep(at);
                    }
                });
    }

    @Override
    public void onMoveStep(final int by) {
        if (isReadOnly() || selected == null) {
            return;
        }
        final int from = selected;
        final int to = from + by;
        if (to < 0 || to >= steps.size()) {
            return;
        }
        final FlowStep step = steps.remove(from);
        steps.add(to, step);
        flowChanged();
        onChange();
        select(to);
    }

    /// The text beneath the chart was edited (A87): every line is parsed, and the chart follows where
    /// every line parses. A line that does not is reported where the flow's problems are, and nothing
    /// changes until it does.
    @Override
    public void onFlowText(final String text) {
        if (isReadOnly()) {
            return;
        }
        final List<FlowStep> parsed = new ArrayList<>();
        int number = 0;
        for (final String line : text.split("\n")) {
            number++;
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                parsed.add(FlowStep.parse(line));
            } catch (final RuntimeException e) {
                getView().setFlowProblem("Line " + number + ": " + e.getMessage());
                return;
            }
        }
        steps.clear();
        steps.addAll(parsed);
        flowChanged();
        onChange();
        select(selected != null && selected < steps.size()
                ? selected
                : null);
    }

    private void removeStep(final int at) {
        steps.remove(at);
        flowChanged();
        onChange();
        select(steps.isEmpty()
                ? null
                : Math.min(at, steps.size() - 1));
    }

    private void confirmThenLoad(final FlowExample example) {
        final int count = steps.size();
        if (count == 0) {
            onFlowExample(example.steps());
            return;
        }
        ConfirmEvent.fire(this,
                "Replace this flow's " + count + " step" + (count == 1
                        ? ""
                        : "s") + " with the " + example.getDisplayValue() + " example?",
                ok -> {
                    if (ok) {
                        onFlowExample(example.steps());
                    }
                });
    }

    /// A step was clicked, or chosen by an edit: it opens in the form, and the chart paints it.
    private void select(final Integer index) {
        selectedTerminal = null;
        selected = index == null || index < 0 || index >= steps.size()
                ? null
                : index;
        final ShapeshifterAiFlowView view = getView();
        view.setSelectedStep(selected);
        if (selected == null) {
            view.showNothingSelected();
            view.setTemplateChoices(SHARED_TEXTS);
            return;
        }
        final FlowStep step = steps.get(selected);
        readingStep = true;
        stepPresenter.read(step, ids(), isReadOnly());
        readingStep = false;
        view.showStep(selected + 1, steps.size(), title(step), defaults(step),
                problemsOf(selected));
        view.setTemplateChoices(textsOf(step.getKind()));
    }

    /// Start or End was clicked: Start opens the shared texts (A86), End says what ending means.
    private void selectTerminal(final String terminal) {
        selected = null;
        selectedTerminal = terminal;
        getView().setSelectedTerminal(terminal);
        if (FlowChart.START.equals(terminal)) {
            getView().showStart();
            getView().setTemplateChoices(SHARED_TEXTS);
        } else {
            getView().showEnd();
            getView().setTemplateChoices(List.of());
        }
    }

    /// A field of the form, or a transition, changed: the step is rewritten from the form where the
    /// form can be read as a step, and the chart follows. What the form cannot stop a person writing —
    /// an id that is not a word, a limit below one, a name another step has — is said beside the form,
    /// and the step keeps its last good state until it is put right.
    private void stepEdited() {
        if (readingStep || selected == null || isReadOnly()) {
            return;
        }
        final FlowStep written;
        try {
            written = stepPresenter.write();
        } catch (final RuntimeException fault) {
            getView().setStepProblem(fault.getMessage());
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (i != selected && steps.get(i).effectiveId().equals(written.effectiveId())) {
                getView().setStepProblem("Another step is already called '" + written.effectiveId()
                                         + "'. Two of one name leaves a transition unable to say which it means.");
                return;
            }
        }
        steps.set(selected, written);
        flowChanged();
        onChange();
        getView().showStep(selected + 1, steps.size(), title(written), defaults(written), problemsOf(selected));
        getView().setTemplateChoices(textsOf(written.getKind()));
    }

    @Override
    protected void onBind() {
        super.onBind();
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
        flow = doc.getFlow();
        steps.clear();
        steps.addAll(flow.getSteps());
        getView().setTemplateOverrides(flow.getTemplates(), flow.getBuiltInVersion());
        flowChanged();
        select(steps.isEmpty()
                ? null
                : 0);
    }

    @Override
    protected ShapeshifterAiDoc onWrite(final ShapeshifterAiDoc doc) {
        return doc.copy().flow(new LearningFlow(new ArrayList<>(steps), getView().getTemplateOverrides(),
                flow.getBuiltInVersion())).build();
    }

    /// The flow changed: the chart and the text say what the steps now say, and what is wrong with the
    /// flow is painted and listed.
    private void flowChanged() {
        final List<FlowProblem> problems = check();
        getView().showChart(new ArrayList<>(steps), problems);
        getView().setFlowText(steps.stream().map(FlowStep::format).collect(Collectors.joining("\n")));
        getView().setFlowProblem(problems.isEmpty()
                ? null
                : String.join("; ", FlowProblem.messages(problems)));
    }

    /// The flow's own check, which is the one the store runs, asked of the steps alone.
    private List<FlowProblem> check() {
        return new LearningFlow(new ArrayList<>(steps), null, null).check();
    }

    private String problemsOf(final int index) {
        final List<String> mine = new ArrayList<>();
        for (final FlowProblem problem : check()) {
            if (problem.getStep() != null && problem.getStep() == index) {
                mine.add(problem.getMessage());
            }
        }
        return mine.isEmpty()
                ? null
                : String.join("; ", mine);
    }

    private List<String> ids() {
        return steps.stream().map(FlowStep::effectiveId).toList();
    }

    private static String title(final FlowStep step) {
        return step.getKind() == QuestionKind.CONFIGURE && step.getRole() != null
                ? "Configure " + step.getRole().getDisplayValue()
                : step.getKind().getDisplayValue();
    }

    /// What the step does where the flow says nothing (A84), said beneath its transitions.
    private static String defaults(final FlowStep step) {
        final List<String> parts = new ArrayList<>();
        if (step.transitionOn(StepOutcome.PASSED) == null) {
            parts.add("passed goes to the next step");
        }
        if (step.getCandidates() == null || step.getCandidates() != 1) {
            parts.add("a shortfall re-asks with the next candidate");
        }
        if (step.transitionOnSpent() == null) {
            parts.add("spent " + (step.getRole() == ConfigureRole.PARSER
                    ? "gives the shape up (G), the parser's default"
                    : "parks for a person (P)"));
        }
        return parts.isEmpty()
                ? "Every outcome has a transition."
                : "By default: " + String.join("; ", parts) + ".";
    }

    /// The question text a kind of step is asked with (A85): the split's three variants belong to one
    /// step, since the guard chooses among them.
    private static List<Template> textsOf(final QuestionKind kind) {
        switch (kind) {
            case CHAIN:
                return List.of(Template.CHAIN);
            case SPLIT:
                return List.of(Template.SPLIT, Template.SPLIT_XML, Template.SPLIT_JSON);
            case TARGET:
                return List.of(Template.TARGET);
            case ADMIT:
                return List.of(Template.ADMISSION);
            default:
                return List.of(Template.CONFIGURATION);
        }
    }


    // --------------------------------------------------------------------------------


    public interface ShapeshifterAiFlowView
            extends View, ReadOnlyChangeHandler, HasUiHandlers<ShapeshifterAiSettingsUiHandlers> {

        /// The flow drawn, with what is wrong with it painted on the steps it names.
        void showChart(List<FlowStep> steps, List<FlowProblem> problems);

        void setSelectedStep(Integer index);

        void setSelectedTerminal(String terminal);

        void setOnStepSelected(Consumer<Integer> onSelected);

        void setOnTerminalSelected(Consumer<String> onSelected);

        void setOnEdgeSelected(BiConsumer<Integer, String> onSelected);

        /// The form the selected step is edited in.
        void setStepFormView(View view);

        /// The side panel showing a step: its place, its title, what it does by default, and what is
        /// wrong with it, or null.
        void showStep(int number, int of, String title, String defaults, String problem);

        void showStart();

        void showEnd();

        void showNothingSelected();

        /// What the form cannot stop a person writing, said beside it; null clears it.
        void setStepProblem(String problem);

        /// The same flow in the grammar the harness and import/export carry, beneath the chart.
        void setFlowText(String text);

        void setFlowProblem(String problem);

        /// Which texts the question-text section offers: a step's kind's, or the shared ones at Start.
        void setTemplateChoices(List<Template> templates);

        Map<Template, String> getTemplateOverrides();

        void setTemplateOverrides(Map<Template, String> templates, Integer savedAgainstVersion);

        void setBuiltInTemplates(Map<Template, String> templates, int version);
    }
}
