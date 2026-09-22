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
 * Why a person rejected what an attempt drafted (A25, A28): the reason is the shape's, and the model is
 * not asked about that shape again until somebody says otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RejectAttemptRequest {

    @JsonProperty
    private final String reason;

    @JsonCreator
    public RejectAttemptRequest(@JsonProperty("reason") final String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
