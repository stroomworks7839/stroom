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

import stroom.shapeshifter.engine.exec.TypedValue;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;

/** {@code hex-to-oct}: as {@code stroom.pipeline.xsltfunctions.HexToOct}. */
public final class HexToOctFunction extends StroomFunction {

    public HexToOctFunction() {
        super("hex-to-oct", Signature.of(Kind.STRING, Kind.STRING), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final String hex = requiredString(context, arguments, 0);
            if (hex == null) {
                return null;
            }
            try {
                return TypedValue.of(Long.toOctalString(Long.parseLong(hex, 16)));
            } catch (final NumberFormatException e) {
                context.warn(e.getMessage());
                return null;
            }
        };
    }
}
