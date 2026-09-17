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

import stroom.docref.HasDisplayValue;

/**
 * The built-in scorers of design §8.4. A Shapeshifter AI document lists the ones it uses with a weight and threshold
 * each.
 */
public enum ScorerType implements HasDisplayValue {
    COMPILE("Compile"),
    INPUT_COVERAGE("Input coverage"),
    YIELD("Yield"),
    SCHEMA_CONFORMANCE("Schema conformance"),
    EXTRACTION_QUALITY("Extraction quality"),
    BUSINESS_RULES("Business rules"),
    ERROR_LOAD("Error load"),
    EVENT_CLASSIFICATION("Event classification");

    private final String displayValue;

    ScorerType(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
