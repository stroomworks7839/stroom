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

/** {@code meta-stream-for-id}: any part's attribute map, by stream id and one-based part number. */
public final class MetaStreamForIdFunction extends MetaStreamFunction {

    public MetaStreamForIdFunction() {
        super("meta-stream-for-id", Signature.of(Kind.STRING, Kind.STRING, Kind.INTEGER));
    }

    @Override
    long[] part(final FunctionContext context, final Arguments arguments) {
        final String id = requiredString(context, arguments, 0);
        final Long partNo = arguments.integer(1);
        if (id == null || partNo == null) {
            if (partNo == null) {
                context.warn("Illegal non integer argument found in function " + name() + "() at position 1");
            }
            return null;
        }
        try {
            return new long[]{Long.parseLong(id), partNo - 1};
        } catch (final NumberFormatException e) {
            context.warn("Error fetching meta stream for streamId " + id + " " + e.getMessage());
            return null;
        }
    }
}
