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

import stroom.data.store.api.DataException;
import stroom.data.store.api.DataService;
import stroom.meta.shared.Meta;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.engine.value.TypedValue;

import java.util.Map;

/**
 * {@code manifest} and {@code manifest-for-id}: as {@code stroom.pipeline.xsltfunctions.Manifest}:
 * a stream's attributes from the data store — the running stream's, or any stream's by id — as
 * a {@code manifest} element of {@code string} entries, as text; an empty element when the
 * store has none.
 */
abstract class ManifestFunction extends StroomFunction {

    ManifestFunction(final String name, final Signature signature) {
        super(name, signature, Purity.CONTEXT);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return new FunctionCall() {
            private long lastStreamId = -1;
            private String last;

            @Override
            public TypedValue call(final Arguments arguments) {
                final Long streamId = streamId(context, arguments);
                if (streamId == null) {
                    return null;
                }
                try {
                    if (last == null || streamId != lastStreamId) {
                        final DataService dataService = context.service(DataService.class);
                        if (dataService == null) {
                            return null;
                        }
                        Map<String, String> attributes;
                        try {
                            attributes = dataService.metaAttributes(streamId);
                        } catch (final DataException e) {
                            attributes = Map.of();
                        }
                        last = MetaXml.entries("manifest", attributes);
                        lastStreamId = streamId;
                    }
                    return text(last);
                } catch (final Exception e) {
                    context.warn("Error fetching manifest for streamId " + streamId + " " + e.getMessage());
                    return null;
                }
            }
        };
    }

    Long streamId(final FunctionContext context, final Arguments arguments) {
        final Meta meta = Holders.meta(context);
        return meta == null ? null : meta.getId();
    }
}
