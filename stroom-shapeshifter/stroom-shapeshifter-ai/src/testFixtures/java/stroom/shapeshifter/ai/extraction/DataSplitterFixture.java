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

package stroom.shapeshifter.ai.extraction;

import stroom.content.ContentPack;
import stroom.content.ContentPacks;
import stroom.docref.DocRef;
import stroom.docstore.impl.Serialiser2FactoryImpl;
import stroom.docstore.impl.StoreFactoryImpl;
import stroom.docstore.impl.memory.MemoryPersistence;
import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.cache.PoolItem;
import stroom.pipeline.cache.PoolKey;
import stroom.pipeline.cache.SchemaKey;
import stroom.pipeline.cache.SchemaLoaderImpl;
import stroom.pipeline.cache.SchemaPool;
import stroom.pipeline.cache.StoredSchema;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.filter.SchemaFilter;
import stroom.pipeline.state.PipelineContext;
import stroom.pipeline.xml.converter.ds3.DS3ParserFactory;
import stroom.pipeline.xmlschema.XmlSchemaCache;
import stroom.pipeline.xmlschema.XmlSchemaSerialiser;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.pipeline.xmlschema.XmlSchemaStoreImpl;
import stroom.security.api.SecurityContext;
import stroom.security.mock.MockSecurityContext;
import stroom.test.common.util.test.ContentPackZipDownloader;
import stroom.test.common.util.test.FileSystemTestUtil;
import stroom.util.io.StreamUtil;

import com.google.inject.Guice;
import com.google.inject.Injector;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything a {@link DataSplitterCompiler} needs in order to run outside a Stroom node.
 * <p>
 * The Data Splitter validates each configuration against the {@code data-splitter:3} schema, which it
 * resolves through Stroom's XML schema store. That schema is not in the repository; it ships in the
 * {@code core-xml-schemas} content pack (design §2.1), so this fixture fetches the pack into the shared
 * test download area and stands up an in-memory schema store holding just that one document.
 */
public final class DataSplitterFixture {

    /**
     * Where the schema sits inside the content pack, by the pack's own naming.
     */
    private static final String SCHEMA_FILE = "XML_Schemas/data_splitter/"
            + "data_splitter_v3_0.XMLSchema.9e1e2567-ba83-4720-95c0-f882b951bd3e.xsd";

    private final DataSplitterCompiler compiler;

    public DataSplitterFixture() {
        final SecurityContext securityContext = new MockSecurityContext();
        final XmlSchemaStore schemaStore = new XmlSchemaStoreImpl(
                new StoreFactoryImpl(new MemoryPersistence(), null, securityContext, null, null),
                securityContext,
                new XmlSchemaSerialiser(new Serialiser2FactoryImpl()));
        loadDataSplitterSchema(schemaStore);

        final XmlSchemaCache schemaCache = new XmlSchemaCache(schemaStore, securityContext);
        final SchemaPool schemaPool = new LoadingSchemaPool(new SchemaLoaderImpl(schemaCache));

        // The factory's constructor is package-private and injected, so it is reached the way the
        // node reaches it: through Guice. Each factory takes a fresh filter, as the filter is
        // stateful across a configure; every filter reports through the one proxy the compiler owns.
        final ErrorReceiverProxy errorReceiverProxy = new ErrorReceiverProxy();
        final Injector injector = Guice.createInjector(binder -> binder
                .bind(SchemaFilter.class)
                .toProvider(() -> new SchemaFilter(
                        schemaPool,
                        schemaCache,
                        errorReceiverProxy,
                        new LocationFactoryProxy(),
                        new PipelineContext())));
        compiler = new DataSplitterCompiler(injector.getProvider(DS3ParserFactory.class), errorReceiverProxy);
    }

    public DataSplitterCompiler compiler() {
        return compiler;
    }

    private static void loadDataSplitterSchema(final XmlSchemaStore schemaStore) {
        final ContentPack pack = ContentPacks.CORE_XML_SCHEMAS_PACK;
        final Path packDir = ContentPackZipDownloader.downloadContentPack(
                pack,
                FileSystemTestUtil.getExplodedContentPacksDir());
        final Path schemaFile = packDir.resolve(pack.getPath()).resolve(SCHEMA_FILE);

        final DocRef docRef = schemaStore.createDocument(DS3ParserFactory.SCHEMA_NAME);
        schemaStore.writeDocument(schemaStore.readDocument(docRef)
                .copy()
                .schemaGroup(DS3ParserFactory.SCHEMA_GROUP)
                .namespaceURI(DS3ParserFactory.NAMESPACE_URI)
                .systemId(DS3ParserFactory.SYSTEM_ID)
                .data(StreamUtil.fileToString(schemaFile))
                .build());
    }

    /**
     * Compiles each distinct schema once and hands it out on every borrow. There is one schema and no
     * contention here, so the node's pooled cache would add machinery without adding anything else.
     */
    private static final class LoadingSchemaPool implements SchemaPool {

        private final SchemaLoaderImpl loader;
        private final Map<SchemaKey, StoredSchema> schemas = new ConcurrentHashMap<>();

        private LoadingSchemaPool(final SchemaLoaderImpl loader) {
            this.loader = loader;
        }

        @Override
        public PoolItem<StoredSchema> borrowObject(final SchemaKey key, final boolean usePool) {
            final StoredSchema schema = schemas.computeIfAbsent(key, k ->
                    loader.load(k.getSchemaLanguage(), k.getData(), k.getFindXMLSchemaCriteria()));
            return new PoolItem<>(new PoolKey<>(key), schema);
        }

        @Override
        public void returnObject(final PoolItem<StoredSchema> poolItem, final boolean usePool) {
        }
    }
}
