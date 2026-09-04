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

import stroom.cache.api.CacheManager;
import stroom.pipeline.cache.AbstractDocPool;
import stroom.pipeline.cache.DocumentPermissionCache;
import stroom.pipeline.cache.StoredParserFactory;
import stroom.pipeline.errorhandler.StoredErrorReceiver;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventHandler;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.xml.ParserConfig;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@EntityEventHandler(
        type = ShapeshifterDoc.TYPE,
        action = {EntityAction.DELETE, EntityAction.UPDATE, EntityAction.CLEAR_CACHE})
class ShapeshifterParserFactoryPoolImpl
        extends AbstractDocPool<ShapeshifterDoc, StoredParserFactory>
        implements ShapeshifterParserFactoryPool, EntityEvent.Handler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShapeshifterParserFactoryPoolImpl.class);
    private static final ElementId ELEMENT_ID = new ElementId(ShapeshifterParserFactoryPool.class.getSimpleName());

    private final StroomFunctionLibrary functions;

    @Inject
    ShapeshifterParserFactoryPoolImpl(final CacheManager cacheManager,
                                      final Provider<ParserConfig> parserConfigProvider,
                                      final DocumentPermissionCache documentPermissionCache,
                                      final SecurityContext securityContext,
                                      final StroomFunctionLibrary functions) {
        // The same cache configuration as DS3's parser factories: one knob for all parser pools.
        super(cacheManager,
                "Shapeshifter Parser Factory Pool",
                () -> parserConfigProvider.get().getCacheConfig(),
                documentPermissionCache,
                securityContext);
        this.functions = functions;
    }

    @Override
    protected StoredParserFactory createValue(final ShapeshifterDoc doc) {
        LOGGER.debug("Compiling Shapeshifter configuration: {}", doc);
        final StoredErrorReceiver errorReceiver = new StoredErrorReceiver();
        ShapeshifterParserFactory factory = null;
        try {
            final Project project = ProjectReader.read(doc.getData() == null ? "" : doc.getData());
            factory = new ShapeshifterParserFactory(project, functions.registry());
        } catch (final RuntimeException e) {
            // A configuration that will not read or compile is a fatal error against the document,
            // reported once here and replayed by every element that borrows it.
            LOGGER.debug(e.getMessage(), e);
            errorReceiver.log(Severity.FATAL_ERROR, null, ELEMENT_ID, e.getMessage(), e);
        }
        return new StoredParserFactory(factory, errorReceiver);
    }
}
