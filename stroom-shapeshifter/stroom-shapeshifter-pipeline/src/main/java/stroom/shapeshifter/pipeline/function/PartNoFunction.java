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

import stroom.pipeline.state.MetaHolder;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;

/** {@code part-no}: as {@code stroom.pipeline.xsltfunctions.PartNo}: the one-based part of the stream. */
public final class PartNoFunction extends StroomFunction {

    public PartNoFunction() {
        super("part-no", Signature.of(Kind.STRING), Purity.CONTEXT);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final MetaHolder holder = context.service(MetaHolder.class);
            return holder == null ? null : text(String.valueOf(holder.getPartNo()));
        };
    }
}
