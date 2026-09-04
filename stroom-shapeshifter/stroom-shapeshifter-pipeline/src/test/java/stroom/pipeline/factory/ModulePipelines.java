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

package stroom.pipeline.factory;

import stroom.docref.DocRef;
import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.cache.PoolItem;
import stroom.pipeline.cache.PoolKey;
import stroom.pipeline.cache.StoredParserFactory;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.StoredErrorReceiver;
import stroom.pipeline.parser.XMLParser;
import stroom.pipeline.shared.PipelineDataMerger;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineLayer;
import stroom.pipeline.source.SourceElement;
import stroom.pipeline.writer.FileAppender;
import stroom.pipeline.writer.TextWriter;
import stroom.pipeline.writer.XMLWriter;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.pipeline.ShapeshifterFilter;
import stroom.shapeshifter.pipeline.ShapeshifterParser;
import stroom.shapeshifter.pipeline.ShapeshifterParserFactory;
import stroom.shapeshifter.pipeline.ShapeshifterParserFactoryPool;
import stroom.shapeshifter.pipeline.ShapeshifterStore;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.task.api.SimpleTaskContext;
import stroom.util.io.SimplePathCreator;
import stroom.util.json.JsonUtil;

import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real pipelines in the Shapeshifter pipeline module, without a Stroom: the elements are built
 * by hand, the documents live in a map, and the pipeline is what {@link PipelineFactory} makes of
 * the same JSON Stroom's own full-pipeline tests use. In this package because the element
 * registry's constructor is, as {@code MockPipelineElementRegistryFactory} in stroom-app is.
 */
public final class ModulePipelines implements ElementRegistryFactory, ElementFactory {

    private final ElementRegistry registry = new ElementRegistry(List.of(
            SourceElement.class,
            ShapeshifterParser.class,
            ShapeshifterFilter.class,
            XMLParser.class,
            TextWriter.class,
            XMLWriter.class,
            FileAppender.class));
    private final ErrorReceiverProxy errors;
    private final SimplePathCreator paths;
    private final Map<String, ShapeshifterDoc> docs = new HashMap<>();
    private final ShapeshifterStore store = Mockito.mock(ShapeshifterStore.class);
    private final ShapeshifterParserFactoryPool pool = new ShapeshifterParserFactoryPool() {
        @Override
        public PoolItem<StoredParserFactory> borrowObject(final ShapeshifterDoc doc, final boolean usePool) {
            return new PoolItem<>(new PoolKey<>(doc.getUuid()), new StoredParserFactory(
                    new ShapeshifterParserFactory(ProjectReader.read(doc.getData())), new StoredErrorReceiver()));
        }

        @Override
        public void returnObject(final PoolItem<StoredParserFactory> poolItem, final boolean usePool) {
        }
    };

    /**
     * @param errors  where every element reports
     * @param tempDir what {@code ${stroom.temp}} means in a pipeline's properties
     */
    public ModulePipelines(final ErrorReceiverProxy errors, final Path tempDir) {
        this.errors = errors;
        this.paths = new SimplePathCreator(() -> tempDir, () -> tempDir);
        Mockito.when(store.readDocument(Mockito.any()))
                .thenAnswer(invocation -> docs.get(((DocRef) invocation.getArgument(0)).getUuid()));
    }

    /** A Shapeshifter document the parser and filter elements can be pointed at. */
    public DocRef shapeshifterDoc(final String name, final String json) {
        final String uuid = UUID.randomUUID().toString();
        final ShapeshifterDoc doc = ShapeshifterDoc.builder().uuid(uuid).name(name)
                .data(json).build();
        docs.put(uuid, doc);
        return new DocRef(ShapeshifterDoc.TYPE, uuid, name);
    }

    /** The pipeline the data describes, validated and merged as the pipeline cache would. */
    public Pipeline create(final PipelineData pipelineData) {
        new PipelineDataValidator(this).validate(pipelineData, PipelineDataMerger.createElementMap());
        final DocRef pipelineRef = new DocRef(PipelineDoc.TYPE, UUID.randomUUID().toString(), "test");
        final PipelineData merged = new PipelineDataMerger()
                .merge(new PipelineLayer(pipelineRef, pipelineData))
                .createMergedData();
        return new PipelineFactory(this, this, new SimpleProcessorFactory(), errors)
                .create(merged, new SimpleTaskContext());
    }

    public static PipelineData pipelineData(final String json) {
        return JsonUtil.readValue(json, PipelineData.class);
    }

    @Override
    public ElementRegistry get() {
        return registry;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Element> T getElementInstance(final Class<T> elementClass) {
        if (elementClass.equals(ShapeshifterParser.class)) {
            return (T) new ShapeshifterParser(errors, new LocationFactoryProxy(), pool, store, paths, null, null, null);
        }
        if (elementClass.equals(ShapeshifterFilter.class)) {
            return (T) new ShapeshifterFilter(errors, pool, store, paths, null, null, null);
        }
        if (elementClass.equals(XMLParser.class)) {
            return (T) new XMLParser(errors, new LocationFactoryProxy());
        }
        if (elementClass.equals(TextWriter.class)) {
            return (T) new TextWriter(errors);
        }
        if (elementClass.equals(XMLWriter.class)) {
            return (T) new XMLWriter();
        }
        if (elementClass.equals(FileAppender.class)) {
            return (T) new FileAppender(errors, null, paths);
        }
        try {
            return elementClass.getConstructor().newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new PipelineFactoryException(e);
        }
    }
}
