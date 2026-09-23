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
     * What the shape has been scoring lately and over how many records, for a person reading a list of
     * what is serving (A46) rather than for the stage. {@link #scored} answers only once the memory is
     * full, because it decides whether to act; this answers whatever the row holds, because a list that
     * showed nothing until a shape had brought a hundred records would be empty on the feeds that matter
     * least often.
     *
     * @return Empty where the shape has no row, or has one that has never been scored.
     */
    Optional<Rolling> rolling(String docUuid, String shape);


    // --------------------------------------------------------------------------------


    /**
     * A shape's rolling score and the traffic behind it.
     *
     * @param score   The mean per-record score over the rolling memory (design 01 §5).
     * @param records How many records that mean rests on, capped at the memory.
     */
    record Rolling(double score, int records) {

    }
}
