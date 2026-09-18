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

/**
 * Something the engine had to say: its severity, as the engine spells it, the text, and - from
 * a run - the frame it was said in (design 18 §5.8), or {@link #NO_FRAME} from a validation.
 */
@JsonInclude(Include.NON_NULL)
public class ShapeshifterMessage {

    public static final long NO_FRAME = -1;

    @JsonProperty
    private final String severity;
    @JsonProperty
    private final String text;
    @JsonProperty
    private final long frameId;

    public ShapeshifterMessage(final String severity, final String text) {
        this(severity, text, NO_FRAME);
    }

    @JsonCreator
    public ShapeshifterMessage(@JsonProperty("severity") final String severity,
                               @JsonProperty("text") final String text,
                               @JsonProperty("frameId") final long frameId) {
        this.severity = severity;
        this.text = text;
        this.frameId = frameId;
    }

    public long getFrameId() {
        return frameId;
    }

    public String getSeverity() {
        return severity;
    }

    public String getText() {
        return text;
    }
}
