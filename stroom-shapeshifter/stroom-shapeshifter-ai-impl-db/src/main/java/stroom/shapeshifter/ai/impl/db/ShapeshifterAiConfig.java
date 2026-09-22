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

package stroom.shapeshifter.ai.impl.db;

import stroom.config.common.HasDbConfig;
import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsStroomConfig;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// What a node is told about Shapeshifter AI as a whole, as against what one document says about itself:
/// at present only where its runtime state (A26) is kept, so that an operator can point the tables at
/// another database or tune the pool as they can for every other module.
public class ShapeshifterAiConfig extends AbstractConfig implements IsStroomConfig, HasDbConfig {

    private final ShapeshifterAiDbConfig dbConfig;

    public ShapeshifterAiConfig() {
        dbConfig = new ShapeshifterAiDbConfig();
    }

    @SuppressWarnings("unused")
    @JsonCreator
    public ShapeshifterAiConfig(@JsonProperty("db") final ShapeshifterAiDbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    @JsonProperty("db")
    @JsonPropertyDescription("The database holding the runtime state of A26: the shapes a document has met, "
                             + "its routing rules, the ledger of sentinelled streams, each feed's error-mode "
                             + "state and the cluster's spend.")
    public ShapeshifterAiDbConfig getDbConfig() {
        return dbConfig;
    }
}
