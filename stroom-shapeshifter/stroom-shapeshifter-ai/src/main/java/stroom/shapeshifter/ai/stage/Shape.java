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

package stroom.shapeshifter.ai.stage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One value of a document's learning key (design 01 §5, ruling A29): what the stage learns, binds,
 * gives up and validates on. With the default key a shape is a feed and type; with the signature in
 * the key, a kind of record within them.
 *
 * @param values  The key's fields and the stream's values for them, in key order; a field the stream
 *                lacks is absent.
 * @param missing The key fields the stream carries no value for. A shape with any is not learnable
 *                under this key (§3): binding on the fields present would widen the rule to every
 *                value of the missing one.
 */
public record Shape(Map<String, String> values, List<String> missing) {

    public Shape {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        missing = List.copyOf(missing);
    }

    public static Shape of(final List<String> learningKey, final Map<String, ?> attributes) {
        final Map<String, String> values = new LinkedHashMap<>();
        final List<String> missing = new ArrayList<>();
        for (final String field : learningKey) {
            final Object value = attributes.get(field);
            if (value == null) {
                missing.add(field);
            } else {
                values.put(field, value.toString());
            }
        }
        return new Shape(values, missing);
    }

    /**
     * The shape as the ledger and the regression set key it: the key's values in order, e.g.
     * {@code Feed=SYSLOG|Type=Raw Events}.
     */
    public String id() {
        final StringBuilder id = new StringBuilder();
        values.forEach((field, value) -> id.append(id.isEmpty()
                ? ""
                : "|").append(field).append('=').append(value));
        return id.toString();
    }

    /**
     * The shape as a document name can carry it: the values, with anything a name should not hold
     * collapsed to a hyphen.
     */
    public String slug() {
        return String.join("-", values.values()).replaceAll("[^A-Za-z0-9._-]+", "-");
    }
}
