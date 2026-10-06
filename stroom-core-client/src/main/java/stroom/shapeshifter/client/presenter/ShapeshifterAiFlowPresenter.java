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
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowProblem;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;

/// The Flow tab (design 05; A82): the learning flow drawn as a flowchart, the table of its steps beneath
/// to edit them in, the same flow as text to read or paste, and the words of each question. The chart
/// follows the table and the text follows both; a line of text that does not parse is reported and
/// changes nothing until it does (A87). What is wrong with the flow paints the step it names on the
/// chart and is listed beneath it (A88).
public class ShapeshifterAiFlowPresenter
        extends DocPresenter<ShapeshifterAiFlowView, ShapeshifterAiDoc>
        implements ShapeshifterAiSettingsUiHandlers {

    private static final ShapeshifterAiResource RESOURCE = GWT.create(ShapeshifterAiResource.class);

    private final RestFactory restFactory;
    private final FlowStepListPresenter flowStepListPresenter;
    private final MenuPresenter menuPresenter;
    /// The flow as read, for what the table does not hold: the template overrides and the built-in
    /// version they were saved against.
    private LearningFlow flow = LearningFlow.of(FlowExample.DIRECT);

    @Inject
    public ShapeshifterAiFlowPresenter(final EventBus eventBus,
                                       final ShapeshifterAiFlowView view,
                                       final FlowStepListPresenter flowStepListPresenter,
                                       final MenuPresenter menuPresenter,
                                       final RestFactory restFactory) {
        super(eventBus, view);
        this.flowStepListPresenter = flowStepListPresenter;
        this.menuPresenter = menuPresenter;
        this.restFactory = restFactory;
        view.setUiHandlers(this);
        view.setFlowStepsView(flowStepListPresenter.getView());
        // The table is the flow: what it does is what makes the document dirty, and the chart follows.
        flowStepListPresenter.setOnChange(() -> {
            flowChanged();
            onChange();
        });
        // A step chosen in either place is chosen in both.
        flowStepListPresenter.setOnSelect(view::setSelectedStep);
        view.setOnStepSelected(flowStepListPresenter::select);
    }

    /// An example flow was chosen: its steps are what the table starts from (A34). A starting point and
    /// not a setting, since what is saved is the steps, which the person then edits.
    @Override
    public void onFlowExample(final List<FlowStep> steps) {
        if (isReadOnly()) {
            return;
        }
        flowStepListPresenter.read(steps, false);
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

    /// The text beneath the chart was edited (A87): every line is parsed, and the table and the chart
    /// follow where every line parses. A line that does not is reported where the flow's problems are,
    /// and nothing changes until it does.
    @Override
    public void onFlowText(final String text) {
        if (isReadOnly()) {
            return;
        }
        final List<FlowStep> steps = new ArrayList<>();
        int number = 0;
        for (final String line : text.split("\n")) {
            number++;
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                steps.add(FlowStep.parse(line));
            } catch (final RuntimeException e) {
                getView().setFlowProblem("Line " + number + ": " + e.getMessage());
                return;
            }
        }
        flowStepListPresenter.read(steps, false);
        flowChanged();
        onChange();
    }

    private void confirmThenLoad(final FlowExample example) {
        final int steps = flowStepListPresenter.write().size();
        if (steps == 0) {
            onFlowExample(example.steps());
            return;
        }
        ConfirmEvent.fire(this,
                "Replace this flow's " + steps + " step" + (steps == 1
                        ? ""
                        : "s") + " with the " + example.getDisplayValue() + " example?",
                ok -> {
                    if (ok) {
                        onFlowExample(example.steps());
                    }
                });
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
        flowStepListPresenter.read(flow.getSteps(), readOnly);
        flowChanged();
        getView().setTemplateOverrides(flow.getTemplates(), flow.getBuiltInVersion());
    }

    @Override
    protected ShapeshifterAiDoc onWrite(final ShapeshifterAiDoc doc) {
        return doc.copy().flow(flow()).build();
    }

    /// The flow changed: the chart and the text say what the table now says, and what is wrong with it
    /// is painted and listed.
    private void flowChanged() {
        final List<FlowStep> steps = flowStepListPresenter.write();
        // The flow's own check, which is the one the store runs, asked of the steps alone.
        final List<FlowProblem> problems = new LearningFlow(steps, null, null).check();
        getView().showChart(steps, problems);
        getView().setFlowText(steps.stream().map(FlowStep::format).collect(Collectors.joining("\n")));
        getView().setFlowProblem(problems.isEmpty()
                ? null
                : String.join("; ", FlowProblem.messages(problems)));
    }

    private LearningFlow flow() {
        return new LearningFlow(flowStepListPresenter.write(), getView().getTemplateOverrides(),
                flow.getBuiltInVersion());
    }


    // --------------------------------------------------------------------------------


    public interface ShapeshifterAiFlowView
            extends View, ReadOnlyChangeHandler, HasUiHandlers<ShapeshifterAiSettingsUiHandlers> {

        /// The flow drawn, with what is wrong with it painted on the steps it names.
        void showChart(List<FlowStep> steps, List<FlowProblem> problems);

        /// Paint one step of the chart as the selected one, or none.
        void setSelectedStep(Integer index);

        /// Who is told which step of the chart was clicked.
        void setOnStepSelected(Consumer<Integer> onSelected);

        /// The table the steps are edited in.
        void setFlowStepsView(View view);

        /// The same flow in the grammar the harness and import/export carry, beneath the chart.
        void setFlowText(String text);

        void setFlowProblem(String problem);

        Map<Template, String> getTemplateOverrides();

        void setTemplateOverrides(Map<Template, String> templates, Integer savedAgainstVersion);

        void setBuiltInTemplates(Map<Template, String> templates, int version);
    }
}
