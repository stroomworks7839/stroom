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

import stroom.meta.shared.MetaFields;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// What a learner learns, binds, gives up and validates on (design 01 §5, A29): a feed and a type of the
/// streams one learner meets (A49). Until design 06 phase J slice 3 removes it, it carries its learner, so
/// that what is kept about it is the learner's and not the document's (A124).
///
/// @param learner The learner it is a shape of.
/// @param values  The feed and the type, in that order; a field the stream lacks is absent.
/// @param missing The fields the stream carries no value for. A shape with any is not learnable (§3).
public record Shape(Learner learner, Map<String, String> values, List<String> missing) {

    /// What a shape is the values of (A49): a feed and a type. A document no longer chooses (design 06
    /// phase J slice 2a), since a learner's pipeline already says which streams it takes, and slice 3
    /// removes the shape altogether.
    public static final List<String> KEY = List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE);

    public Shape {
        Objects.requireNonNull(learner, "learner");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        missing = List.copyOf(missing);
    }

    /// The shape a stream of these attributes makes for a learner.
    ///
    /// @param learner    The learner meeting the stream.
    /// @param attributes The stream's attributes, from which the feed and type are read.
    public static Shape of(final Learner learner, final Map<String, ?> attributes) {
        final Map<String, String> values = new LinkedHashMap<>();
        final List<String> missing = new ArrayList<>();
        for (final String field : KEY) {
            final Object value = attributes.get(field);
            if (value == null) {
                missing.add(field);
            } else {
                values.put(field, value.toString());
            }
        }
        return new Shape(learner, values, missing);
    }

    /// A shape read back from the id it was recorded under, for a path that begins from a *rule* or a
    /// row rather than from a stream (A46's improvement): the stream whose values made the id is long
    /// gone, and the id is what keys the ledger, the shape's state and an attempt's claim.
    ///
    /// Splitting the id back up is exact for every value that can be told apart by it in the first
    /// place. A value holding a `|` or an `=` would mis-split here — and would already have made two
    /// different shapes share one id, which is the worse of the two problems and not this method's.
    ///
    /// @param learner The learner the id was recorded for.
    /// @param id      The id.
    public static Shape parse(final Learner learner, final String id) {
        final Map<String, String> values = new LinkedHashMap<>();
        if (id != null && !id.isBlank()) {
            for (final String field : id.split("\\|")) {
                final int at = field.indexOf('=');
                if (at > 0) {
                    values.put(field.substring(0, at), field.substring(at + 1));
                }
            }
        }
        return new Shape(learner, values, List.of());
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
