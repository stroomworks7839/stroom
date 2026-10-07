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

package stroom.pipelinefactory.client.gin;

import stroom.core.client.gin.PluginModule;
import stroom.pipelinefactory.client.PipelineFactoryPlugin;
import stroom.pipelinefactory.client.presenter.PipelineFactoryFilterPresenter;
import stroom.pipelinefactory.client.presenter.PipelineFactoryFilterPresenter.PipelineFactoryFilterView;
import stroom.pipelinefactory.client.presenter.PipelineFactoryMadePresenter;
import stroom.pipelinefactory.client.presenter.PipelineFactoryPresenter;
import stroom.pipelinefactory.client.presenter.PipelineFactoryPropertyListPresenter;
import stroom.pipelinefactory.client.presenter.PipelineFactoryPropertyPresenter;
import stroom.pipelinefactory.client.presenter.PipelineFactoryPropertyPresenter.PipelineFactoryPropertyView;
import stroom.pipelinefactory.client.presenter.PipelineFactorySettingsPresenter;
import stroom.pipelinefactory.client.presenter.PipelineFactorySettingsPresenter.PipelineFactorySettingsView;
import stroom.pipelinefactory.client.view.PipelineFactoryFilterViewImpl;
import stroom.pipelinefactory.client.view.PipelineFactoryPropertyViewImpl;
import stroom.pipelinefactory.client.view.PipelineFactorySettingsViewImpl;

/// Binds the Pipeline Factory document's screens (design 06 §3.5).
public class PipelineFactoryModule extends PluginModule {

    @Override
    protected void configure() {
        bindPlugin(PipelineFactoryPlugin.class);
        bind(PipelineFactoryPresenter.class);
        bindPresenterWidget(PipelineFactorySettingsPresenter.class,
                PipelineFactorySettingsView.class,
                PipelineFactorySettingsViewImpl.class);
        bindPresenterWidget(PipelineFactoryFilterPresenter.class,
                PipelineFactoryFilterView.class,
                PipelineFactoryFilterViewImpl.class);
        bindPresenterWidget(PipelineFactoryPropertyPresenter.class,
                PipelineFactoryPropertyView.class,
                PipelineFactoryPropertyViewImpl.class);
        bind(PipelineFactoryPropertyListPresenter.class);
        bind(PipelineFactoryMadePresenter.class);
    }
}
