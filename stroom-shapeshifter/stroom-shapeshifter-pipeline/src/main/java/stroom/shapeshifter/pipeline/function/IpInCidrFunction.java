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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Matcher;

/** {@code ip-in-cidr}: as {@code stroom.pipeline.xsltfunctions.IPInCidr}. */
public final class IpInCidrFunction extends StroomFunction {

    public IpInCidrFunction() {
        super("ip-in-cidr", Signature.of(Kind.BOOLEAN, Kind.STRING, Kind.STRING), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final String ip = requiredString(context, arguments, 0);
            final String cidr = requiredString(context, arguments, 1);
            if (ip == null || cidr == null) {
                return null;
            }
            final Matcher cidrMatcher = Cidr.IPV4_CIDR_PATTERN.matcher(cidr);
            if (!cidrMatcher.matches()) {
                context.error("Invalid CIDR format: " + cidr);
                return null;
            }
            try {
                final InetAddress ipAddress = InetAddress.getByName(ip);
                final InetAddress cidrAddress = InetAddress.getByName(cidrMatcher.group(1));
                final int prefixLength = Integer.parseInt(cidrMatcher.group(2));
                final int subnetMask = 0xFFFFFFFF << (32 - prefixLength);
                final byte[] subnetMaskBytes = new byte[]{
                        (byte) ((subnetMask & 0xFF000000) >>> 24),
                        (byte) ((subnetMask & 0x00FF0000) >>> 16),
                        (byte) ((subnetMask & 0x0000FF00) >>> 8),
                        (byte) (subnetMask & 0x000000FF)
                };
                final byte[] ipBytes = ipAddress.getAddress();
                final byte[] cidrBytes = cidrAddress.getAddress();
                for (int i = 0; i < ipBytes.length; i++) {
                    if ((ipBytes[i] & subnetMaskBytes[i]) != (cidrBytes[i] & subnetMaskBytes[i])) {
                        return new TypedValue.Bool(false);
                    }
                }
                return new TypedValue.Bool(true);
            } catch (final UnknownHostException e) {
                context.error("Invalid IP address format");
                return null;
            }
        };
    }
}
