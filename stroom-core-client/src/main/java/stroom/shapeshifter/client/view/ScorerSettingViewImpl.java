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

package stroom.shapeshifter.client.view;

import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.ScorerSettingPresenter.ScorerSettingView;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.util.shared.Severity;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.logical.shared.HasValueChangeHandlers;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.DeckPanel;
import com.google.gwt.user.client.ui.DoubleBox;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.ArrayList;
import java.util.List;

public class ScorerSettingViewImpl extends ViewImpl implements ScorerSettingView {

    /**
     * The order of the panels in the deck; a type without parameters shows the first, empty one.
     */
    private static final int NONE = 0;
    private static final int YIELD = 1;
    private static final int SCHEMA_CONFORMANCE = 2;
    private static final int EXTRACTION_QUALITY = 3;
    private static final int BUSINESS_RULES = 4;
    private static final int ERROR_LOAD = 5;
    private static final int EVENT_CLASSIFICATION = 6;

    private final Widget widget;

    @UiField
    SelectionBox<ScorerType> type;
    @UiField
    DoubleBox weight;
    @UiField
    DoubleBox threshold;
    @UiField
    CustomCheckBox gate;
    @UiField
    DeckPanel parameters;
    @UiField
    DoubleBox expectedRatio;
    @UiField
    SelectionBox<YieldBasis> basis;
    @UiField
    TextBox schemaGroup;
    @UiField
    CustomCheckBox allowUnknownEventDetail;
    @UiField
    TextArea requiredFields;
    @UiField
    SimplePanel assertions;
    @UiField
    CustomCheckBox includeTransformMessages;
    @UiField
    SelectionBox<Severity> minimumSeverity;
    @UiField
    TextArea recognisedTypes;

    @Inject
    public ScorerSettingViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        type.addItems(ScorerType.values());
        basis.addItems(YieldBasis.values());
        minimumSeverity.addItems(List.of(Severity.WARNING, Severity.ERROR, Severity.FATAL_ERROR));
        parameters.showWidget(NONE);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public HasValueChangeHandlers<ScorerType> getTypeBox() {
        return type;
    }

    @Override
    public ScorerType getType() {
        return type.getValue();
    }

    @Override
    public void setType(final ScorerType type) {
        this.type.setValue(type);
    }

    @Override
    public Double getWeight() {
        return weight.getValue();
    }

    @Override
    public void setWeight(final double weight) {
        this.weight.setValue(weight);
    }

    @Override
    public Double getThreshold() {
        return threshold.getValue();
    }

    @Override
    public void setThreshold(final double threshold) {
        this.threshold.setValue(threshold);
    }

    @Override
    public boolean isGate() {
        return gate.getValue();
    }

    @Override
    public void setGate(final boolean gate) {
        this.gate.setValue(gate);
    }

    @Override
    public void showParameters(final ScorerType type) {
        parameters.showWidget(switch (type) {
            case COMPILE, INPUT_COVERAGE -> NONE;
            case YIELD -> YIELD;
            case SCHEMA_CONFORMANCE -> SCHEMA_CONFORMANCE;
            case EXTRACTION_QUALITY -> EXTRACTION_QUALITY;
            case BUSINESS_RULES -> BUSINESS_RULES;
            case ERROR_LOAD -> ERROR_LOAD;
            case EVENT_CLASSIFICATION -> EVENT_CLASSIFICATION;
        });
    }

    @Override
    public Double getExpectedRatio() {
        return expectedRatio.getValue();
    }

    @Override
    public void setExpectedRatio(final double expectedRatio) {
        this.expectedRatio.setValue(expectedRatio);
    }

    @Override
    public YieldBasis getBasis() {
        return basis.getValue();
    }

    @Override
    public void setBasis(final YieldBasis basis) {
        this.basis.setValue(basis);
    }

    @Override
    public String getSchemaGroup() {
        return schemaGroup.getValue();
    }

    @Override
    public void setSchemaGroup(final String schemaGroup) {
        this.schemaGroup.setValue(schemaGroup);
    }

    @Override
    public boolean isAllowUnknownEventDetail() {
        return allowUnknownEventDetail.getValue();
    }

    @Override
    public void setAllowUnknownEventDetail(final boolean allowUnknownEventDetail) {
        this.allowUnknownEventDetail.setValue(allowUnknownEventDetail);
    }

    @Override
    public List<String> getRequiredFields() {
        return lines(requiredFields);
    }

    @Override
    public void setRequiredFields(final List<String> requiredFields) {
        this.requiredFields.setValue(String.join("\n", requiredFields));
    }

    @Override
    public void setAssertionsView(final View view) {
        assertions.setWidget(view.asWidget());
    }

    @Override
    public boolean isIncludeTransformMessages() {
        return includeTransformMessages.getValue();
    }

    @Override
    public void setIncludeTransformMessages(final boolean includeTransformMessages) {
        this.includeTransformMessages.setValue(includeTransformMessages);
    }

    @Override
    public Severity getMinimumSeverity() {
        return minimumSeverity.getValue();
    }

    @Override
    public void setMinimumSeverity(final Severity minimumSeverity) {
        this.minimumSeverity.setValue(minimumSeverity);
    }

    @Override
    public List<String> getRecognisedTypes() {
        return lines(recognisedTypes);
    }

    @Override
    public void setRecognisedTypes(final List<String> recognisedTypes) {
        this.recognisedTypes.setValue(String.join("\n", recognisedTypes));
    }

    private static List<String> lines(final TextArea textArea) {
        final List<String> lines = new ArrayList<>();
        for (final String line : textArea.getValue().split("\n")) {
            final String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, ScorerSettingViewImpl> {

    }
}
