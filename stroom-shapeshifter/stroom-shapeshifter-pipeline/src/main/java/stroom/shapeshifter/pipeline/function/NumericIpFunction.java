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
import stroom.util.net.IpAddressUtil;

import java.net.UnknownHostException;

/** {@code numeric-ip}: as {@code stroom.pipeline.xsltfunctions.NumericIP}, over {@link IpAddressUtil}. */
public final class NumericIpFunction extends StroomFunction {

    public NumericIpFunction() {
        super("numeric-ip", Signature.of(Kind.STRING, Kind.STRING), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final String ipAddress = requiredString(context, arguments, 0);
            if (ipAddress == null) {
                return null;
            }
            try {
                return TypedValue.of(Long.toString(IpAddressUtil.toNumericIpAddress(ipAddress)));
            } catch (final UnknownHostException e) {
                context.error(e.getMessage());
                return null;
            } catch (final RuntimeException e) {
                context.warn(e.getMessage());
                return null;
            }
        };
    }
}
