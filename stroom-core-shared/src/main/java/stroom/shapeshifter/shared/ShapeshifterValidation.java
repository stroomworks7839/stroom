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

import java.util.List;

/**
 * The engine's verdict on a project's text without running it: read and compiled, with every
 * message that produced, and — when it read — the project printed back in the engine's own
 * form, which is what the Source tab's format action shows (design 43 §3).
 */
@JsonInclude(Include.NON_NULL)
public class ShapeshifterValidation {

    @JsonProperty
    private final boolean valid;
    @JsonProperty
    private final List<ShapeshifterMessage> messages;
    @JsonProperty
    private final String canonical;

    @JsonCreator
    public ShapeshifterValidation(@JsonProperty("valid") final boolean valid,
                                  @JsonProperty("messages") final List<ShapeshifterMessage> messages,
                                  @JsonProperty("canonical") final String canonical) {
        this.valid = valid;
        this.messages = messages;
        this.canonical = canonical;
    }

    public boolean isValid() {
        return valid;
    }

    public List<ShapeshifterMessage> getMessages() {
        return messages;
    }

    public String getCanonical() {
        return canonical;
    }
}
