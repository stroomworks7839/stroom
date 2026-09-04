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
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;

/** A Shapeshifter variant of a Stroom XSLT function: its name, signature and purity, once. */
abstract class StroomFunction implements FunctionDefinition {

    private final String name;
    private final Signature signature;
    private final Purity purity;

    protected StroomFunction(final String name, final Signature signature, final Purity purity) {
        this.name = name;
        this.signature = signature;
        this.purity = purity;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final Signature signature() {
        return signature;
    }

    @Override
    public final Purity purity() {
        return purity;
    }

    @Override
    public String toString() {
        return name;
    }

    /** A string result, or absent for null. */
    protected static TypedValue text(final String value) {
        return value == null ? null : TypedValue.of(value);
    }

    /**
     * A required string argument. Stroom's {@code getSafeString} warns "illegal non string
     * argument" when nothing usable is there; here the same warning, and the caller treats null
     * as "stop, absent".
     */
    protected String requiredString(final FunctionContext context, final Arguments arguments, final int index) {
        final String value = arguments.string(index);
        if (value == null) {
            context.warn("Illegal non string argument found in function " + name + "() at position " + index);
        }
        return value;
    }
}
