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

package stroom.shapeshifter.engine.function;

import java.util.Objects;
import java.util.function.Function;

/**
 * A function as the registry knows it (design 26 §2): its name, its signature, its purity, and
 * how to bind it to a run. The counterpart of Stroom's {@code StroomExtensionFunctionDefinition}.
 */
public interface FunctionDefinition {

    /** The name a configuration calls: {@code "hex-to-dec"}. */
    String name();

    Signature signature();

    Purity purity();

    /** Once per run: the instance the run's calls go to. */
    FunctionCall bind(FunctionContext context);

    /** A definition from its parts, for functions with no state of their own. */
    static FunctionDefinition of(final String name,
                                 final Signature signature,
                                 final Purity purity,
                                 final Function<FunctionContext, FunctionCall> binder) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(purity, "purity");
        Objects.requireNonNull(binder, "binder");
        return new FunctionDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Signature signature() {
                return signature;
            }

            @Override
            public Purity purity() {
                return purity;
            }

            @Override
            public FunctionCall bind(final FunctionContext context) {
                return binder.apply(context);
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }
}
