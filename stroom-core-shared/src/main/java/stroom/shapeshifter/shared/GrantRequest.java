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

package stroom.shapeshifter.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/// What a person grants a parked session beyond the document's budgets (A77): tokens, milliseconds, or
/// both, recorded against the attempt alone.
@JsonInclude(Include.NON_NULL)
public class GrantRequest {

    @JsonProperty
    private final long tokens;
    @JsonProperty
    private final long ms;

    @JsonCreator
    public GrantRequest(@JsonProperty("tokens") final long tokens, @JsonProperty("ms") final long ms) {
        this.tokens = tokens;
        this.ms = ms;
    }

    public long getTokens() {
        return tokens;
    }

    public long getMs() {
        return ms;
    }
}
