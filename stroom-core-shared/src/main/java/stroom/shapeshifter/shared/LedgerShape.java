/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.shared;

import stroom.docref.DocRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One shape's place on the ledger (design 01 §5.2, A26, A28): how many streams are waiting for it to
 * settle, since when, and what the last of them was told.
 * <p>
 * Nothing is held — every stream on the ledger was processed to an error stream and is where it always
 * was — so this is a list of what a promotion would release, not a queue of anything being kept. Grouped
 * by shape because that is the unit that settles: one promotion takes a whole shape off.
 */
@JsonInclude(Include.NON_NULL)
public class LedgerShape {

    @JsonProperty
    private final DocRef doc;
    @JsonProperty
    private final String shapeId;
    @JsonProperty
    private final int waiting;
    @JsonProperty
    private final Long oldestTimeMs;
    @JsonProperty
    private final Long newestTimeMs;
    @JsonProperty
    private final String reason;

    @JsonCreator
    public LedgerShape(@JsonProperty("doc") final DocRef doc,
                       @JsonProperty("shapeId") final String shapeId,
                       @JsonProperty("waiting") final int waiting,
                       @JsonProperty("oldestTimeMs") final Long oldestTimeMs,
                       @JsonProperty("newestTimeMs") final Long newestTimeMs,
                       @JsonProperty("reason") final String reason) {
        this.doc = doc;
        this.shapeId = shapeId;
        this.waiting = waiting;
        this.oldestTimeMs = oldestTimeMs;
        this.newestTimeMs = newestTimeMs;
        this.reason = reason;
    }

    /**
     * Whose ledger it is. The view is over every document (A28), so a shape's document is part of what
     * it is: two documents may have shapes of the same name and they settle separately.
     */
    public DocRef getDoc() {
        return doc;
    }

    /**
     * The shape as the ledger keys it: the learning key's values, e.g. {@code Feed=SYSLOG|Type=Raw Events}.
     */
    public String getShapeId() {
        return shapeId;
    }

    /**
     * How many streams are on the ledger for it.
     */
    public int getWaiting() {
        return waiting;
    }

    /**
     * When the first of them was sentinelled, which is how long this shape has been waiting.
     */
    public Long getOldestTimeMs() {
        return oldestTimeMs;
    }

    public Long getNewestTimeMs() {
        return newestTimeMs;
    }

    /**
     * What the most recent of them was told: the shape is unknown, is being learned elsewhere, is given
     * up, or has a draft awaiting review (A4, A25).
     */
    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return shapeId + ": " + waiting + " waiting";
    }
}
