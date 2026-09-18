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
 * What the engine says about a regex (design 18 §4): whether it compiles, the error if not, its
 * groups by number and name, and the engine's own account of how it would run it.
 */
@JsonInclude(Include.NON_NULL)
public class ShapeshifterPatternInfo {

    @JsonProperty
    private final boolean valid;
    @JsonProperty
    private final String error;
    @JsonProperty
    private final List<Group> groups;
    @JsonProperty
    private final String explain;

    @JsonCreator
    public ShapeshifterPatternInfo(@JsonProperty("valid") final boolean valid,
                                   @JsonProperty("error") final String error,
                                   @JsonProperty("groups") final List<Group> groups,
                                   @JsonProperty("explain") final String explain) {
        this.valid = valid;
        this.error = error;
        this.groups = groups;
        this.explain = explain;
    }

    public boolean isValid() {
        return valid;
    }

    public String getError() {
        return error;
    }

    public List<Group> getGroups() {
        return groups;
    }

    public String getExplain() {
        return explain;
    }

    /** A capture group: its number, and its name where it has one. */
    @JsonInclude(Include.NON_NULL)
    public static class Group {

        @JsonProperty
        private final int index;
        @JsonProperty
        private final String name;
        @JsonProperty
        private final int start;
        @JsonProperty
        private final int end;

        @JsonCreator
        public Group(@JsonProperty("index") final int index,
                     @JsonProperty("name") final String name,
                     @JsonProperty("start") final int start,
                     @JsonProperty("end") final int end) {
            this.index = index;
            this.name = name;
            this.start = start;
            this.end = end;
        }

        public int getIndex() {
            return index;
        }

        public String getName() {
            return name;
        }

        /** Where the group's '(' is in the pattern text. */
        public int getStart() {
            return start;
        }

        /** Just past the group's ')'. */
        public int getEnd() {
            return end;
        }
    }
}
