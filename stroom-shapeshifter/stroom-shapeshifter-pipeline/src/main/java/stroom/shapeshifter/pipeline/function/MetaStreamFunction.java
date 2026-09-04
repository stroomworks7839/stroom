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

import stroom.data.store.api.AttributeMapFactory;
import stroom.meta.shared.Meta;
import stroom.pipeline.state.MetaHolder;
import stroom.shapeshifter.engine.exec.TypedValue;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;

import java.io.UncheckedIOException;
import java.util.Map;

/**
 * {@code meta-stream} and {@code meta-stream-for-id}: as {@code stroom.pipeline.xsltfunctions.MetaStream}:
 * the attribute map of a part of a stream — the running part, or any by stream id and one-based
 * part number — as a {@code meta-stream} element of {@code string} entries, as text.
 */
abstract class MetaStreamFunction extends StroomFunction {

    MetaStreamFunction(final String name, final Signature signature) {
        super(name, signature, Purity.CONTEXT);
    }

    /** The stream id and zero-based part index, or null. */
    abstract long[] part(FunctionContext context, Arguments arguments);

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return new FunctionCall() {
            private long lastStreamId = -1;
            private long lastPart = -1;
            private String last;

            @Override
            public TypedValue call(final Arguments arguments) {
                final long[] part = part(context, arguments);
                if (part == null) {
                    return null;
                }
                try {
                    if (last == null || part[0] != lastStreamId || part[1] != lastPart) {
                        final AttributeMapFactory factory = context.service(AttributeMapFactory.class);
                        if (factory == null) {
                            return null;
                        }
                        Map<String, String> attributes;
                        try {
                            attributes = factory.getAttributeMapForPart(part[0], part[1]);
                        } catch (final UncheckedIOException e) {
                            attributes = Map.of();
                        }
                        last = MetaXml.entries("meta-stream", attributes);
                        lastStreamId = part[0];
                        lastPart = part[1];
                    }
                    return text(last);
                } catch (final Exception e) {
                    context.warn("Error fetching meta stream for streamId " + part[0] + " " + e.getMessage());
                    return null;
                }
            }
        };
    }

    static long[] running(final FunctionContext context) {
        final Meta meta = Holders.meta(context);
        final MetaHolder holder = context.service(MetaHolder.class);
        return meta == null ? null : new long[]{meta.getId(), holder.getPartIndex()};
    }
}
