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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** {@code hash}: as {@code stroom.pipeline.xsltfunctions.Hash}: value, algorithm (SHA-256 by default), salt. */
public final class HashFunction extends StroomFunction {

    private static final String DEFAULT_ALGORITHM = "SHA-256";

    public HashFunction() {
        super("hash", Signature.of(1, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final String value = requiredString(context, arguments, 0);
            if (value == null || value.isEmpty()) {
                return null;
            }
            String algorithm = arguments.string(1);
            if (algorithm == null || algorithm.isBlank()) {
                algorithm = DEFAULT_ALGORITHM;
            }
            final String salt = arguments.string(2);
            try {
                final MessageDigest digest = MessageDigest.getInstance(algorithm);
                digest.reset();
                if (salt != null) {
                    digest.update(salt.getBytes());
                }
                digest.update(value.getBytes());
                return TypedValue.of(HexFormat.of().formatHex(digest.digest()));
            } catch (final NoSuchAlgorithmException | RuntimeException e) {
                context.error(e.getMessage());
                return null;
            }
        };
    }
}
