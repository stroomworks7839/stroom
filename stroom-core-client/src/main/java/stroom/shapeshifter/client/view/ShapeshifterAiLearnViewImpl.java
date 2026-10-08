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
import stroom.shapeshifter.client.presenter.ShapeshifterAiLearnPresenter.ShapeshifterAiLearnView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiSettingsUiHandlers;
import stroom.shapeshifter.shared.FlowProblem;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.Template;
import stroom.widget.button.client.Button;
import stroom.widget.valuespinner.client.ValueSpinner;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ShapeshifterAiLearnViewImpl
        extends ViewWithUiHandlers<ShapeshifterAiSettingsUiHandlers>
        implements ShapeshifterAiLearnView, ReadOnlyChangeHandler {

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
    /// The session's settings, at Start (A94).
    @UiField
    FlowPanel startSection;
    @UiField
    SimplePanel model;
    @UiField
    TextArea instructions;
    @UiField
    ValueSpinner maxAttempts;
    @UiField
    ValueSpinner attemptBudgetMs;
    @UiField
    SelectionBox<LearningMode> learningMode;
    @UiField
    ValueSpinner maxDeferralAgeMs;
    @UiField
    ValueSpinner tokenBudget;
    @UiField
    ValueSpinner sampleSizeLimit;
    @UiField
    FlowPanel textSection;
    /// The texts the selected node is asked with, one slot each.
    @UiField
    FlowPanel texts;
    @UiField
    Label flowProblem;
    /// The same flow in the grammar the harness and import/export carry, two-way (A87).
    @UiField
    TextArea flowText;

    /// This document's changed texts, by template; and the built-ins the node serves, shown where the
    /// document has not changed one.
    private final Map<Template, String> overrides = new HashMap<>();
    private final Map<Template, TextSlot> slots = new LinkedHashMap<>();
    private Map<Template, String> builtIns = new HashMap<>();
    private Integer builtInVersion;
    private Integer savedAgainstVersion;
    private boolean readOnly;
    private boolean stepShown;
    /// What the chart paints as selected, kept here so that a redraw after an edit keeps it.
    private Integer selectedStep;

    @Inject
    public ShapeshifterAiLearnViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        chartPanel.setWidget(chart);
        bound(maxAttempts, 1, Integer.MAX_VALUE);
        bound(attemptBudgetMs, 1, Long.MAX_VALUE);
        bound(maxDeferralAgeMs, 1, Long.MAX_VALUE);
        learningMode.addItems(LearningMode.values());
        bound(tokenBudget, 0, Long.MAX_VALUE);
        bound(sampleSizeLimit, 1, Integer.MAX_VALUE);
        showNothingSelected();
    }

    private static void bound(final ValueSpinner spinner, final long min, final long max) {
        spinner.setMin(min);
        spinner.setMax(max);
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
    public void setModelView(final View view) {
        model.setWidget(view.asWidget());
    }

    @Override
    public String getInstructions() {
        return instructions.getValue();
    }

    @Override
    public void setInstructions(final String instructions) {
        this.instructions.setValue(instructions);
    }

    @Override
    public int getMaxAttempts() {
        return maxAttempts.getIntValue();
    }

    @Override
    public void setMaxAttempts(final int maxAttempts) {
        this.maxAttempts.setValue(maxAttempts);
    }

    @Override
    public LearningMode getLearningMode() {
        return learningMode.getValue();
    }

    @Override
    public void setLearningMode(final LearningMode learningMode) {
        this.learningMode.setValue(learningMode);
    }

    @Override
    public long getMaxDeferralAgeMs() {
        return maxDeferralAgeMs.getValue();
    }

    @Override
    public void setMaxDeferralAgeMs(final long maxDeferralAgeMs) {
        this.maxDeferralAgeMs.setValue(maxDeferralAgeMs);
    }

    @Override
    public long getAttemptBudgetMs() {
        return attemptBudgetMs.getValue();
    }

    @Override
    public void setAttemptBudgetMs(final long attemptBudgetMs) {
        this.attemptBudgetMs.setValue(attemptBudgetMs);
    }

    @Override
    public Long getTokenBudget() {
        final long tokens = tokenBudget.getValue();
        return tokens == 0
                ? null
                : tokens;
    }

    @Override
    public void setTokenBudget(final Long tokenBudget) {
        this.tokenBudget.setValue(tokenBudget == null
                ? 0
                : tokenBudget);
    }

    @Override
    public int getSampleSizeLimit() {
        return sampleSizeLimit.getIntValue();
    }

    @Override
    public void setSampleSizeLimit(final int sampleSizeLimit) {
        this.sampleSizeLimit.setValue(sampleSizeLimit);
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
        startSection.setVisible(false);
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
        nodeNote.setText("A session opens for a learner nothing serves, with a sample of the stream in hand. "
                         + "These are the session's own settings: the model it talks to, the instructions every "
                         + "question carries, what it may spend, and the texts every question shares.");
        nodeNote.setVisible(true);
        startSection.setVisible(true);
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
        nodeNote.setText("The fragment is bound as the learner's binding, the corpus is written, and the stream "
                         + "and every stream that waited on it are served. A session also ends by an exit: park, "
                         + "for a person to grant more, answer a turn or send it to the exhaustion step; give-up, "
                         + "the learner given up and its streams errored, which is a parser's default.");
        nodeNote.setVisible(true);
        startSection.setVisible(false);
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
        nodeNote.setText("Click a step on the chart to edit it here, Start for the session's settings and the "
                         + "texts every question shares, or add a step.");
        nodeNote.setVisible(true);
        startSection.setVisible(false);
        textSection.setVisible(false);
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
    public void setTexts(final List<Template> templates) {
        slots.clear();
        texts.clear();
        for (final Template template : templates) {
            final TextSlot slot = new TextSlot(template);
            slots.put(template, slot);
            texts.add(slot.widget);
        }
        textSection.setVisible(!templates.isEmpty());
        showTemplates();
    }

    @Override
    public void setText(final Template slot, final String text) {
        if (text == null || text.equals(builtIns.get(slot))) {
            overrides.remove(slot);
        } else {
            overrides.put(slot, text);
        }
        showTemplates();
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
        showTemplates();
    }

    @Override
    public void setBuiltInTemplates(final Map<Template, String> templates, final int version) {
        builtIns = new HashMap<>(templates);
        builtInVersion = version;
        showTemplates();
    }

    /// Every slot as this document uses it, and where its text comes from. Text is per kind of question
    /// (A85): the status says so where an edit reaches more than this step.
    private void showTemplates() {
        for (final TextSlot slot : slots.values()) {
            final String override = overrides.get(slot.template);
            slot.text.setValue(override != null
                    ? override
                    : builtIns.getOrDefault(slot.template, ""));
            slot.text.setReadOnly(readOnly);
            slot.load.setEnabled(!readOnly);
            final StringBuilder status = new StringBuilder(override != null
                    ? "This document's own text"
                    : builtIns.containsKey(slot.template)
                            ? "The built-in text"
                            : "The built-in text has not been fetched");
            if (builtInVersion != null) {
                status.append(", built-in version ").append(builtInVersion);
                if (savedAgainstVersion != null && !savedAgainstVersion.equals(builtInVersion)) {
                    status.append(", this document last saved against ").append(savedAgainstVersion);
                }
            }
            if (slot.template == Template.CONFIGURATION) {
                status.append(". Asked at every Configure step, parser and transform alike");
            }
            status.append('.');
            slot.status.setText(status.toString());
            slot.text.setTitle("Variables: " + (slot.template.getVariables().length == 0
                    ? "none"
                    : String.join(", ", variables(slot.template))));
        }
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
        copyStep.setVisible(stepShown);
        deleteStep.setVisible(stepShown);
        moveUp.setVisible(stepShown);
        moveDown.setVisible(stepShown);
        copyStep.setEnabled(enabled);
        deleteStep.setEnabled(enabled);
        moveUp.setEnabled(enabled);
        moveDown.setEnabled(enabled);
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        this.readOnly = readOnly;
        flowText.setReadOnly(readOnly);
        instructions.setEnabled(!readOnly);
        maxAttempts.setEnabled(!readOnly);
        attemptBudgetMs.setEnabled(!readOnly);
        learningMode.setEnabled(!readOnly);
        maxDeferralAgeMs.setEnabled(!readOnly);
        tokenBudget.setEnabled(!readOnly);
        sampleSizeLimit.setEnabled(!readOnly);
        showTemplates();
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

    @UiHandler("instructions")
    public void onInstructions(final ValueChangeEvent<String> event) {
        fireChange();
    }

    @UiHandler("maxAttempts")
    public void onMaxAttempts(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("attemptBudgetMs")
    public void onAttemptBudgetMs(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("learningMode")
    public void onLearningMode(final ValueChangeEvent<LearningMode> event) {
        fireChange();
    }

    @UiHandler("maxDeferralAgeMs")
    public void onMaxDeferralAgeMs(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("tokenBudget")
    public void onTokenBudget(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("sampleSizeLimit")
    public void onSampleSizeLimit(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("flowText")
    public void onFlowText(final ValueChangeEvent<String> event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onFlowText(event.getValue() == null
                    ? ""
                    : event.getValue());
        }
    }

    /// One text edited in place: words equal to the built-in are no change, and the document keeps
    /// following the built-in.
    private void textEdited(final Template template, final String text) {
        if (text == null || text.equals(builtIns.get(template))) {
            overrides.remove(template);
        } else {
            overrides.put(template, text);
        }
        showTemplates();
        fireChange();
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, ShapeshifterAiLearnViewImpl> {

    }


    // --------------------------------------------------------------------------------


    /// One text the selected node is asked with: its name, where its words come from, the words
    /// themselves to edit, and a button to load a built-in's in their place.
    private final class TextSlot {

        private final Template template;
        private final FlowPanel widget = new FlowPanel();
        private final Label status = new Label();
        private final TextArea text = new TextArea();
        private final Button load = new Button();

        private TextSlot(final Template template) {
            this.template = template;
            widget.addStyleName("flow-text-slot");
            final Label heading = new Label(template.getDisplayValue());
            heading.addStyleName("flow-heading");
            status.addStyleName("flow-note");
            text.addStyleName("templateText flow-slot-text");
            text.setWidth("100%");
            text.setHeight("180px");
            text.addValueChangeHandler(event -> textEdited(template, event.getValue()));
            load.setText("Load built-in");
            load.addStyleName("allow-focus");
            load.addClickHandler(event -> {
                if (getUiHandlers() != null) {
                    getUiHandlers().onLoadBuiltIn(template);
                }
            });
            widget.add(heading);
            widget.add(status);
            widget.add(text);
            widget.add(load);
        }
    }
}
