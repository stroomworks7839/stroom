/*
 * Copyright 2024 Crown Copyright
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

package stroom.shapeshifter.ai.impl.db;

import stroom.db.util.AbstractFlyWayDbModule;
import stroom.db.util.DataSourceProxy;
import stroom.shapeshifter.ai.ShapeshifterAiDbConfig;
import stroom.shapeshifter.ai.cache.CachedRules;
import stroom.shapeshifter.ai.cache.CachedShapes;
import stroom.shapeshifter.ai.cache.Rows;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Ledger;
import stroom.shapeshifter.ai.stage.Outputs;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.stage.Shapes;
import stroom.shapeshifter.ai.stage.Spend;
import stroom.util.entityevent.EntityEvent;
import stroom.util.guice.GuiceUtil;
import stroom.util.shared.Clearable;

import com.google.inject.Scopes;

import java.util.List;
import javax.sql.DataSource;

/// The runtime state of A26 as tables: the seams the stage has used against memory since slice 8, bound
/// here to rows so that what a node learns survives its restart and reaches every other node (A41, A42).
public class ShapeshifterAiDbModule
        extends AbstractFlyWayDbModule<ShapeshifterAiDbConfig, ShapeshifterAiDbConnProvider> {

    private static final String MODULE = "stroom-shapeshifter-ai";
    private static final String FLYWAY_LOCATIONS = "stroom/shapeshifter/ai/impl/db/migration";
    private static final String FLYWAY_TABLE = "shapeshifter_ai_schema_history";

    @Override
    protected void configure() {
        super.configure();
        bind(Attempts.class).to(AttemptsDao.class);
        bind(Ledger.class).to(LedgerDao.class);
        bind(Spend.class).to(SpendDao.class);
        bind(Outputs.class).to(OutputsDao.class);
        // The two the hot path reads for every stream are bound as the rows behind a cache, and the cache
        // is what everything else asks for (design 01 §12 item 8): a routing table read from the database
        // once per stream per node is the first thing to give at volume. Nothing but the cache asks the
        // database, and what writes through it tells every node to let go of its copy.
        bind(Rules.class).annotatedWith(Rows.class).to(RulesDao.class);
        bind(Shapes.class).annotatedWith(Rows.class).to(ShapesDao.class);
        bind(Rules.class).to(CachedRules.class).in(Scopes.SINGLETON);
        bind(Shapes.class).to(CachedShapes.class).in(Scopes.SINGLETON);
        GuiceUtil.buildMultiBinder(binder(), EntityEvent.Handler.class)
                .addBinding(CachedRules.class)
                .addBinding(CachedShapes.class);
        GuiceUtil.buildMultiBinder(binder(), Clearable.class)
                .addBinding(CachedRules.class)
                .addBinding(CachedShapes.class);
    }

    @Override
    protected String getFlyWayTableName() {
        return FLYWAY_TABLE;
    }

    @Override
    protected String getModuleName() {
        return MODULE;
    }

    @Override
    protected List<String> getFlyWayLocations() {
        return List.of(FLYWAY_LOCATIONS);
    }

    @Override
    protected Class<ShapeshifterAiDbConnProvider> getConnectionProviderType() {
        return ShapeshifterAiDbConnProvider.class;
    }

    @Override
    protected ShapeshifterAiDbConnProvider createConnectionProvider(final DataSource dataSource) {
        return new DataSourceImpl(dataSource);
    }

    private static class DataSourceImpl extends DataSourceProxy implements ShapeshifterAiDbConnProvider {

        private DataSourceImpl(final DataSource dataSource) {
            super(dataSource, MODULE);
        }
    }
}
