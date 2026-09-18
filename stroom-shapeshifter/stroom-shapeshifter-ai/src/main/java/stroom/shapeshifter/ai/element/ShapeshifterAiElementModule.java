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

package stroom.shapeshifter.ai.element;

import stroom.pipeline.factory.PipelineElementModule;
import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.stage.Ledger;
import stroom.shapeshifter.ai.stage.Outputs;
import stroom.shapeshifter.ai.stage.RegressionSet;
import stroom.shapeshifter.ai.stage.Reprocessing;
import stroom.shapeshifter.ai.stage.Shapes;
import stroom.shapeshifter.ai.state.InMemoryLedger;
import stroom.shapeshifter.ai.state.InMemoryOutputs;
import stroom.shapeshifter.ai.state.InMemoryRegressionSet;
import stroom.shapeshifter.ai.state.InMemoryShapes;

import com.google.inject.Scopes;
import com.google.inject.multibindings.OptionalBinder;

/**
 * The supervisor element and what it runs over. The advisor is an optional binding with the no-model
 * default, so that a test — or, later, the module that wires {@code stroom-ai} in (design 01 §12 item 6)
 * — can set it. The runtime state of A26 is bound to the in-memory implementations until the module
 * of §12 item 8 replaces them; a reprocess request is real already, a reprocess filter on the pipeline.
 */
public class ShapeshifterAiElementModule extends PipelineElementModule {

    @Override
    protected void configure() {
        super.configure();
        OptionalBinder.newOptionalBinder(binder(), Advisor.class).setDefault().to(NoModelAdvisor.class);
        bind(Shapes.class).to(InMemoryShapes.class).in(Scopes.SINGLETON);
        bind(Ledger.class).to(InMemoryLedger.class).in(Scopes.SINGLETON);
        bind(Outputs.class).to(InMemoryOutputs.class).in(Scopes.SINGLETON);
        bind(Reprocessing.class).to(PipelineReprocessing.class);
        bind(RegressionSet.class).to(InMemoryRegressionSet.class).in(Scopes.SINGLETON);
    }

    @Override
    protected void configureElements() {
        bindElement(ShapeshifterAiParser.class);
        bindElement(FragmentOutputFilter.class);
    }
}
