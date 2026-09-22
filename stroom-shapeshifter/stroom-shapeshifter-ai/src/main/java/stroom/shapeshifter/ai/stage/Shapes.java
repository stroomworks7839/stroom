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

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The shape rows of ruling A26, one per document and learning-key value: whether the shape is given up
 * and why, whether it is marked for relearning and why, the rolling per-record score of design 01 §5
 * that decides the mark, and the draft rule awaiting review for it (A25). In-memory in scenarios; the
 * {@code shapeshifter_shape} table in a node.
 */
public interface Shapes {

    Optional<String> reasonGivenUp(String docUuid, String shape);

    void giveUp(String docUuid, String shape, String reason);

    /**
     * Fold one served stream into the shape's rolling score. The score counts once per record the stream
     * brought, against a memory of at most {@code memory} earlier records, so a shape that has been good
     * for a year is judged on what it has done lately.
     *
     * @return The rolling score, once the shape has brought at least {@code memory} records since the row
     * was last reset; empty before that, when it would rest on too little to act on.
     */
    OptionalDouble scored(String docUuid, String shape, double score, int records, int memory);

    Optional<String> relearnReason(String docUuid, String shape);

    void markForRelearning(String docUuid, String shape, String reason);

    /**
     * A draft rule was written for the shape and waits for Approve or Reject (A25).
     */
    void awaitReview(String docUuid, String shape, String ruleUuid);

    /**
     * @return The draft rule awaiting review for the shape, if there is one.
     */
    Optional<String> draftAwaiting(String docUuid, String shape);

    /**
     * @return The shape whose draft this rule is, if it is one.
     */
    Optional<String> shapeAwaiting(String docUuid, String ruleUuid);

    /**
     * The shape is bound, or unknown again: not given up, not marked, nothing awaiting review, and its
     * rolling score starts afresh.
     */
    void reset(String docUuid, String shape);

    /**
     * Take the learning lease for a shape (A42): one learner at a time, across the cluster. A node that
     * does not win it does not wait — waiting would hold a processing thread for minutes, and at hundreds
     * of threads a shape's first minute would stall the cluster — it sentinels its stream and returns, and
     * the winner's promotion releases the backlog.
     *
     * @param node   Which node is asking, so that a lease can be read back and reported.
     * @param untilMs When the lease expires if the holder neither finishes nor extends it, which is how a
     *                node that died mid-attempt lets the next one in.
     * @return Whether this node now holds it.
     */
    boolean lease(String docUuid, String shape, String node, long untilMs);

    /**
     * Give the lease back, whatever the attempt came to.
     */
    void releaseLease(String docUuid, String shape, String node);
}
