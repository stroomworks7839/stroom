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

package stroom.shapeshifter.ai.stage;

import stroom.shapeshifter.shared.RoutingRule;

/**
 * What the stage decided about one stream, in the terms of design 02 §4.
 */
public sealed interface Decision {

    /**
     * A routing rule matched and bound a fragment; the stream was processed with it, no learning.
     */
    record Bound(RoutingRule rule) implements Decision {

    }

    /**
     * A fragment was learned and promoted, and the routing table now carries this rule for it.
     */
    record Promoted(RoutingRule rule, double score) implements Decision {

    }

    /**
     * A fragment was learned but the incumbent was kept: the candidate did not beat it (A15, A18).
     */
    record Kept(RoutingRule incumbent, double candidateScore) implements Decision {

    }

    /**
     * A variant cleared the floor on the shape's records but there were too few for a held-out split
     * (A14), so it is bound provisionally (A5, design 01 §6): it handles this stream and every one after
     * it, marked as such, until the shape has {@code required} records and the gate can be met.
     */
    record Provisional(RoutingRule rule, double score, int records, int required) implements Decision {

    }

    /**
     * No fragment passed within the budget; the shape is recorded as given up.
     */
    record GivenUp(String reason) implements Decision {

    }

    /**
     * The stream was not processed and the model was not consulted: the shape is given up, reserved by
     * a rule, awaiting review as a draft, lacks a value for a key field, or learning is disabled and no
     * bound variant fits. The stream is sentinelled — an error stream and a ledger row (A4).
     */
    record Sentinel(String reason) implements Decision {

    }
}
