/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client;

import stroom.core.client.ContentManager;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.docstore.shared.DocRefUtil;
import stroom.document.client.DocumentPlugin;
import stroom.document.client.DocumentPluginEventManager;
import stroom.entity.client.presenter.DocPresenter;
import stroom.security.client.api.ClientSecurityContext;
import stroom.shapeshifter.client.presenter.ShapeshifterPresenter;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.task.client.TaskMonitorFactory;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;
import javax.inject.Singleton;

/**
 * The Shapeshifter document in the client: open, load, save and create through the resource.
 * Registering it is also what makes a Shapeshifter element steppable — stepping resolves an
 * element's document through the plugin registry by type (design 43 §1).
 */
@Singleton
public class ShapeshifterPlugin extends DocumentPlugin<ShapeshifterDoc> {

    private static final ShapeshifterResource SHAPESHIFTER_RESOURCE = GWT.create(ShapeshifterResource.class);

    private final Provider<ShapeshifterPresenter> editorProvider;
    private final RestFactory restFactory;

    @Inject
    public ShapeshifterPlugin(final EventBus eventBus,
                              final Provider<ShapeshifterPresenter> editorProvider,
                              final RestFactory restFactory,
                              final ContentManager contentManager,
                              final DocumentPluginEventManager entityPluginEventManager,
                              final ClientSecurityContext securityContext) {
        super(eventBus, contentManager, entityPluginEventManager, securityContext);
        this.editorProvider = editorProvider;
        this.restFactory = restFactory;
    }

    @Override
    protected DocPresenter<?, ?> createEditor() {
        return editorProvider.get();
    }

    @Override
    public void load(final DocRef docRef,
                     final Consumer<ShapeshifterDoc> resultConsumer,
                     final RestErrorHandler errorHandler,
                     final TaskMonitorFactory taskMonitorFactory) {
        restFactory
                .create(SHAPESHIFTER_RESOURCE)
                .method(res -> res.fetch(docRef.getUuid()))
                .onSuccess(resultConsumer)
                .onFailure(errorHandler)
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }

    @Override
    public void save(final DocRef docRef,
                     final ShapeshifterDoc document,
                     final Consumer<ShapeshifterDoc> resultConsumer,
                     final RestErrorHandler errorHandler,
                     final TaskMonitorFactory taskMonitorFactory) {
        restFactory
                .create(SHAPESHIFTER_RESOURCE)
                .method(res -> res.update(document.getUuid(), document))
                .onSuccess(resultConsumer)
                .onFailure(errorHandler)
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }

    @Override
    public void create(final String documentName,
                       final Consumer<ShapeshifterDoc> resultConsumer,
                       final RestErrorHandler errorHandler,
                       final TaskMonitorFactory taskMonitorFactory) {
        restFactory
                .create(SHAPESHIFTER_RESOURCE)
                .method(res -> res.create(documentName))
                .onSuccess(resultConsumer)
                .onFailure(errorHandler)
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }

    @Override
    public String getType() {
        return ShapeshifterDoc.TYPE;
    }

    @Override
    protected DocRef getDocRef(final ShapeshifterDoc document) {
        return DocRefUtil.create(document);
    }
}
