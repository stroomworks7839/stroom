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

import stroom.data.store.api.DataService;
import stroom.meta.shared.Meta;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.engine.value.TypedValue;

import java.util.Map;

/**
 * {@code meta-attribute}: as {@code stroom.pipeline.xsltfunctions.MetaAttribute}: the stream's
 * attributes from the data store, fetched once per run, by key.
 */
public final class MetaAttributeFunction extends StroomFunction {

    public MetaAttributeFunction() {
        super("meta-attribute", Signature.of(Kind.STRING, Kind.STRING), Purity.CONTEXT);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return new FunctionCall() {
            private Map<String, String> attributes;

            @Override
            public TypedValue call(final Arguments arguments) {
                final String key = requiredString(context, arguments, 0);
                if (key == null) {
                    return null;
                }
                try {
                    if (attributes == null) {
                        final DataService dataService = context.service(DataService.class);
                        final Meta meta = Holders.meta(context);
                        if (dataService == null || meta == null) {
                            return null;
                        }
                        attributes = dataService.metaAttributes(meta.getId());
                    }
                    return text(attributes.get(key));
                } catch (final RuntimeException e) {
                    context.warn("Error fetching meta attribute for key '" + key + "' " + e.getMessage());
                    return null;
                }
            }
        };
    }
}
