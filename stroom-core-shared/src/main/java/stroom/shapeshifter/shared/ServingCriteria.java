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

import stroom.util.shared.BaseCriteria;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.PageRequest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Which serving rules the Supervisor is asking for (A46): across every document by default, ordered by
 * the traffic each carries, and narrowed by the one thing a person browsing for something to improve
 * would narrow by — how well it has been scoring lately.
 * <p>
 * There is no sort list to choose from. Traffic is the order, because the rule worth an hour is the one
 * carrying the most streams; a score is what filters the list rather than what arranges it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServingCriteria extends BaseCriteria {

    @JsonProperty
    private final String docUuid;
    @JsonProperty
    private final Double below;

    public ServingCriteria() {
        this(null, null, null, null);
    }

    @JsonCreator
    public ServingCriteria(@JsonProperty("pageRequest") final PageRequest pageRequest,
                           @JsonProperty("sortList") final List<CriteriaFieldSort> sortList,
                           @JsonProperty("docUuid") final String docUuid,
                           @JsonProperty("below") final Double below) {
        super(pageRequest, sortList);
        this.docUuid = docUuid;
        this.below = below;
    }

    /**
     * One document's rules, or every document this person may read when null.
     */
    public String getDocUuid() {
        return docUuid;
    }

    /**
     * Only rules whose shape has been scoring below this, or all of them when null. A shape that has
     * served nothing yet has no score and is left out when a threshold is given.
     */
    public Double getBelow() {
        return below;
    }
}
