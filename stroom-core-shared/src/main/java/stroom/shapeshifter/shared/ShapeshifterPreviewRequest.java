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

import stroom.pipeline.shared.SourceLocation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A project to run over a sample, once, whole, recording everything (design 43 §5). The sample is
 * supplied, never stored (design 18 Q2): either the text itself — pasted — or the record it is
 * to be read from, which the server reads from the stream store under the caller's permissions
 * (design 44 §5). One or the other; the text wins if both are given.
 */
@JsonInclude(Include.NON_NULL)
public class ShapeshifterPreviewRequest {

    @JsonProperty
    private final String project;
    @JsonProperty
    private final String sample;
    @JsonProperty
    private final SourceLocation sourceLocation;

    public ShapeshifterPreviewRequest(final String project, final String sample) {
        this(project, sample, null);
    }

    @JsonCreator
    public ShapeshifterPreviewRequest(@JsonProperty("project") final String project,
                                      @JsonProperty("sample") final String sample,
                                      @JsonProperty("sourceLocation") final SourceLocation sourceLocation) {
        this.project = project;
        this.sample = sample;
        this.sourceLocation = sourceLocation;
    }

    public String getProject() {
        return project;
    }

    public String getSample() {
        return sample;
    }

    /** The record the sample is read from, or null when the sample is the text above. */
    public SourceLocation getSourceLocation() {
        return sourceLocation;
    }
}
