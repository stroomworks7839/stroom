/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.ai;

import stroom.config.common.HasDbConfig;
import stroom.util.cache.CacheConfig;
import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsStroomConfig;
import stroom.util.time.StroomDuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Min;

/// What a node is told about Shapeshifter AI as a whole, as against what one document says about itself:
/// where its runtime state (A26) is kept, the caches that keep the hot path off those tables, how much
/// deferred learning one pass of the worker does (A5), and how long an attempt's record is kept (A28).
public class ShapeshifterAiConfig extends AbstractConfig implements IsStroomConfig, HasDbConfig {

    private final ShapeshifterAiDbConfig dbConfig;
    private final CacheConfig ruleCache;
    private final CacheConfig shapeCache;
    private final int deferredLearningBatchSize;
    private final StroomDuration attemptRetention;

    public ShapeshifterAiConfig() {
        dbConfig = new ShapeshifterAiDbConfig();
        // Read for every stream of every shape and written only when something is learned, so it is
        // invalidated by what writes it rather than by time; the expiry is a backstop.
        ruleCache = CacheConfig.builder()
                .maximumSize(1000L)
                .expireAfterAccess(StroomDuration.ofMinutes(10))
                .build();
        shapeCache = CacheConfig.builder()
                .maximumSize(10_000L)
                .expireAfterAccess(StroomDuration.ofMinutes(10))
                .build();
        deferredLearningBatchSize = 10;
        attemptRetention = StroomDuration.ofDays(90);
    }

    @SuppressWarnings("unused")
    @JsonCreator
    public ShapeshifterAiConfig(@JsonProperty("db") final ShapeshifterAiDbConfig dbConfig,
                                @JsonProperty("ruleCache") final CacheConfig ruleCache,
                                @JsonProperty("shapeCache") final CacheConfig shapeCache,
                                @JsonProperty("deferredLearningBatchSize") final int deferredLearningBatchSize,
                                @JsonProperty("attemptRetention") final StroomDuration attemptRetention) {
        this.dbConfig = dbConfig;
        this.ruleCache = ruleCache;
        this.shapeCache = shapeCache;
        this.deferredLearningBatchSize = deferredLearningBatchSize;
        this.attemptRetention = attemptRetention;
    }

    @Override
    @JsonProperty("db")
    @JsonPropertyDescription("The database holding the runtime state of A26: the shapes a document has met, "
                             + "its routing rules, the ledger of sentinelled streams, each feed's error-mode "
                             + "state and the cluster's spend.")
    public ShapeshifterAiDbConfig getDbConfig() {
        return dbConfig;
    }

    @JsonProperty("ruleCache")
    @JsonPropertyDescription("The routing rules of each Shapeshifter AI document, cached per node. Every "
                             + "stream of every shape is routed against them, and they change only when "
                             + "something is learned or an operator edits them, which clears the entry on "
                             + "every node.")
    public CacheConfig getRuleCache() {
        return ruleCache;
    }

    @JsonProperty("shapeCache")
    @JsonPropertyDescription("What each shape has settled into — given up, marked for relearning, awaiting "
                             + "review — cached per node. The rolling scores are not cached: they are "
                             + "counted across the cluster.")
    public CacheConfig getShapeCache() {
        return shapeCache;
    }

    @Min(1)
    @JsonProperty("deferredLearningBatchSize")
    @JsonPropertyDescription("How many attempts awaiting the model one pass of the Shapeshifter AI Deferred "
                             + "Learning job carries on. One attempt is one conversation, which may be minutes "
                             + "of model time, so a pass is bounded and the job's schedule sets the rate. "
                             + "At least one: a batch of none would leave every deferred document learning "
                             + "nothing, silently.")
    public int getDeferredLearningBatchSize() {
        return deferredLearningBatchSize;
    }

    @JsonProperty("attemptRetention")
    @JsonPropertyDescription("How long the record of a finished attempt is kept before the prune job "
                             + "removes it and its turns, and how long what each rule produced is kept, "
                             + "which is what a retraction can still ask to be processed again. An attempt "
                             + "that is still learning, or waiting for a person, is never pruned however "
                             + "old it is. Zero keeps everything for ever, which on a busy cluster is a "
                             + "row per stream that nothing will ever remove.")
    public StroomDuration getAttemptRetention() {
        return attemptRetention;
    }
}
