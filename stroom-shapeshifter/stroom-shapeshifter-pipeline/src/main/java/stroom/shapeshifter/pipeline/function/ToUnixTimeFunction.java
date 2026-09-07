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

/** {@code to-unixTime}: as {@code stroom.pipeline.xsltfunctions.ToUnixTime}: a date to epoch milliseconds. */
public final class ToUnixTimeFunction extends StroomFunction {

    public ToUnixTimeFunction() {
        super("to-unixTime", Signature.of(Kind.INTEGER, Kind.DATE), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final TypedValue.Instant value = arguments.date(0);
            if (value == null) {
                if (arguments.raw(0) != null) {
                    context.warn("Illegal non dateTime argument found in function " + name() + "() at position 0");
                }
                return null;
            }
            return new TypedValue.Integer(value.toJavaInstant().toEpochMilli());
        };
    }
}
