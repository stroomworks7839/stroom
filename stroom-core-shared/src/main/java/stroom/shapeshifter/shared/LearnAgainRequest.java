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
 * Ask for a learner to learn now (A28, design 01 §11.6), rather than when its feed next ships.
 * <p>
 * This is what the ruling means by raising an attempt for a learner that gave up from the Supervisor, and
 * why it says no on-request learning mode is needed: the learner is sent back to learn, and the streams
 * waiting on the ledger for it — which are waiting precisely because nothing served them — are asked to
 * be processed again, so the first of them through learns.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LearnAgainRequest {

    @JsonProperty
    private final LearnerRef learner;
    @JsonProperty
    private final String reason;

    @JsonCreator
    public LearnAgainRequest(@JsonProperty("learner") final LearnerRef learner,
                             @JsonProperty("reason") final String reason) {
        this.learner = learner;
        this.reason = reason;
    }

    /// The learner to learn again (A128), as the ledger row named it.
    public LearnerRef getLearner() {
        return learner;
    }

    /**
     * Why, in the person's words. It opens the next attempt and travels with every stream asked for.
     */
    public String getReason() {
        return reason;
    }
}
