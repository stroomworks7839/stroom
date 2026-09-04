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

import stroom.data.store.api.AttributeMapFactory;
import stroom.data.store.api.DataService;
import stroom.data.store.api.Store;
import stroom.dictionary.api.WordListProvider;
import stroom.docref.DocRef;
import stroom.feed.api.FeedProperties;
import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.cache.PoolItem;
import stroom.pipeline.cache.PoolKey;
import stroom.pipeline.cache.SchemaKey;
import stroom.pipeline.cache.SchemaLoaderImpl;
import stroom.pipeline.cache.SchemaPool;
import stroom.pipeline.cache.StoredParserFactory;
import stroom.pipeline.cache.StoredSchema;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.StoredErrorReceiver;
import stroom.pipeline.filter.RecordCountFilter;
import stroom.pipeline.filter.RecordOutputFilter;
import stroom.pipeline.filter.SchemaFilter;
import stroom.pipeline.filter.SchemaFilterSplit;
import stroom.pipeline.filter.SplitFilter;
import stroom.pipeline.parser.XMLParser;
import stroom.pipeline.shared.PipelineDataMerger;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineLayer;
import stroom.pipeline.source.SourceElement;
import stroom.pipeline.state.CurrentUserHolder;
import stroom.pipeline.state.FeedHolder;
import stroom.pipeline.state.MetaDataHolder;
import stroom.pipeline.state.MetaHolder;
import stroom.pipeline.state.PipelineContext;
import stroom.pipeline.state.PipelineHolder;
import stroom.pipeline.state.RecordCount;
import stroom.pipeline.state.RecordCountService;
import stroom.pipeline.state.SearchIdHolder;
import stroom.pipeline.writer.FileAppender;
import stroom.pipeline.writer.TextWriter;
import stroom.pipeline.writer.XMLWriter;
import stroom.pipeline.xmlschema.XmlSchemaCache;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.function.FunctionRegistry;
import stroom.shapeshifter.pipeline.ShapeshifterFilter;
import stroom.shapeshifter.pipeline.ShapeshifterFunctionModule;
import stroom.shapeshifter.pipeline.ShapeshifterParser;
import stroom.shapeshifter.pipeline.ShapeshifterParserFactory;
import stroom.shapeshifter.pipeline.ShapeshifterParserFactoryPool;
import stroom.shapeshifter.pipeline.ShapeshifterServices;
import stroom.shapeshifter.pipeline.ShapeshifterStore;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.task.api.SimpleTaskContext;
import stroom.util.io.SimplePathCreator;
import stroom.util.json.JsonUtil;
import stroom.util.shared.ResultPage;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Real pipelines in the Shapeshifter pipeline module, without a Stroom: the elements are built
 * by hand, the documents and schemas live in lists, and the pipeline is what
 * {@link PipelineFactory} makes of the same JSON Stroom's own full-pipeline tests use — record
 * counting, splitting, schema validation, record output and the writers and appender included.
 * In this package because the element registry's constructor is, as
 * {@code MockPipelineElementRegistryFactory} in stroom-app is.
 */
public final class ModulePipelines implements ElementRegistryFactory, ElementFactory {

