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

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * {@code host-address}: as {@code stroom.pipeline.xsltfunctions.HostAddress}. Impure: it resolves
 * through DNS, and a preview should not.
 */
public final class HostAddressFunction extends StroomFunction {

    public HostAddressFunction() {
        super("host-address", Signature.of(1, Kind.STRING, Kind.STRING, Kind.BOOLEAN), Purity.IMPURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final boolean ignoreWarnings = Boolean.TRUE.equals(arguments.bool(1));
            try {
                final String hostName = arguments.string(0);
                if (hostName == null) {
                    throw new IllegalArgumentException("Illegal non string argument found in function "
                                                       + name() + "() at position 0");
                }
                return TypedValue.of(InetAddress.getByName(hostName).getHostAddress());
            } catch (final UnknownHostException | RuntimeException e) {
                if (!ignoreWarnings) {
                    context.warn(e.getMessage());
                }
                return null;
            }
        };
    }
}
