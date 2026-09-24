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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ask for a shape to be learned now (A28, design 01 §11.6), rather than when its feed next ships.
 * <p>
 * This is what the ruling means by raising an attempt for a given-up shape from the Supervisor, and why
 * it says no on-request learning mode is needed: the shape is sent back to be learned, and the streams
 * waiting on the ledger for it — which are waiting precisely because nothing bound it — are asked to be
 * processed again, so the first of them through learns it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LearnShapeRequest {

    @JsonProperty
    private final String shapeId;
    @JsonProperty
    private final String reason;

    @JsonCreator
    public LearnShapeRequest(@JsonProperty("shapeId") final String shapeId,
                             @JsonProperty("reason") final String reason) {
        this.shapeId = shapeId;
        this.reason = reason;
    }

    /**
     * Which shape: the learning key's values, as the ledger names them.
     */
    public String getShapeId() {
        return shapeId;
    }

    /**
     * Why, in the person's words. It opens the next attempt and travels with every stream asked for.
     */
    public String getReason() {
        return reason;
    }
}
