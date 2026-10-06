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

package stroom.shapeshifter.client.view;

import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.ShapeshifterAiFlowPresenter.ShapeshifterAiFlowView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiSettingsUiHandlers;
import stroom.shapeshifter.shared.FlowProblem;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.Template;
import stroom.widget.button.client.Button;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ShapeshifterAiFlowViewImpl
        extends ViewWithUiHandlers<ShapeshifterAiSettingsUiHandlers>
        implements ShapeshifterAiFlowView, ReadOnlyChangeHandler {

    private final Widget widget;
    private final FlowChart chart = new FlowChart();
    @UiField
    SimplePanel chartPanel;
    @UiField
    Button addStep;
    @UiField
    Button copyStep;
    @UiField
    Button deleteStep;
    @UiField
    Button moveUp;
    @UiField
    Button moveDown;
    /// Loading an example replaces the steps, so it is a button and not a picker: what it does is done
    /// once, and nothing about it describes the flow afterwards.
    @UiField
    Button loadExample;
    @UiField
    Label stepHeading;
    @UiField
    Label stepProblem;
    @UiField
    SimplePanel stepForm;
    @UiField
    Label stepDefaults;
    @UiField
    Label nodeNote;
    @UiField
    FlowPanel textSection;
    @UiField
    SelectionBox<Template> template;
    @UiField
    TextArea templateText;
    @UiField
    Button resetTemplate;
    @UiField
    Label templateStatus;
    @UiField
    Label flowProblem;
    /// The same flow in the grammar the harness and import/export carry, two-way (A87).
    @UiField
    TextArea flowText;

    /// This document's changed texts, by template; and the built-ins the node serves, shown where the
    /// document has not changed one.
    private final Map<Template, String> overrides = new HashMap<>();
    private Map<Template, String> builtIns = new HashMap<>();
    private Integer builtInVersion;
    private Integer savedAgainstVersion;
    private boolean readOnly;
    private boolean stepShown;
    /// What the chart paints as selected, kept here so that a redraw after an edit keeps it.
    private Integer selectedStep;

    @Inject
    public ShapeshifterAiFlowViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        chartPanel.setWidget(chart);
        showNothingSelected();
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void showChart(final List<FlowStep> steps, final List<FlowProblem> problems) {
        if (selectedStep != null && selectedStep >= steps.size()) {
            selectedStep = null;
        }
        chart.show(steps, problems, null, selectedStep);
    }

    @Override
    public void setSelectedStep(final Integer index) {
        selectedStep = index;
        chart.setSelected(index);
    }

    @Override
    public void setSelectedTerminal(final String terminal) {
        selectedStep = null;
        chart.setSelectedTerminal(terminal);
    }

    @Override
    public void setOnStepSelected(final Consumer<Integer> onSelected) {
        chart.setOnSelect(onSelected);
    }

    @Override
    public void setOnTerminalSelected(final Consumer<String> onSelected) {
        chart.setOnTerminalSelect(onSelected);
    }

    @Override
    public void setOnEdgeSelected(final BiConsumer<Integer, String> onSelected) {
        chart.setOnEdgeSelect(onSelected);
    }

    @Override
    public void setStepFormView(final View view) {
        stepForm.setWidget(view.asWidget());
    }

    @Override
    public void showStep(final int number,
                         final int of,
                         final String title,
                         final String defaults,
                         final String problem) {
        stepShown = true;
        stepHeading.setText("Step " + number + " of " + of + "  ·  " + title);
        setStepProblem(problem);
        stepForm.setVisible(true);
        stepDefaults.setText(defaults);
        stepDefaults.setVisible(true);
        nodeNote.setVisible(false);
        textSection.setVisible(true);
        updateButtons();
    }

    @Override
    public void showStart() {
        stepShown = false;
        stepHeading.setText("Start");
        setStepProblem(null);
        stepForm.setVisible(false);
        stepDefaults.setVisible(false);
        nodeNote.setText("A session opens for a shape no rule binds, with a sample of the stream in hand. "
                         + "The texts below are shared by every question: the system text they are all asked "
                         + "under, and the rules the questions include.");
        nodeNote.setVisible(true);
        textSection.setVisible(true);
        updateButtons();
    }

    @Override
    public void showEnd() {
        stepShown = false;
        stepHeading.setText("End");
        setStepProblem(null);
        stepForm.setVisible(false);
        stepDefaults.setVisible(false);
        nodeNote.setText("The fragment is bound as the shape's rule, the corpus is written, and the stream "
                         + "and every stream that waited on it are served. A session also ends by an exit: P, "
                         + "parked for a person to grant more, answer a turn or send it to the exhaustion step; "
                         + "G, the shape given up and its streams errored, which is a parser's default.");
        nodeNote.setVisible(true);
        textSection.setVisible(false);
        updateButtons();
    }

    @Override
    public void showNothingSelected() {
        stepShown = false;
        stepHeading.setText("No step selected");
        setStepProblem(null);
        stepForm.setVisible(false);
        stepDefaults.setVisible(false);
        nodeNote.setText("Click a step on the chart to edit it here, Start for the texts every question "
                         + "shares, or add a step.");
        nodeNote.setVisible(true);
        textSection.setVisible(true);
        updateButtons();
    }

    @Override
    public void setStepProblem(final String problem) {
        stepProblem.setText(problem == null
                ? ""
                : problem);
        stepProblem.setVisible(problem != null);
    }

    /// What is wrong with the flow as a whole, beneath the chart: `onWrite` runs on every dirty check,
    /// so a fault here must not be a modal.
    @Override
    public void setFlowProblem(final String problem) {
        flowProblem.setText(problem == null
                ? ""
                : problem);
        flowProblem.setVisible(problem != null);
    }

    @Override
    public void setFlowText(final String text) {
        flowText.setValue(text);
    }

    @Override
    public void setTemplateChoices(final List<Template> templates) {
        final Template was = template.getValue();
        template.clear();
        template.addItems(templates);
        template.setValue(templates.contains(was)
                ? was
                : templates.isEmpty()
                        ? null
                        : templates.get(0));
        textSection.setVisible(!templates.isEmpty() && (stepShown || nodeNote.isVisible()));
        showTemplate();
    }

    @Override
    public Map<Template, String> getTemplateOverrides() {
        return new HashMap<>(overrides);
    }

    @Override
    public void setTemplateOverrides(final Map<Template, String> templates, final Integer savedAgainstVersion) {
        overrides.clear();
        overrides.putAll(templates);
        this.savedAgainstVersion = savedAgainstVersion;
        showTemplate();
    }

    @Override
    public void setBuiltInTemplates(final Map<Template, String> templates, final int version) {
        builtIns = new HashMap<>(templates);
        builtInVersion = version;
        showTemplate();
    }

    /// The selected template as this document uses it, and where its text comes from. Text is per kind
    /// of question (A85): the status says so where an edit reaches more than this step.
    private void showTemplate() {
        final Template selected = template.getValue();
        if (selected == null) {
            templateText.setValue("");
            templateStatus.setText("");
            resetTemplate.setEnabled(false);
            return;
        }
        final String override = overrides.get(selected);
        templateText.setValue(override != null
                ? override
                : builtIns.getOrDefault(selected, ""));
        resetTemplate.setEnabled(override != null && !readOnly);
        final StringBuilder status = new StringBuilder(override != null
                ? "This document's own text"
                : builtIns.containsKey(selected)
                        ? "The built-in text"
                        : "The built-in text has not been fetched");
        if (builtInVersion != null) {
            status.append("; built-in version ").append(builtInVersion);
            if (savedAgainstVersion != null && !savedAgainstVersion.equals(builtInVersion)) {
                status.append(", this document last saved against ").append(savedAgainstVersion);
            }
        }
        status.append(". Variables: ").append(selected.getVariables().length == 0
                ? "none"
                : String.join(", ", variables(selected)));
        if (selected == Template.CONFIGURATION) {
            status.append(". Asked at every Configure step, parser and transform alike.");
        }
        templateStatus.setText(status.toString());
    }

    private static List<String> variables(final Template template) {
        final List<String> names = new ArrayList<>();
        for (final String variable : template.getVariables()) {
            names.add("${" + variable + "}");
        }
        return names;
    }

    private void updateButtons() {
        final boolean enabled = !readOnly;
        addStep.setEnabled(enabled);
        loadExample.setEnabled(enabled);
        copyStep.setEnabled(enabled && stepShown);
        deleteStep.setEnabled(enabled && stepShown);
        moveUp.setEnabled(enabled && stepShown);
        moveDown.setEnabled(enabled && stepShown);
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        this.readOnly = readOnly;
        flowText.setReadOnly(readOnly);
        template.setEnabled(!readOnly);
        templateText.setReadOnly(readOnly);
        resetTemplate.setEnabled(!readOnly && overrides.containsKey(template.getValue()));
        updateButtons();
    }

    private void fireChange() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    @UiHandler("addStep")
    public void onAddStep(final ClickEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onAddStep();
        }
    }

    @UiHandler("copyStep")
    public void onCopyStep(final ClickEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onCopyStep();
        }
    }

    @UiHandler("deleteStep")
    public void onDeleteStep(final ClickEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onDeleteStep();
        }
    }

    @UiHandler("moveUp")
    public void onMoveUp(final ClickEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onMoveStep(-1);
        }
    }

    @UiHandler("moveDown")
    public void onMoveDown(final ClickEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onMoveStep(1);
        }
    }

    /// Which example to load is asked here, where the button is; the menu of them, and what replacing a
    /// flow means, belong to the presenter.
    @UiHandler("loadExample")
    public void onLoadExample(final ClickEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onLoadExample(event.getClientX(), event.getClientY());
        }
    }

    @UiHandler("flowText")
    public void onFlowText(final ValueChangeEvent<String> event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onFlowText(event.getValue() == null
                    ? ""
                    : event.getValue());
        }
    }

    @UiHandler("template")
    public void onTemplate(final ValueChangeEvent<Template> event) {
        showTemplate();
    }

    /// Text equal to the built-in is no change: the document keeps following the built-in.
    @UiHandler("templateText")
    public void onTemplateText(final ValueChangeEvent<String> event) {
        final Template selected = template.getValue();
        if (selected == null) {
            return;
        }
        final String text = event.getValue();
        if (text == null || text.equals(builtIns.get(selected))) {
            overrides.remove(selected);
        } else {
            overrides.put(selected, text);
        }
        showTemplate();
        fireChange();
    }

    @UiHandler("resetTemplate")
    public void onResetTemplate(final ClickEvent event) {
        final Template selected = template.getValue();
        if (selected != null && overrides.remove(selected) != null) {
            showTemplate();
            fireChange();
        }
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, ShapeshifterAiFlowViewImpl> {

    }
}
