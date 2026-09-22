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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.Ledger;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.stage.Shapes;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;

/// The runtime state in memory, for a harness that has no database: the node binds these to the tables of
/// A26 through `stroom-shapeshifter-ai-impl-db` instead. What is in memory is one JVM's and lasts as long
/// as it does, which is why the tables exist.
public class InMemoryStateModule extends AbstractModule {

    @Override
    protected void configure() {
        super.configure();
        bind(Rules.class).to(InMemoryRules.class).in(Scopes.SINGLETON);
        bind(Shapes.class).to(InMemoryShapes.class).in(Scopes.SINGLETON);
        bind(Ledger.class).to(InMemoryLedger.class).in(Scopes.SINGLETON);
    }
}
