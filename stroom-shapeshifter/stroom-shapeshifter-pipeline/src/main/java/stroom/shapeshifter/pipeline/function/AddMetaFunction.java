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

import stroom.pipeline.state.MetaDataHolder;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;

/** {@code add-meta}: as {@code stroom.pipeline.xsltfunctions.AddMeta}: a key and value into the stream's meta data. */
public final class AddMetaFunction extends StroomFunction {

    public AddMetaFunction() {
        super("add-meta", Signature.of(Kind.STRING, Kind.STRING, Kind.STRING), Purity.IMPURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final String key = requiredString(context, arguments, 0);
            final String value = requiredString(context, arguments, 1);
            if (key == null || key.isBlank()) {
                return null;
            }
            final MetaDataHolder holder = context.service(MetaDataHolder.class);
            if (holder == null) {
                return null;
            }
            try {
                holder.getMetaData().put(key, value == null ? "" : value);
            } catch (final RuntimeException e) {
                context.error(e.getMessage());
            }
            return null;
        };
    }
}
