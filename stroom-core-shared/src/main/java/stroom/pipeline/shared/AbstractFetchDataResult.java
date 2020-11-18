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

package stroom.pipeline.shared;


import stroom.util.shared.OffsetRange;
import stroom.util.shared.RowCount;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Set;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = FetchDataResult.class, name = "data"),
        @JsonSubTypes.Type(value = FetchMarkerResult.class, name = "marker")
})
@JsonInclude(Include.NON_NULL)
public abstract class AbstractFetchDataResult {

    @JsonProperty
    private final String feedName;
    @JsonProperty
    private final String streamTypeName;
    @JsonProperty
    private final String classification;
    @JsonProperty
    private final SourceLocation sourceLocation;
    @JsonProperty
    private final OffsetRange<Long> itemRange; // part/segment/marker
    @JsonProperty
    private final RowCount<Long> totalItemCount; // part/segment/marker
    @JsonProperty
    private final RowCount<Long> totalCharacterCount; // Total chars in part/segment
    @JsonProperty
    private final Set<String> availableChildStreamTypes;

    @JsonCreator
    public AbstractFetchDataResult(@JsonProperty("feedName") final String feedName,
                                   @JsonProperty("streamTypeName") final String streamTypeName,
                                   @JsonProperty("classification") final String classification,
                                   @JsonProperty("sourceLocation") final SourceLocation sourceLocation,
                                   @JsonProperty("itemRange") final OffsetRange<Long> itemRange,
                                   @JsonProperty("totalItemCount") final RowCount<Long> totalItemCount,
                                   @JsonProperty("totalCharacterCount") final RowCount<Long> totalCharacterCount,
                                   @JsonProperty("availableChildStreamTypes") final Set<String> availableChildStreamTypes) {
        this.feedName = feedName;
        this.streamTypeName = streamTypeName;
        this.classification = classification;
        this.sourceLocation = sourceLocation;
        this.itemRange = itemRange;
        this.totalItemCount = totalItemCount;
        this.totalCharacterCount = totalCharacterCount;
        this.availableChildStreamTypes = availableChildStreamTypes;
    }

    public String getFeedName() {
        return feedName;
    }

    public String getStreamTypeName() {
        return streamTypeName;
    }

    public String getClassification() {
        return classification;
    }

    public SourceLocation getSourceLocation() {
        return sourceLocation;
    }

    public OffsetRange<Long> getItemRange() {
        return itemRange;
    }

    public RowCount<Long> getTotalItemCount() {
        return totalItemCount;
    }

    public RowCount<Long> getTotalCharacterCount() {
        return totalCharacterCount;
    }

    public Set<String> getAvailableChildStreamTypes() {
        return availableChildStreamTypes;
    }
}
