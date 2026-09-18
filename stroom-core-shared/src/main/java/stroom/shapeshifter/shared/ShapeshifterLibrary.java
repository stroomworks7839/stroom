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

import java.util.List;

/**
 * The regex module's standard library — what a pattern tree's {@code ref} names — each entry
 * with the regex it means, for the tree editor's picker and its read-only library pane.
 */
@JsonInclude(Include.NON_NULL)
public class ShapeshifterLibrary {

    @JsonProperty
    private final List<Entry> entries;

    @JsonCreator
    public ShapeshifterLibrary(@JsonProperty("entries") final List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    @JsonInclude(Include.NON_NULL)
    public static class Entry {

        @JsonProperty
        private final String name;
        @JsonProperty
        private final String regex;

        @JsonCreator
        public Entry(@JsonProperty("name") final String name,
                     @JsonProperty("regex") final String regex) {
            this.name = name;
            this.regex = regex;
        }

        public String getName() {
            return name;
        }

        public String getRegex() {
            return regex;
        }
    }
}
