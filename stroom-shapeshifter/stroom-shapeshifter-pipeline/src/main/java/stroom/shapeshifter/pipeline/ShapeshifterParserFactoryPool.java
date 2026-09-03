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

import stroom.pipeline.cache.PoolItem;
import stroom.pipeline.cache.StoredParserFactory;
import stroom.shapeshifter.shared.ShapeshifterDoc;

/**
 * Compiled configurations, pooled per document the way DS3's parser factories are: compilation
 * is the expensive step, a parser is cheap, and a document that changes evicts its entry.
 */
public interface ShapeshifterParserFactoryPool {

    PoolItem<StoredParserFactory> borrowObject(ShapeshifterDoc doc, boolean usePool);

    void returnObject(PoolItem<StoredParserFactory> poolItem, boolean usePool);
}
