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

package stroom.pipelinefactory.impl;

import stroom.docstore.api.DocumentStoreBinder;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.util.guice.RestResourcesBinder;

import com.google.inject.AbstractModule;

/// Binds the Pipeline Factory document's store, its handlers and its resource (design 06, phase I).
public class PipelineFactoryModule extends AbstractModule {

    @Override
    protected void configure() {
        DocumentStoreBinder.create(binder())
                .bind(PipelineFactoryDoc.TYPE, PipelineFactoryStore.class, PipelineFactoryStoreImpl.class);

        RestResourcesBinder.create(binder())
                .bind(PipelineFactoryResourceImpl.class);
    }
}
