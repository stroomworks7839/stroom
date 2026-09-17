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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A JSON object: members in insertion order, which is the order they are written. */
public final class JsonObject implements JsonValue {

    private final Map<String, JsonValue> members = new LinkedHashMap<>();

    @Override
    public String shape() {
        return "object";
    }

    @Override
    public boolean isObject() {
        return true;
    }

    @Override
    public JsonValue get(final String field) {
        return members.get(field);
    }

    @Override
    public int size() {
        return members.size();
    }

    /** The members, in order, read-only. */
    public Set<Map.Entry<String, JsonValue>> entries() {
        return Collections.unmodifiableSet(members.entrySet());
    }

    public JsonObject put(final String field, final JsonValue value) {
        members.put(field, value == null ? JsonNull.NULL : value);
        return this;
    }

    public JsonObject put(final String field, final String value) {
        return put(field, new JsonString(value));
    }

    public JsonObject put(final String field, final long value) {
        return put(field, JsonNumber.of(value));
    }

    public JsonObject put(final String field, final double value) {
        return put(field, JsonNumber.of(value));
    }

    public JsonObject put(final String field, final boolean value) {
        return put(field, JsonBoolean.of(value));
    }

    /** Put a new empty array under the field and return it, for the writer to fill. */
    public JsonArray putArray(final String field) {
        final JsonArray array = new JsonArray();
        put(field, array);
        return array;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof JsonObject that && members.equals(that.members);
    }

    @Override
    public int hashCode() {
        return members.hashCode();
    }

    @Override
    public String toString() {
        return members.toString();
    }
}
