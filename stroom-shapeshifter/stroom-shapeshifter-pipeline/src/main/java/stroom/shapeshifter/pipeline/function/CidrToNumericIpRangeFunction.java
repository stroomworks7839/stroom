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
import stroom.util.net.IpAddressUtil;

import java.net.UnknownHostException;
import java.util.regex.Matcher;

/**
 * {@code cidr-to-numeric-ip-range}: as {@code stroom.pipeline.xsltfunctions.CidrToNumericIPRange},
 * which returns an array of the network and broadcast addresses as numbers; here the two joined
 * with a comma (design 26 ruling 4), for a {@code tokenize} to split.
 */
public final class CidrToNumericIpRangeFunction extends StroomFunction {

    public CidrToNumericIpRangeFunction() {
        super("cidr-to-numeric-ip-range", Signature.of(Kind.STRING, Kind.STRING), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final String cidr = requiredString(context, arguments, 0);
            if (cidr == null) {
                return null;
            }
            final Matcher cidrMatcher = Cidr.IPV4_CIDR_PATTERN.matcher(cidr);
            if (!cidrMatcher.matches()) {
                context.error("Invalid CIDR format: " + cidr);
                return null;
            }
            try {
                final int prefixLength = Integer.parseInt(cidrMatcher.group(2));
                final int subnetMask = 0xFFFFFFFF << (32 - prefixLength);
                final long networkAddress = IpAddressUtil.toNumericIpAddress(cidrMatcher.group(1)) & subnetMask;
                final long broadcastAddress = networkAddress | (~subnetMask);
                return TypedValue.of(networkAddress + "," + broadcastAddress);
            } catch (final UnknownHostException e) {
                context.error("Invalid IP address");
                return null;
            }
        };
    }
}
