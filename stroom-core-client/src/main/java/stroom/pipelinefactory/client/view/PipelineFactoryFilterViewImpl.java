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

package stroom.pipelinefactory.client.view;

import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.item.client.SelectionBox;
import stroom.pipelinefactory.client.presenter.PipelineFactoryFilterPresenter.PipelineFactoryFilterView;
import stroom.pipelinefactory.client.presenter.PipelineFactoryUiHandlers;
import stroom.preferences.client.UserPreferencesManager;
import stroom.processor.shared.ProcessorFilter;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;
import stroom.widget.customdatebox.client.DurationPicker;
import stroom.widget.customdatebox.client.MyDateBox;
import stroom.widget.tickbox.client.view.CustomCheckBox;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.Objects;

/// The form for the processor filter a Pipeline Factory gives every pipeline it makes, laid out as the
/// processing screen lays out a filter.
public class PipelineFactoryFilterViewImpl
        extends ViewWithUiHandlers<PipelineFactoryUiHandlers>
        implements PipelineFactoryFilterView, ReadOnlyChangeHandler {

    private static final SimpleDuration DEFAULT_MAX_TASK_CREATION_DELAY = new SimpleDuration(30, TimeUnit.SECONDS);

    private final Widget widget;

    @UiField
    CustomCheckBox filterEnabled;
    @UiField
    ValueSpinner priority;
    @UiField
    CustomCheckBox autoPriority;
    @UiField
    ValueSpinner maxProcessingTasks;
    @UiField
    SelectionBox<String> profile;
    @UiField
    MyDateBox minMetaCreateTimeMs;
    @UiField
    MyDateBox maxMetaCreateTimeMs;
    @UiField
    CustomCheckBox maxTaskCreationDelayEnabled;
    @UiField
    DurationPicker maxTaskCreationDelay;
    @UiField
    CustomCheckBox export;

    @Inject
    public PipelineFactoryFilterViewImpl(final Binder binder, final UserPreferencesManager userPreferencesManager) {
        widget = binder.createAndBindUi(this);
        minMetaCreateTimeMs.setUtc(userPreferencesManager.isUtc());
        maxMetaCreateTimeMs.setUtc(userPreferencesManager.isUtc());
        priority.setMin(ProcessorFilter.MIN_PRIORITY);
        priority.setMax(ProcessorFilter.MAX_PRIORITY);
        // Zero means no limit, so it must stay reachable.
        maxProcessingTasks.setMin(ProcessorFilter.MIN_MAX_PROCESSING_TASKS);
        maxProcessingTasks.setMax(ProcessorFilter.MAX_MAX_PROCESSING_TASKS);
        maxTaskCreationDelay.setValue(DEFAULT_MAX_TASK_CREATION_DELAY);
        updateEnabled();
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public boolean isFilterEnabled() {
        return filterEnabled.getValue();
    }

    @Override
    public void setFilterEnabled(final boolean enabled) {
        filterEnabled.setValue(enabled);
    }

    @Override
    public int getPriority() {
        return priority.getIntValue();
    }

    @Override
    public void setPriority(final int priority) {
        this.priority.setValue(priority);
    }

    @Override
    public boolean isAutoPriority() {
        return autoPriority.getValue();
    }

    @Override
    public void setAutoPriority(final boolean autoPriority) {
        this.autoPriority.setValue(autoPriority);
    }

    @Override
    public int getMaxProcessingTasks() {
        return maxProcessingTasks.getIntValue();
    }

    @Override
    public void setMaxProcessingTasks(final int maxProcessingTasks) {
        this.maxProcessingTasks.setValue(maxProcessingTasks);
    }

    @Override
    public SelectionBox<String> getProfile() {
        return profile;
    }

    @Override
    public Long getMinMetaCreateTimeMs() {
        return minMetaCreateTimeMs.getMilliseconds();
    }

    @Override
    public void setMinMetaCreateTimeMs(final Long minMetaCreateTimeMs) {
        this.minMetaCreateTimeMs.setMilliseconds(minMetaCreateTimeMs);
    }

    @Override
    public Long getMaxMetaCreateTimeMs() {
        return maxMetaCreateTimeMs.getMilliseconds();
    }

    @Override
    public void setMaxMetaCreateTimeMs(final Long maxMetaCreateTimeMs) {
        this.maxMetaCreateTimeMs.setMilliseconds(maxMetaCreateTimeMs);
    }

    @Override
    public SimpleDuration getMaxTaskCreationDelay() {
        return maxTaskCreationDelayEnabled.getValue()
                ? maxTaskCreationDelay.getValue()
                : null;
    }

    @Override
    public void setMaxTaskCreationDelay(final SimpleDuration maxTaskCreationDelay) {
        // None means the filter takes the cluster's limit.
        maxTaskCreationDelayEnabled.setValue(maxTaskCreationDelay != null);
        this.maxTaskCreationDelay.setValue(Objects.requireNonNullElse(maxTaskCreationDelay,
                DEFAULT_MAX_TASK_CREATION_DELAY));
        updateEnabled();
    }

    @Override
    public boolean isExport() {
        return export.getValue();
    }

    @Override
    public void setExport(final boolean export) {
        this.export.setValue(export);
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        final boolean editable = !readOnly;
        filterEnabled.setEnabled(editable);
        priority.setEnabled(editable);
        autoPriority.setEnabled(editable);
        maxProcessingTasks.setEnabled(editable);
        profile.setEnabled(editable);
        minMetaCreateTimeMs.setEnabled(editable);
        maxMetaCreateTimeMs.setEnabled(editable);
        maxTaskCreationDelayEnabled.setEnabled(editable);
        export.setEnabled(editable);
        updateEnabled();
    }

    private void updateEnabled() {
        maxTaskCreationDelay.setEnabled(maxTaskCreationDelayEnabled.isEnabled()
                                        && maxTaskCreationDelayEnabled.getValue());
    }

    private void fireChange() {
        if (getUiHandlers() != null) {
            getUiHandlers().onSettingChange();
        }
    }

    @UiHandler({"filterEnabled", "autoPriority", "export"})
    public void onTick(final ValueChangeEvent<Boolean> event) {
        fireChange();
    }

    @UiHandler("maxTaskCreationDelayEnabled")
    public void onMaxTaskCreationDelayEnabled(final ValueChangeEvent<Boolean> event) {
        updateEnabled();
        fireChange();
    }

    @UiHandler({"priority", "maxProcessingTasks"})
    public void onSpinner(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("profile")
    public void onProfile(final ValueChangeEvent<String> event) {
        fireChange();
    }

    @UiHandler({"minMetaCreateTimeMs", "maxMetaCreateTimeMs"})
    public void onCreateTime(final ValueChangeEvent<String> event) {
        fireChange();
    }

    @UiHandler("maxTaskCreationDelay")
    public void onMaxTaskCreationDelay(final ValueChangeEvent<SimpleDuration> event) {
        fireChange();
    }


    // --------------------------------------------------------------------------------


    /// Binds the form's layout.
    public interface Binder extends UiBinder<Widget, PipelineFactoryFilterViewImpl> {

    }
}
