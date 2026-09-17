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

import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.ShapeshifterAiLearningPresenter.ShapeshifterAiLearningView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiSettingsUiHandlers;
import stroom.shapeshifter.shared.SampleRedaction;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.ArrayList;
import java.util.List;

public class ShapeshifterAiLearningViewImpl
        extends ViewWithUiHandlers<ShapeshifterAiSettingsUiHandlers>
        implements ShapeshifterAiLearningView, ReadOnlyChangeHandler {

    private static final int PERCENT = 100;

    private final Widget widget;

    @UiField
    SimplePanel model;
    @UiField
    SimplePanel learningKey;
    @UiField
    ValueSpinner relearnThreshold;
    @UiField
    TextArea allowedElements;
    @UiField
    TextArea instructions;
    @UiField
    ValueSpinner maxAttempts;
    @UiField
    ValueSpinner attemptBudgetMs;
    @UiField
    ValueSpinner tokenBudget;
    @UiField
    SelectionBox<SampleRedaction> sampleRedaction;
    @UiField
    ValueSpinner sampleSizeLimit;

    @Inject
    public ShapeshifterAiLearningViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        sampleRedaction.addItems(SampleRedaction.values());
        bound(relearnThreshold, 0, PERCENT);
        bound(maxAttempts, 1, Integer.MAX_VALUE);
        bound(attemptBudgetMs, 1, Long.MAX_VALUE);
        bound(tokenBudget, 0, Long.MAX_VALUE);
        bound(sampleSizeLimit, 1, Integer.MAX_VALUE);
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
    public void setModelView(final View view) {
        model.setWidget(view.asWidget());
    }

    @Override
    public void setLearningKeyView(final View view) {
        learningKey.setWidget(view.asWidget());
    }

    @Override
    public double getRelearnThreshold() {
        return relearnThreshold.getIntValue() / (double) PERCENT;
    }

    @Override
    public void setRelearnThreshold(final double relearnThreshold) {
        // Shown as a whole percentage because there is no double-valued spinner, as the Promotion tab does.
        this.relearnThreshold.setValue((int) Math.round(relearnThreshold * PERCENT));
    }

    @Override
    public List<String> getAllowedElements() {
        return lines(allowedElements);
    }

    @Override
    public void setAllowedElements(final List<String> allowedElements) {
        this.allowedElements.setValue(String.join("\n", allowedElements));
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
    public long getAttemptBudgetMs() {
        return attemptBudgetMs.getValue();
    }

    @Override
    public void setAttemptBudgetMs(final long attemptBudgetMs) {
        this.attemptBudgetMs.setValue(attemptBudgetMs);
    }

    @Override
    public Long getTokenBudget() {
        final Long value = tokenBudget.getValue();
        return value == null || value == 0L
                ? null
                : value;
    }

    @Override
    public void setTokenBudget(final Long tokenBudget) {
        // The spinner cannot show "unset"; zero stands for unlimited, which is not otherwise a budget.
        this.tokenBudget.setValue(tokenBudget == null
                ? 0L
                : tokenBudget);
    }

    @Override
    public SampleRedaction getSampleRedaction() {
        return sampleRedaction.getValue();
    }

    @Override
    public void setSampleRedaction(final SampleRedaction sampleRedaction) {
        this.sampleRedaction.setValue(sampleRedaction);
    }

    @Override
    public int getSampleSizeLimit() {
        return sampleSizeLimit.getIntValue();
    }

    @Override
    public void setSampleSizeLimit(final int sampleSizeLimit) {
        this.sampleSizeLimit.setValue(sampleSizeLimit);
    }

    /**
     * The list fields are one entry per line, trimmed, blank lines dropped.
     */
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

    @Override
    public void onReadOnly(final boolean readOnly) {
        final boolean enabled = !readOnly;
        relearnThreshold.setEnabled(enabled);
        allowedElements.setEnabled(enabled);
        instructions.setEnabled(enabled);
        maxAttempts.setEnabled(enabled);
        attemptBudgetMs.setEnabled(enabled);
        tokenBudget.setEnabled(enabled);
        sampleRedaction.setEnabled(enabled);
        sampleSizeLimit.setEnabled(enabled);
    }

    private void fireChange() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    @UiHandler("relearnThreshold")
    public void onRelearnThreshold(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("allowedElements")
    public void onAllowedElements(final ValueChangeEvent<String> event) {
        fireChange();
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

    @UiHandler("tokenBudget")
    public void onTokenBudget(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("sampleRedaction")
    public void onSampleRedaction(final ValueChangeEvent<SampleRedaction> event) {
        fireChange();
    }

    @UiHandler("sampleSizeLimit")
    public void onSampleSizeLimit(final ValueChangeEvent<Long> event) {
        fireChange();
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, ShapeshifterAiLearningViewImpl> {

    }
}
