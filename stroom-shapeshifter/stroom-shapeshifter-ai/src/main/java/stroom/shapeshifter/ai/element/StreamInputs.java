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

package stroom.shapeshifter.ai.element;

import stroom.data.store.api.AttributeMapFactory;
import stroom.data.store.api.Source;
import stroom.data.store.api.SourceUtil;
import stroom.data.store.api.Store;
import stroom.meta.api.MetaService;
import stroom.meta.shared.Meta;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Inputs;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.Optional;

/// The streams of A28 in a node: the stream store, read by meta id. This is how deferred mode's worker
/// gets back the sample an attempt was learning from — the attempt keeps the id and the store keeps the
/// stream, so nothing is copied and nothing is held.
///
/// The first part only, as the supervisor element sees it: a stream of several parts is served part by
/// part and it is the first that raised the attempt (design 02 §7).
@Singleton
public class StreamInputs implements Inputs {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(StreamInputs.class);

    private final Store store;
    private final MetaService metaService;
    private final AttributeMapFactory attributeMapFactory;

    @Inject
    public StreamInputs(final Store store,
                        final MetaService metaService,
                        final AttributeMapFactory attributeMapFactory) {
        this.store = store;
        this.metaService = metaService;
        this.attributeMapFactory = attributeMapFactory;
    }

    @Override
    public Optional<Input> byId(final long metaId) {
        final Meta meta = metaService.getMeta(metaId);
        if (meta == null) {
            return Optional.empty();
        }
        try (final Source source = store.openSource(metaId)) {
            final Map<String, String> attributes = attributeMapFactory.getAttributes(metaId);
            return Optional.of(new Input(
                    metaId,
                    meta.getFeedName(),
                    meta.getTypeName(),
                    attributes == null
                            ? Map.of()
                            : attributes,
                    SourceUtil.readString(source)));
        } catch (final Exception e) {
            // Deleted, on a volume this node cannot reach, or unreadable — and the close of the source
            // throws a checked exception besides. The attempt that was learning from it is abandoned by
            // the caller rather than left waiting for a stream that will not come.
            LOGGER.warn(() -> LogUtil.message("Stream {} could not be read: {}", metaId, e.getMessage()), e);
            return Optional.empty();
        }
    }
}
