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

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;

/**
 * A Guice module that contributes Shapeshifter functions: {@code bindFunction} each definition
 * class, and {@link StroomFunctionLibrary} collects them (design 26 §5). The counterpart of
 * {@code AbstractXsltFunctionModule}.
 */
public abstract class AbstractShapeshifterFunctionModule extends AbstractModule {

    private Multibinder<FunctionDefinition> multibinder;

    @Override
    protected void configure() {
        multibinder = Multibinder.newSetBinder(binder(), FunctionDefinition.class);
        configureFunctions();
    }

    protected abstract void configureFunctions();

    protected void bindFunction(final Class<? extends FunctionDefinition> definition) {
        multibinder.addBinding().to(definition);
    }
}
