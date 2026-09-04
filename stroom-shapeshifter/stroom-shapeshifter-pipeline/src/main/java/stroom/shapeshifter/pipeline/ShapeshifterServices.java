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

import stroom.data.store.api.AttributeMapFactory;
import stroom.data.store.api.DataService;
import stroom.data.store.api.Store;
import stroom.dictionary.api.WordListProvider;
import stroom.feed.api.FeedProperties;
import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.shared.data.PipelineReference;
import stroom.pipeline.state.CurrentUserHolder;
import stroom.pipeline.state.FeedHolder;
import stroom.pipeline.state.MetaDataHolder;
import stroom.pipeline.state.MetaHolder;
import stroom.pipeline.state.PipelineHolder;
import stroom.pipeline.state.SearchIdHolder;
import stroom.shapeshifter.engine.function.Services;
import stroom.util.io.PathCreator;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;

/**
 * Everything Stroom's context functions are injected with, gathered once so an element can hand
 * a document's run all of it (design 26 §5): the pipeline-scoped holders through their
 * providers, and the services behind meta attributes, parts, feeds and dictionaries. Null
 * where a build has none — a harness outside Stroom — and then absent from the services.
 */
public class ShapeshifterServices {

    private final Provider<MetaHolder> metaHolder;
    private final Provider<MetaDataHolder> metaDataHolder;
    private final Provider<FeedHolder> feedHolder;
    private final Provider<PipelineHolder> pipelineHolder;
    private final Provider<CurrentUserHolder> currentUserHolder;
    private final Provider<SearchIdHolder> searchIdHolder;
    private final Provider<FeedProperties> feedProperties;
    private final Provider<DataService> dataService;
    private final Provider<AttributeMapFactory> attributeMapFactory;
    private final Provider<Store> store;
    private final Provider<WordListProvider> wordListProvider;

    @Inject
    public ShapeshifterServices(final Provider<MetaHolder> metaHolder,
                                final Provider<MetaDataHolder> metaDataHolder,
                                final Provider<FeedHolder> feedHolder,
                                final Provider<PipelineHolder> pipelineHolder,
                                final Provider<CurrentUserHolder> currentUserHolder,
                                final Provider<SearchIdHolder> searchIdHolder,
                                final Provider<FeedProperties> feedProperties,
                                final Provider<DataService> dataService,
                                final Provider<AttributeMapFactory> attributeMapFactory,
                                final Provider<Store> store,
                                final Provider<WordListProvider> wordListProvider) {
        this.metaHolder = metaHolder;
        this.metaDataHolder = metaDataHolder;
        this.feedHolder = feedHolder;
        this.pipelineHolder = pipelineHolder;
        this.currentUserHolder = currentUserHolder;
        this.searchIdHolder = searchIdHolder;
        this.feedProperties = feedProperties;
        this.dataService = dataService;
        this.attributeMapFactory = attributeMapFactory;
        this.store = store;
        this.wordListProvider = wordListProvider;
    }

    /** What one element's documents may reach: the shared services plus the element's own. */
    public Services forElement(final ErrorReceiverProxy errorReceiverProxy,
                               final LocationFactoryProxy locationFactory,
                               final PathCreator pathCreator,
                               final List<PipelineReference> pipelineReferences,
                               final PipelineState state) {
        final ElementServices services = new ElementServices();
        services.put(ErrorReceiverProxy.class, errorReceiverProxy);
        services.put(LocationFactoryProxy.class, locationFactory);
        services.put(PathCreator.class, pathCreator);
        services.put(ElementServices.PipelineReferences.class,
                new ElementServices.PipelineReferences(List.copyOf(pipelineReferences)));
        services.put(PipelineState.class, state);
        services.put(MetaHolder.class, get(metaHolder));
        services.put(MetaDataHolder.class, get(metaDataHolder));
        services.put(FeedHolder.class, get(feedHolder));
        services.put(PipelineHolder.class, get(pipelineHolder));
        services.put(CurrentUserHolder.class, get(currentUserHolder));
        services.put(SearchIdHolder.class, get(searchIdHolder));
        services.put(FeedProperties.class, get(feedProperties));
        services.put(DataService.class, get(dataService));
        services.put(AttributeMapFactory.class, get(attributeMapFactory));
        services.put(Store.class, get(store));
        services.put(WordListProvider.class, get(wordListProvider));
        return services;
    }

    private static <T> T get(final Provider<T> provider) {
        return provider == null ? null : provider.get();
    }
}
