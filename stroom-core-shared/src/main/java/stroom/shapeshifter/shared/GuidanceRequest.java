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
 * Something a supervisor wants the learning to know about a shape (A46), given without asking for
 * anything to be run: the feed's own explanation of a field, a correction to what the model assumed, a
 * fact the sample does not show.
 * <p>
 * Separate from {@link ImproveRequest}, which says the same thing and then asks for an attempt. A person
 * who has just learned something about a feed should be able to write it down at once, and have it
 * carried into whatever is asked next — which may be months away.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GuidanceRequest {

    @JsonProperty
    private final String shapeId;
    @JsonProperty
    private final String message;

    @JsonCreator
    public GuidanceRequest(@JsonProperty("shapeId") final String shapeId,
                           @JsonProperty("message") final String message) {
        this.shapeId = shapeId;
        this.message = message;
    }

    /**
     * Which shape it is about: the learning key's values, as a rule or a ledger row shows them.
     */
    public String getShapeId() {
        return shapeId;
    }

    public String getMessage() {
        return message;
    }
}
