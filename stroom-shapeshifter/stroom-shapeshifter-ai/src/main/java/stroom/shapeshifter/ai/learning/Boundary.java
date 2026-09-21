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

package stroom.shapeshifter.ai.learning;

/// The record boundary a plan settles (A31, A35): for raw text the parser configuration that cuts one
/// record per unit and emits it whole; for input that is already XML the local name of the element that is
/// one record. Exactly one of the two is set.
///
/// @param configuration The Data Splitter configuration that cuts the records, or null.
/// @param element       The element that is one record, or null.
public record Boundary(String configuration, String element) {

    public Boundary {
        if ((configuration == null) == (element == null)) {
            throw new IllegalArgumentException("A boundary is a configuration or an element, not both or neither");
        }
    }

    /// The boundary of raw text: a configuration that cuts it.
    public static Boundary ofConfiguration(final String configuration) {
        return new Boundary(configuration, null);
    }

    /// The boundary of input that is already XML: the element that is one record.
    public static Boundary ofElement(final String element) {
        return new Boundary(null, element);
    }
}
