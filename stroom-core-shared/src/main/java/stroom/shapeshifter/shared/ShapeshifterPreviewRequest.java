/*
 * Copyright 2016 Crown Copyright
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
 * A project to run over a sample, once, whole, recording everything (design 43 §5). The sample is
 * supplied, never stored (design 18 Q2): the client got it from a stream or a step.
 */
@JsonInclude(Include.NON_NULL)
public class ShapeshifterPreviewRequest {

    @JsonProperty
    private final String project;
    @JsonProperty
    private final String sample;

    @JsonCreator
    public ShapeshifterPreviewRequest(@JsonProperty("project") final String project,
                                      @JsonProperty("sample") final String sample) {
        this.project = project;
        this.sample = sample;
    }

    public String getProject() {
        return project;
    }

    public String getSample() {
        return sample;
    }
}
