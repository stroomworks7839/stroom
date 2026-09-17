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

/**
 * A JSON value, as the mapping reads and writes it.
 *
 * <p>The mapping is shared with the GWT client (design 43 §2), which has no Jackson, and the
 * engine, which has no GWT; so it is written against this tree and each side adapts its own
 * parser's to it at the edge — {@code ProjectReader} in the engine, the JSON client library in
 * the browser — in a few dozen lines. The tree carries exactly what the format needs: the six
 * JSON kinds, insertion-ordered objects, and numbers as their literal text so that a whole
 * number stays whole across the round trip. It is not a general JSON library and has no
 * parser or printer of its own.
 *
 * <p>The {@code is} and {@code as} accessors are here rather than on each kind so that the
 * mapping reads as it did against Jackson's tree: {@code node.get("x").asString()}. Asking a
 * value for what it is not is an {@link IllegalStateException}; the mapping checks first and
 * refuses by name with a {@code ConfigException}, so the exception is a bug, not a message.
 */
public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {

    /** The kind's name for messages: object, array, string, number, boolean, null. */
    String shape();

    default boolean isObject() {
        return false;
    }

    default boolean isArray() {
        return false;
    }

    default boolean isString() {
        return false;
    }

    default boolean isNumber() {
        return false;
    }

    default boolean isIntegralNumber() {
        return false;
    }

    default boolean isBoolean() {
        return false;
    }

    default boolean isNull() {
        return false;
    }

    /** A named member of an object, or null where this is not an object or has no such member. */
    default JsonValue get(final String field) {
        return null;
    }

    /** Whether this is an object with the named member (a JSON null member counts). */
    default boolean has(final String field) {
        return get(field) != null;
    }

    /** An object's member count or an array's length; zero for a scalar. */
    default int size() {
        return 0;
    }

    default String asString() {
        throw new IllegalStateException("Not a string: " + shape());
    }

    default int asInt() {
        throw new IllegalStateException("Not a number: " + shape());
    }

    default long asLong() {
        throw new IllegalStateException("Not a number: " + shape());
    }

    default double asDouble() {
        throw new IllegalStateException("Not a number: " + shape());
    }

    default boolean asBoolean() {
        throw new IllegalStateException("Not a boolean: " + shape());
    }
}
