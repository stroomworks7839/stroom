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
 * Which serving bindings the Supervisor is asking for (A46): across every document by default, ordered by
 * the traffic each carries, and narrowed by the one thing a person browsing for something to improve
 * would narrow by — how well it has been scoring lately.
 * <p>
 * There is no sort list to choose from. Traffic is the order, because the binding worth an hour is the one
 * carrying the most streams; a score is what filters the list rather than what arranges it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServingCriteria extends BaseCriteria {

    @JsonProperty
    private final String docUuid;
    @JsonProperty
    private final Double below;
    @JsonProperty
    private final boolean showDeletedPipelines;

    public ServingCriteria() {
        this(null, null, null, null, false);
    }

    @JsonCreator
    public ServingCriteria(@JsonProperty("pageRequest") final PageRequest pageRequest,
                           @JsonProperty("sortList") final List<CriteriaFieldSort> sortList,
                           @JsonProperty("docUuid") final String docUuid,
                           @JsonProperty("below") final Double below,
                           @JsonProperty("showDeletedPipelines") final boolean showDeletedPipelines) {
        super(pageRequest, sortList);
        this.showDeletedPipelines = showDeletedPipelines;
        this.docUuid = docUuid;
        this.below = below;
    }

    /**
     * One document's bindings, or every document this person may read when null.
     */
    public String getDocUuid() {
        return docUuid;
    }

    /**
     * Only bindings whose learner has been scoring below this, or all of them when null. A binding that has
     * served nothing yet has no score and is left out when a threshold is given.
     */
    public Double getBelow() {
        return below;
    }

    /// Whether what served pipelines since deleted is shown too (design 06 A137): kept to be read until it
    /// ages out, and left out unless a person asks for it.
    public boolean isShowDeletedPipelines() {
        return showDeletedPipelines;
    }
}
