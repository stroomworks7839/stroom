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

package stroom.shapeshifter.ai.learning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an attempt is about: the input to learn from and the values of the document's learning key that
 * describe the stream (A29).
 * <p>
 * Shown means bound: the model sees every field in the key and nothing else, so what it was told and
 * what the learned rule requires cannot drift apart, and the attribute map's sender-set entries —
 * hostnames, paths, tokens — never travel unless an operator put that header in the key. Both text and
 * values are subject to the redaction of A17 before they reach a model; that redaction is not yet built,
 * so today they travel as they arrive.
 *
 * @param text    The input sample, as the first element of the chain will receive it.
 * @param headers The learning key's values present on the stream, in key order.
 */
public record Sample(String text, Map<String, String> headers) {

    public Sample {
        // Order is kept: a prompt lists the values in key order, strongest signal first.
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public static Sample of(final String text) {
        return new Sample(text, Map.of());
    }

    /**
     * @param learningKey The document's learning key, in order.
     * @param values      The stream's routing attributes, of which only the key's fields are kept.
     */
    public static Sample of(final String text, final List<String> learningKey, final Map<String, ?> values) {
        final Map<String, String> headers = new LinkedHashMap<>();
        for (final String field : learningKey) {
            final Object value = values.get(field);
            if (value != null) {
                headers.put(field, value.toString());
            }
        }
        return new Sample(text, headers);
    }
}
