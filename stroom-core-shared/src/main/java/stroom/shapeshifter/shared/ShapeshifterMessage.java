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

/** Something the engine had to say about a configuration: its severity, as the engine spells it, and the text. */
@JsonInclude(Include.NON_NULL)
public class ShapeshifterMessage {

    @JsonProperty
    private final String severity;
    @JsonProperty
    private final String text;

    @JsonCreator
    public ShapeshifterMessage(@JsonProperty("severity") final String severity,
                               @JsonProperty("text") final String text) {
        this.severity = severity;
        this.text = text;
    }

    public String getSeverity() {
        return severity;
    }

    public String getText() {
        return text;
    }
}
