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

package stroom.shapeshifter.config.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A JSON array. */
public final class JsonArray implements JsonValue {

    private final List<JsonValue> elements = new ArrayList<>();

    @Override
    public String shape() {
        return "array";
    }

    @Override
    public boolean isArray() {
        return true;
    }

    @Override
    public int size() {
        return elements.size();
    }

    public JsonValue get(final int index) {
        return elements.get(index);
    }

    /** The elements, in order, read-only. */
    public List<JsonValue> elements() {
        return Collections.unmodifiableList(elements);
    }

    public JsonArray add(final JsonValue value) {
        elements.add(value == null ? JsonNull.NULL : value);
        return this;
    }

    public JsonArray add(final String value) {
        return add(new JsonString(value));
    }

    public JsonArray add(final long value) {
        return add(JsonNumber.of(value));
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof JsonArray that && elements.equals(that.elements);
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    public String toString() {
        return elements.toString();
    }
}
