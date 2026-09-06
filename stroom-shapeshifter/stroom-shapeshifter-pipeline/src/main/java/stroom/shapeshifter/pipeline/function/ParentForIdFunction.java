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

package stroom.shapeshifter.pipeline.function;

import stroom.data.store.api.Source;
import stroom.data.store.api.Store;
import stroom.meta.shared.Meta;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.engine.value.TypedValue;

import java.io.IOException;

/**
 * {@code parent-for-id}: as {@code stroom.pipeline.xsltfunctions.ParentForId}: the parent's id of
 * any stream by its id, read from the store and remembered for the last id asked.
 */
public final class ParentForIdFunction extends StroomFunction {

    public ParentForIdFunction() {
        super("parent-for-id", Signature.of(Kind.STRING, Kind.STRING), Purity.CONTEXT);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return new FunctionCall() {
            private Long lastStreamId;
            private Long lastParentId;

            @Override
            public TypedValue call(final Arguments arguments) {
                final String id = requiredString(context, arguments, 0);
                if (id == null) {
                    return null;
                }
                try {
                    final long streamId = Long.parseLong(id);
                    if (lastStreamId == null || lastStreamId != streamId) {
                        final Store store = context.service(Store.class);
                        if (store == null) {
                            return null;
                        }
                        try (Source source = store.openSource(streamId)) {
                            final Meta meta = source.getMeta();
                            lastParentId = meta == null ? null : meta.getParentMetaId();
                            lastStreamId = streamId;
                        }
                    }
                    return lastParentId == null ? null : text(String.valueOf(lastParentId));
                } catch (final IOException | RuntimeException e) {
                    context.error(e.getMessage());
                    return null;
                }
            }
        };
    }
}
