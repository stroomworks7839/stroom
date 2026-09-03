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

package stroom.shapeshifter.pipeline;

import stroom.docstore.api.DocumentStoreBinder;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.util.entityevent.EntityEvent;
import stroom.util.guice.GuiceUtil;
import stroom.util.guice.RestResourcesBinder;
import stroom.util.shared.Clearable;

import com.google.inject.AbstractModule;

/**
 * The document type, its REST resource and the compiled-configuration pool. The pipeline element
 * itself is bound by {@link ShapeshifterPipelineElementModule}, because element modules are
 * installed separately from service modules in every Stroom assembly.
 */
public class ShapeshifterModule extends AbstractModule {

    @Override
    protected void configure() {
        DocumentStoreBinder.create(binder())
                .bind(ShapeshifterDoc.TYPE, ShapeshifterStore.class, ShapeshifterStoreImpl.class);
        RestResourcesBinder.create(binder())
                .bind(ShapeshifterResourceImpl.class);

        bind(ShapeshifterParserFactoryPool.class).to(ShapeshifterParserFactoryPoolImpl.class);
        GuiceUtil.buildMultiBinder(binder(), Clearable.class)
                .addBinding(ShapeshifterParserFactoryPoolImpl.class);
        GuiceUtil.buildMultiBinder(binder(), EntityEvent.Handler.class)
                .addBinding(ShapeshifterParserFactoryPoolImpl.class);
    }
}
