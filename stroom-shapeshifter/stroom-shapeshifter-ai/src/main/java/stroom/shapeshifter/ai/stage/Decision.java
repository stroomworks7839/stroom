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
     * A bound shape marked for relearning (A29) was relearned while the incumbent served the stream, and
     * the candidate beat it: the rule is rebound to the new fragment (design 01 §7.3 rule 2) for the
     * streams that follow. Same rule, same {@code uuid}; the regression set grows.
     */
    record Rebound(RoutingRule incumbent, RoutingRule rule, double score) implements Decision {

    }

    /**
     * A bound shape marked for relearning was relearned while the incumbent served the stream, and the
     * incumbent was kept: no candidate passed, or the candidate did not beat it on this stream or on the
     * regression set (A15, A18). Nothing was written.
     */
    record Kept(RoutingRule incumbent, String reason) implements Decision {

    }

    /**
     * The document is in review mode (A25): a candidate that would have been bound is written as a draft
     * rule the router does not bind, and waits for Approve or Reject. For a new shape the stream is
     * sentinelled and on the ledger, released when the draft is approved; for a relearned shape the
     * incumbent served the stream and the draft sits behind it in the table.
     */
    record Drafted(RoutingRule rule, double score) implements Decision {

    }

    /**
     * A provisional rule failed the gate once the shape brought enough records to judge it (design 01 §6):
     * the rule is gone from the table, the shape is unknown again, the inputs whose output it produced
     * are requested for reprocessing as-current, and this stream is sentinelled.
     */
    record Retracted(RoutingRule rule, double score, String reason) implements Decision {

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
