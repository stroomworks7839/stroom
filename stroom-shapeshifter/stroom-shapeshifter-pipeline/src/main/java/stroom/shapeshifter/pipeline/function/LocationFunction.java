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
import stroom.shapeshifter.pipeline.RunLocations;

/**
 * Where the running match is, in lines and columns: what {@code line-from} and its siblings
 * answer from a {@code LocationHolder} in XSLT, answered here from the engine's own offsets
 * through the run's line index. {@code from} is the match's first byte, {@code to} its last.
 */
abstract class LocationFunction extends StroomFunction {

    enum Edge { FROM, TO }

    enum Axis { LINE, COLUMN }

    private final Edge edge;
    private final Axis axis;

    LocationFunction(final String name, final Edge edge, final Axis axis) {
        super(name, Signature.of(Kind.STRING), Purity.CONTEXT);
        this.edge = edge;
        this.axis = axis;
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final RunLocations locations = context.service(RunLocations.class);
            final long offset = edge == Edge.FROM ? from(context) : to(context);
            if (locations == null || offset < 0) {
                return null;
            }
            final int value = axis == Axis.LINE ? locations.line(offset) : locations.column(offset);
            return value < 0 ? null : text(String.valueOf(value));
        };
    }

    static long from(final FunctionContext context) {
        final long offset = context.inputOffset();
        return offset >= Long.MAX_VALUE / 2 ? -1 : offset;
    }

    static long to(final FunctionContext context) {
        final long from = from(context);
        final long length = context.inputLength();
        return from < 0 || length < 0 ? -1 : from + Math.max(0, length - 1);
    }
}
