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

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/** {@code hex-to-string}: as {@code stroom.pipeline.xsltfunctions.HexToString}. */
public final class HexToStringFunction extends StroomFunction {

    public HexToStringFunction() {
        super("hex-to-string", Signature.of(Kind.STRING, Kind.STRING, Kind.STRING), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            String hex = requiredString(context, arguments, 0);
            final String charsetName = requiredString(context, arguments, 1);
            if (hex == null || charsetName == null) {
                return null;
            }
            hex = hex.replaceAll("\\s*", "");
            if (hex.isBlank()) {
                return TypedValue.of("");
            }
            try {
                final Charset charset = Charset.forName(charsetName);
                return TypedValue.of(charset.decode(decodeHex(hex)).toString());
            } catch (final IllegalArgumentException e) {
                // An unknown or illegal charset name, as Stroom reports it: an error.
                context.error("Failed to decode hex value " + hex + ". " + e.getMessage());
                return null;
            } catch (final RuntimeException e) {
                context.warn("Failed to decode hex value " + hex + ". " + e.getMessage());
                return null;
            }
        };
    }

    private static ByteBuffer decodeHex(final String hex) {
        final int length = hex.length();
        if (length % 2 > 0) {
            throw new IllegalStateException("Invalid string length: " + length);
        }
        final ByteBuffer bytes = ByteBuffer.allocate(length / 2);
        for (int i = 0; i < length; i += 2) {
            bytes.put((byte) Integer.parseInt(hex.substring(i, i + 2), 16));
        }
        return bytes.rewind();
    }
}
