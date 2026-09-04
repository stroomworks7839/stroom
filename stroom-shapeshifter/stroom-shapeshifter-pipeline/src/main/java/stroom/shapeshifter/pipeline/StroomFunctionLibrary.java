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

package stroom.shapeshifter.pipeline;

import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.function.FunctionRegistry;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Set;

/**
 * The functions a Shapeshifter configuration may call under Stroom (design 26 §5): every
 * {@link FunctionDefinition} the Guice multibinder collected, as one registry the parser
 * factory pool compiles against. The counterpart of {@code StroomXsltFunctionLibrary}.
 */
@Singleton
public class StroomFunctionLibrary {

    private final FunctionRegistry registry;

    @Inject
    public StroomFunctionLibrary(final Set<FunctionDefinition> definitions) {
        this.registry = FunctionRegistry.of(definitions);
    }

    public FunctionRegistry registry() {
        return registry;
    }
}
