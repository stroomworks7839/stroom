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
import java.util.function.Consumer;

public class ShapeshifterAiFlowViewImpl
        extends ViewWithUiHandlers<ShapeshifterAiSettingsUiHandlers>
        implements ShapeshifterAiFlowView, ReadOnlyChangeHandler {

    private final Widget widget;
    private final FlowChart chart = new FlowChart();
    @UiField
    SimplePanel chartPanel;
    @UiField
    Label flowProblem;
    @UiField
    SimplePanel flowSteps;
    /// Loading an example replaces the steps, so it is a button beneath the table and not a picker
    /// above it: what it does is done once, and nothing about it describes the flow afterwards.
    @UiField
    Button loadExample;
    /// The same flow in the grammar the harness and import/export carry, two-way (A87).
    @UiField
    TextArea flowText;
    @UiField
    SelectionBox<Template> template;
    @UiField
    TextArea templateText;
    @UiField
    Button resetTemplate;
    @UiField
    Label templateStatus;

    /// This document's changed texts, by template; and the built-ins the node serves, shown where the
    /// document has not changed one.
    private final Map<Template, String> overrides = new HashMap<>();
    private Map<Template, String> builtIns = new HashMap<>();
    private Integer builtInVersion;
    private Integer savedAgainstVersion;
    private List<FlowStep> steps = new ArrayList<>();
    private List<FlowProblem> problems = new ArrayList<>();
    private Integer selectedStep;

    @Inject
    public ShapeshifterAiFlowViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        chartPanel.setWidget(chart);
        template.addItems(Template.values());
        template.setValue(Template.CHAIN);
        showTemplate();
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void showChart(final List<FlowStep> steps, final List<FlowProblem> problems) {
        this.steps = new ArrayList<>(steps);
        this.problems = new ArrayList<>(problems);
        if (selectedStep != null && selectedStep >= steps.size()) {
            selectedStep = null;
        }
        chart.show(this.steps, this.problems, null, selectedStep);
    }

    @Override
    public void setSelectedStep(final Integer index) {
        selectedStep = index == null || index < 0
                ? null
                : index;
        chart.setSelected(selectedStep);
    }

    @Override
    public void setOnStepSelected(final Consumer<Integer> onSelected) {
        chart.setOnSelect(onSelected);
    }

    /// What is wrong with the steps, beneath the chart: `onWrite` runs on every dirty check, so a fault
    /// here must not be a modal.
    @Override
    public void setFlowProblem(final String problem) {
        flowProblem.setText(problem == null
                ? ""
                : problem);
        flowProblem.setVisible(problem != null);
    }

    @Override
    public void setFlowStepsView(final View view) {
        flowSteps.setWidget(view.asWidget());
    }

    @Override
    public void setFlowText(final String text) {
        flowText.setValue(text);
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

    /// The selected template as this document uses it, and where its text comes from.
    private void showTemplate() {
        final Template selected = template.getValue();
        if (selected == null) {
            return;
        }
        final String override = overrides.get(selected);
        templateText.setValue(override != null
                ? override
                : builtIns.getOrDefault(selected, ""));
        resetTemplate.setEnabled(override != null && !templateText.isReadOnly());
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
        templateStatus.setText(status.toString());
    }

    private static List<String> variables(final Template template) {
        final List<String> names = new ArrayList<>();
        for (final String variable : template.getVariables()) {
            names.add("${" + variable + "}");
        }
        return names;
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        final boolean enabled = !readOnly;
        loadExample.setEnabled(enabled);
        flowText.setReadOnly(readOnly);
        template.setEnabled(enabled);
        templateText.setReadOnly(readOnly);
        resetTemplate.setEnabled(enabled && overrides.containsKey(template.getValue()));
    }

    private void fireChange() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    /// Which example to load is asked here, where the button is; the menu of them, and what replacing a
    /// flow means, belong to whoever owns the step list.
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