    private final ElementRegistry registry = new ElementRegistry(List.of(
            SourceElement.class,
            ShapeshifterParser.class,
            ShapeshifterFilter.class,
            XMLParser.class,
            RecordCountFilter.class,
            SplitFilter.class,
            SchemaFilterSplit.class,
            RecordOutputFilter.class,
            TextWriter.class,
            XMLWriter.class,
            FileAppender.class));
    private final ErrorReceiverProxy errors;
    private final SimplePathCreator paths;
    private final Map<String, ShapeshifterDoc> docs = new HashMap<>();
    private final List<XmlSchemaDoc> schemas = new ArrayList<>();
    private final RecordCount recordCount = new RecordCount();
    /** The holders a document's functions read, one of each for the harness, and mocked stores. */
    private final MetaHolder metaHolder = new MetaHolder();
    private final MetaDataHolder metaDataHolder = new MetaDataHolder();
    private final FeedHolder feedHolder = new FeedHolder();
    private final PipelineHolder pipelineHolder = new PipelineHolder();
    private final ShapeshifterServices shapeshifterServices = new ShapeshifterServices(
            () -> metaHolder, () -> metaDataHolder, () -> feedHolder, () -> pipelineHolder,
            CurrentUserHolder::new, SearchIdHolder::new,
            () -> Mockito.mock(FeedProperties.class), () -> Mockito.mock(DataService.class),
            () -> Mockito.mock(AttributeMapFactory.class), () -> Mockito.mock(Store.class),
            () -> Mockito.mock(WordListProvider.class));
    private final RecordCountService recordCountService = new RecordCountService();
    private final XmlSchemaCache schemaCache;
    private final Map<SchemaKey, StoredSchema> compiledSchemas = new HashMap<>();
    private final SchemaPool schemaPool = new SchemaPool() {
        @Override
        public PoolItem<StoredSchema> borrowObject(final SchemaKey key, final boolean usePool) {
            return new PoolItem<>(new PoolKey<>(key), compiledSchemas.computeIfAbsent(key, k ->
                    new SchemaLoaderImpl(schemaCache).load(k.getSchemaLanguage(), k.getData(),
                            k.getFindXMLSchemaCriteria())));
        }

        @Override
        public void returnObject(final PoolItem<StoredSchema> poolItem, final boolean usePool) {
        }
    };
    private final ShapeshifterStore store = Mockito.mock(ShapeshifterStore.class);
    private final ShapeshifterParserFactoryPool pool = new ShapeshifterParserFactoryPool() {
        @Override
        public PoolItem<StoredParserFactory> borrowObject(final ShapeshifterDoc doc, final boolean usePool) {
            return new PoolItem<>(new PoolKey<>(doc.getUuid()), new StoredParserFactory(
                    new ShapeshifterParserFactory(ProjectReader.read(doc.getData()),
                            FunctionRegistry.of(ShapeshifterFunctionModule.all())), new StoredErrorReceiver()));
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
        // The schema cache is Stroom's own, over a store that knows the schemas given here and a
        // security context that runs whatever it is asked to.
        final XmlSchemaStore schemaStore = Mockito.mock(XmlSchemaStore.class);
        Mockito.when(schemaStore.find(Mockito.any()))
                .thenAnswer(invocation -> new ResultPage<>(List.copyOf(schemas)));
        final SecurityContext security = Mockito.mock(SecurityContext.class);
        Mockito.when(security.asProcessingUserResult(Mockito.any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        this.schemaCache = new XmlSchemaCache(schemaStore, security);
    }

    /** An XML schema the schema filter can validate against, as the content pack declares it. */
    public void xmlSchema(final String name, final String namespaceUri, final String systemId,
                          final String schemaGroup, final String xsd) {
        schemas.add(XmlSchemaDoc.builder().uuid(UUID.randomUUID().toString()).name(name)
                .namespaceURI(namespaceUri).systemId(systemId).schemaGroup(schemaGroup).data(xsd).build());
    }

    /** The stream every document is processed as, for the functions that read it. */
    public MetaHolder metaHolder() {
        return metaHolder;
    }

    public MetaDataHolder metaDataHolder() {
        return metaDataHolder;
    }

    public FeedHolder feedHolder() {
        return feedHolder;
    }

    /** What the record count filters counted. */
    public RecordCount recordCount() {
        return recordCount;
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
            return (T) new ShapeshifterParser(errors, new LocationFactoryProxy(), pool, store, paths, null, null, null,
                    shapeshifterServices);
        }
        if (elementClass.equals(ShapeshifterFilter.class)) {
            return (T) new ShapeshifterFilter(errors, pool, store, paths, null, null, null, shapeshifterServices);
        }
        if (elementClass.equals(XMLParser.class)) {
            return (T) new XMLParser(errors, new LocationFactoryProxy());
        }
        if (elementClass.equals(RecordCountFilter.class)) {
            return (T) new RecordCountFilter(recordCountService, recordCount);
        }
        if (elementClass.equals(SplitFilter.class)) {
            return (T) new SplitFilter();
        }
        if (elementClass.equals(SchemaFilterSplit.class)) {
            return (T) new SchemaFilterSplit(new SchemaFilter(schemaPool, schemaCache, errors,
                    new LocationFactoryProxy(), new PipelineContext()), null);
        }
        if (elementClass.equals(RecordOutputFilter.class)) {
            return (T) new RecordOutputFilter(errors);
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
