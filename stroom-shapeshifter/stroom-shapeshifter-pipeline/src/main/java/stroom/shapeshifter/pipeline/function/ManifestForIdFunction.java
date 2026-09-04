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

import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Signature;

/** {@code manifest-for-id}: any stream's attributes, by id. */
public final class ManifestForIdFunction extends ManifestFunction {

    public ManifestForIdFunction() {
        super("manifest-for-id", Signature.of(Kind.STRING, Kind.STRING));
    }

    @Override
    Long streamId(final FunctionContext context, final Arguments arguments) {
        final String id = requiredString(context, arguments, 0);
        try {
            return id == null ? null : Long.parseLong(id);
        } catch (final NumberFormatException e) {
            context.warn("Error fetching manifest for streamId " + id + " " + e.getMessage());
            return null;
        }
    }
}
