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
 * A pattern for the engine to look at: a regex for {@code patternInfo} and {@code explode}, or
 * a pattern tree in its wire form for {@code print}, under the flags the template sets.
 */
@JsonInclude(Include.NON_NULL)
public class ShapeshifterPatternRequest {

    @JsonProperty
    private final String pattern;
    @JsonProperty
    private final boolean caseInsensitive;
    @JsonProperty
    private final boolean dotAll;

    @JsonCreator
    public ShapeshifterPatternRequest(@JsonProperty("pattern") final String pattern,
                                      @JsonProperty("caseInsensitive") final boolean caseInsensitive,
                                      @JsonProperty("dotAll") final boolean dotAll) {
        this.pattern = pattern;
        this.caseInsensitive = caseInsensitive;
        this.dotAll = dotAll;
    }

    public String getPattern() {
        return pattern;
    }

    public boolean isCaseInsensitive() {
        return caseInsensitive;
    }

    public boolean isDotAll() {
        return dotAll;
    }
}
