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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.Bindings;
import stroom.shapeshifter.ai.stage.Outputs;
import stroom.shapeshifter.ai.stage.Replayable;

import java.util.ArrayList;
import java.util.List;

/**
 * The bindings of every output as a list: what scenarios run over, and what a node runs over until the
 * A26 module exists — node-local and gone on restart.
 Every method is synchronised: one node's tasks share it.
 */
public final class InMemoryOutputs implements Outputs {

    private final List<Emitted> emitted = new ArrayList<>();

    @Override
    public synchronized void emitted(final long inputId, final String pipeline, final Bindings bindings) {
        // One row per input per rule, as the table has it: a stream processed twice under one rule is one
        // thing to replay.
        emitted.removeIf(output -> output.inputId() == inputId
                                   && output.bindings().ruleUuid().equals(bindings.ruleUuid()));
        emitted.add(new Emitted(inputId, pipeline, bindings));
    }

    @Override
    public synchronized List<Replayable> boundBy(final String ruleUuid, final String fragmentUuid) {
        return emitted.stream()
                .filter(output -> output.bindings().ruleUuid().equals(ruleUuid))
                .filter(output -> fragmentUuid == null
                                  || fragmentUuid.equals(output.bindings().fragment().getUuid()))
                .map(output -> new Replayable(output.inputId(), output.pipeline()))
                .toList();
    }

    @Override
    public synchronized int prune(final long producedBeforeMs) {
        // Nothing in memory is old: a node's own list lasts as long as the node does, and what a node
        // holds is bounded by that.
        return 0;
    }

    public synchronized List<Emitted> emitted() {
        return List.copyOf(emitted);
    }

    public record Emitted(long inputId, String pipeline, Bindings bindings) {

    }
}
