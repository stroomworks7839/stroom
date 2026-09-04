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

import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.pipeline.PipelineState;

/**
 * {@code get}: as {@code stroom.pipeline.xsltfunctions.Get}: a value {@code put} earlier, from the
 * pipeline's state — across streams, as Stroom's pipeline-scoped map is — or, outside a
 * pipeline, from the run's own.
 */
public final class GetFunction extends StroomFunction {

    public GetFunction() {
        super("get", Signature.of(Kind.STRING, Kind.STRING), Purity.CONTEXT);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final String key = requiredString(context, arguments, 0);
            if (key == null) {
                return null;
            }
            final PipelineState state = context.service(PipelineState.class);
            final Object value = state != null ? state.values().get(key) : context.state().get(key);
            return value == null ? null : text(value.toString());
        };
    }
}
