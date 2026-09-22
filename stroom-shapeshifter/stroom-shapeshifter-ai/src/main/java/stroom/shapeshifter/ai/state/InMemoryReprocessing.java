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

import stroom.shapeshifter.ai.stage.Reprocessing;

import java.util.ArrayList;
import java.util.List;

/**
 * Reprocess requests as a list, for a scenario to inspect; a node creates a reprocess filter instead:
 * what scenarios run over, and what a node runs over until the A26 module exists — node-local and gone
 * on restart.
 Every method is synchronised: one node's tasks share it.
 */
public final class InMemoryReprocessing implements Reprocessing {

    private final List<Request> requests = new ArrayList<>();

    @Override
    public synchronized void request(final String docUuid,
                                     final String pipeline,
                                     final String reason,
                                     final List<Long> inputIds) {
        requests.add(new Request(docUuid, pipeline, reason, List.copyOf(inputIds)));
    }

    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    public record Request(String docUuid, String pipeline, String reason, List<Long> inputIds) {

    }
}
