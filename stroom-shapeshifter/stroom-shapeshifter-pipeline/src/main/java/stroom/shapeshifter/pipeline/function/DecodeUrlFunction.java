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
import stroom.shapeshifter.engine.value.TypedValue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** {@code decode-url}: as {@code stroom.pipeline.xsltfunctions.DecodeUrl}. */
public final class DecodeUrlFunction extends StroomFunction {

    public DecodeUrlFunction() {
        super("decode-url", Signature.of(Kind.STRING, Kind.STRING), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final String url = requiredString(context, arguments, 0);
            if (url == null) {
                return null;
            }
            try {
                return TypedValue.of(URLDecoder.decode(url, StandardCharsets.UTF_8));
            } catch (final RuntimeException e) {
                context.warn(e.getMessage());
                return null;
            }
        };
    }
}
