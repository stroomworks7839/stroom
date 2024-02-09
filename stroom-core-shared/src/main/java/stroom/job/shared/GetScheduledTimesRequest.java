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

package stroom.job.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(Include.NON_NULL)
public class GetScheduledTimesRequest {

    @JsonProperty
    private final Schedule schedule;
    @JsonProperty
    private final Long scheduleReferenceTime;
    @JsonProperty
    private final Long lastExecutedTime;

    @JsonCreator
    public GetScheduledTimesRequest(@JsonProperty("schedule") final Schedule schedule,
                                    @JsonProperty("scheduleReferenceTime") final Long scheduleReferenceTime,
                                    @JsonProperty("lastExecutedTime") final Long lastExecutedTime) {
        this.schedule = schedule;
        this.scheduleReferenceTime = scheduleReferenceTime;
        this.lastExecutedTime = lastExecutedTime;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public Long getScheduleReferenceTime() {
        return scheduleReferenceTime;
    }

    public Long getLastExecutedTime() {
        return lastExecutedTime;
    }
}
