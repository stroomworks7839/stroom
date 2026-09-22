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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// What one record is in a stream that arrives as, or is parsed into, markup (A31, A35): the local name of
/// the element that is one record where the input is XML, or the key of the array whose items are records
/// where it is JSON — [#ROOT] where every top-level value is one. A rule carries the boundary its variant
/// was learned with, so that the stage counts the stream's records and judges yield by it rather than by
/// the root's children; raw text has none, since the parser's configuration cuts it.
@JsonPropertyOrder({"element", "array"})
@JsonInclude(Include.NON_NULL)
public final class RecordBoundary {

    /// The JSON boundary where every top-level value is one record, as JSON lines are.
    public static final String ROOT = "root";

    @JsonProperty
    private final String element;
    @JsonProperty
    private final String array;

    @JsonCreator
    public RecordBoundary(@JsonProperty("element") final String element,
                          @JsonProperty("array") final String array) {
        if ((element == null) == (array == null)) {
            throw new IllegalArgumentException("A record boundary is an element or an array, one of them");
        }
        this.element = element;
        this.array = array;
    }

    /// The boundary of input that is already XML: the element that is one record.
    public static RecordBoundary ofElement(final String element) {
        return new RecordBoundary(element, null);
    }

    /// The boundary of JSON: the key of the array whose items are records, or [#ROOT].
    public static RecordBoundary ofArray(final String array) {
        return new RecordBoundary(null, array);
    }

    /// The local name of the element that is one record, or null where the boundary is an array.
    public String getElement() {
        return element;
    }

    /// The key of the array whose items are records, [#ROOT], or null where the boundary is an element.
    public String getArray() {
        return array;
    }

    /// Whether the boundary is a JSON array — [#getArray()] is set — rather than an element.
    @JsonIgnore
    public boolean isArray() {
        return array != null;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RecordBoundary that = (RecordBoundary) o;
        return Objects.equals(element, that.element) && Objects.equals(array, that.array);
    }

    @Override
    public int hashCode() {
        return Objects.hash(element, array);
    }

    @Override
    public String toString() {
        return element != null
                ? "element " + element
                : "array " + array;
    }
}
