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

package stroom.shapeshifter.client;

import stroom.core.client.ContentManager;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.docstore.shared.DocRefUtil;
import stroom.document.client.DocumentPlugin;
import stroom.document.client.DocumentPluginEventManager;
import stroom.entity.client.presenter.DocPresenter;
import stroom.pipeline.stepping.client.presenter.ElementStepDetailsPresenterRegistry;
import stroom.security.client.api.ClientSecurityContext;
import stroom.shapeshifter.client.presenter.ShapeshifterAiPresenter;
import stroom.shapeshifter.client.presenter.ShapeshifterAiStepPresenter;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiElements;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.task.client.TaskMonitorFactory;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;
import javax.inject.Singleton;

@Singleton
public class ShapeshifterAiPlugin extends DocumentPlugin<ShapeshifterAiDoc> {

    private static final ShapeshifterAiResource RESOURCE = GWT.create(ShapeshifterAiResource.class);

    private final Provider<ShapeshifterAiPresenter> editorProvider;
    private final RestFactory restFactory;

    @Inject
    public ShapeshifterAiPlugin(final EventBus eventBus,
                                   final Provider<ShapeshifterAiPresenter> editorProvider,
                                   final RestFactory restFactory,
                                   final ContentManager contentManager,
                                   final DocumentPluginEventManager entityPluginEventManager,
                                   final ClientSecurityContext securityContext,
                                   final ElementStepDetailsPresenterRegistry stepDetailsRegistry,
                                   final Provider<ShapeshifterAiStepPresenter> stepPresenterProvider) {
        super(eventBus, contentManager, entityPluginEventManager, securityContext);
        this.editorProvider = editorProvider;
        this.restFactory = restFactory;
        // The stepper shows a supervised stage's decision where an element's code pane would be (A30).
        // It knows nothing about this feature: the feature says which of its elements have a pane and
        // what shows it, and does so here because the plugin is where the feature meets the client.
        // Both shapes of the stage, because both have a decision to explain (§12 item 4).
        stepDetailsRegistry.register(ShapeshifterAiElements.PARSER, stepPresenterProvider::get);
        stepDetailsRegistry.register(ShapeshifterAiElements.FILTER, stepPresenterProvider::get);
    }

    @Override
    protected DocPresenter<?, ?> createEditor() {
        return editorProvider.get();
    }

    @Override
    public void load(final DocRef docRef,
                     final Consumer<ShapeshifterAiDoc> resultConsumer,
                     final RestErrorHandler errorHandler,
                     final TaskMonitorFactory taskMonitorFactory) {
        restFactory
                .create(RESOURCE)
                .method(res -> res.fetch(docRef.getUuid()))
                .onSuccess(resultConsumer)
                .onFailure(errorHandler)
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }

    @Override
    public void save(final DocRef docRef,
                     final ShapeshifterAiDoc document,
                     final Consumer<ShapeshifterAiDoc> resultConsumer,
                     final RestErrorHandler errorHandler,
                     final TaskMonitorFactory taskMonitorFactory) {
        restFactory
                .create(RESOURCE)
                .method(res -> res.update(document.getUuid(), document))
                .onSuccess(resultConsumer)
                .onFailure(errorHandler)
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }

    @Override
    public String getType() {
        return ShapeshifterAiDoc.TYPE;
    }

    @Override
    protected DocRef getDocRef(final ShapeshifterAiDoc document) {
        return DocRefUtil.create(document);
    }
}
