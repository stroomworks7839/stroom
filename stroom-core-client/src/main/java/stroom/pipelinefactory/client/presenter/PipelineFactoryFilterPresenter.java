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

package stroom.pipelinefactory.client.presenter;

import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.item.client.SelectionBox;
import stroom.pipelinefactory.client.presenter.PipelineFactoryFilterPresenter.PipelineFactoryFilterView;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.pipelinefactory.shared.PipelineFactoryFilterSettings;
import stroom.processor.client.presenter.ProcessorProfileClient;
import stroom.processor.client.presenter.ProcessorProfileListModel;
import stroom.util.shared.time.SimpleDuration;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

/// The processor filter a Pipeline Factory gives every pipeline it makes (design 06 §3.1, A108): every
/// filter setting but the pipeline, what it matches and who it runs as, each meaning what it means in
/// the processing screen.
public class PipelineFactoryFilterPresenter
        extends DocPresenter<PipelineFactoryFilterView, PipelineFactoryDoc>
        implements PipelineFactoryUiHandlers {

    @Inject
    public PipelineFactoryFilterPresenter(final EventBus eventBus,
                                          final PipelineFactoryFilterView view,
                                          final ProcessorProfileClient processorProfileClient) {
        super(eventBus, view);
        view.setUiHandlers(this);
        final ProcessorProfileListModel profiles = new ProcessorProfileListModel(eventBus, processorProfileClient);
        profiles.setTaskMonitorFactory(this);
        view.getProfile().setModel(profiles);
    }

    @Override
    protected void onRead(final DocRef docRef, final PipelineFactoryDoc doc, final boolean readOnly) {
        final PipelineFactoryFilterSettings settings = doc.getFilterSettings();
        final PipelineFactoryFilterView view = getView();
        view.setFilterEnabled(settings.isEnabled());
        view.setPriority(settings.getPriority());
        view.setAutoPriority(settings.isAutoPriority());
        view.setMaxProcessingTasks(settings.getMaxProcessingTasks());
        view.getProfile().setValue(settings.getProfileName());
        view.setMinMetaCreateTimeMs(settings.getMinMetaCreateTimeMs());
        view.setMaxMetaCreateTimeMs(settings.getMaxMetaCreateTimeMs());
        view.setMaxTaskCreationDelay(settings.getMaxTaskCreationDelay());
        view.setExport(settings.isExport());
    }

    @Override
    protected PipelineFactoryDoc onWrite(final PipelineFactoryDoc doc) {
        final PipelineFactoryFilterView view = getView();
        return doc.copy()
                .filterSettings(PipelineFactoryFilterSettings.builder()
                        .enabled(view.isFilterEnabled())
                        .priority(view.getPriority())
                        .autoPriority(view.isAutoPriority())
                        .maxProcessingTasks(view.getMaxProcessingTasks())
                        .profileName(view.getProfile().getValue())
                        .minMetaCreateTimeMs(view.getMinMetaCreateTimeMs())
                        .maxMetaCreateTimeMs(view.getMaxMetaCreateTimeMs())
                        .maxTaskCreationDelay(view.getMaxTaskCreationDelay())
                        .export(view.isExport())
                        .build())
                .build();
    }

    @Override
    public void onSettingChange() {
        onChange();
    }


    // --------------------------------------------------------------------------------


    /// The filter settings form.
    public interface PipelineFactoryFilterView
            extends View, ReadOnlyChangeHandler, HasUiHandlers<PipelineFactoryUiHandlers> {

        /// @return Whether a made filter is enabled.
        boolean isFilterEnabled();

        /// @param enabled Whether a made filter is enabled.
        void setFilterEnabled(boolean enabled);

        /// @return A made filter's priority.
        int getPriority();

        /// @param priority A made filter's priority.
        void setPriority(int priority);

        /// @return Whether a made filter's priority is worked out.
        boolean isAutoPriority();

        /// @param autoPriority Whether a made filter's priority is worked out.
        void setAutoPriority(boolean autoPriority);

        /// @return The most tasks a made filter may have processing; 0 for no limit.
        int getMaxProcessingTasks();

        /// @param maxProcessingTasks The most tasks a made filter may have processing.
        void setMaxProcessingTasks(int maxProcessingTasks);

        /// @return The processing profile chooser.
        SelectionBox<String> getProfile();

        /// @return The earliest create time a made filter takes; null for none.
        Long getMinMetaCreateTimeMs();

        /// @param minMetaCreateTimeMs The earliest create time a made filter takes.
        void setMinMetaCreateTimeMs(Long minMetaCreateTimeMs);

        /// @return The latest create time a made filter takes; null for none.
        Long getMaxMetaCreateTimeMs();

        /// @param maxMetaCreateTimeMs The latest create time a made filter takes.
        void setMaxMetaCreateTimeMs(Long maxMetaCreateTimeMs);

        /// @return How long a made filter may wait to create tasks; null for the cluster's limit.
        SimpleDuration getMaxTaskCreationDelay();

        /// @param maxTaskCreationDelay How long a made filter may wait to create tasks.
        void setMaxTaskCreationDelay(SimpleDuration maxTaskCreationDelay);

        /// @return Whether a made filter is exported with its pipeline.
        boolean isExport();

        /// @param export Whether a made filter is exported with its pipeline.
        void setExport(boolean export);
    }
}
