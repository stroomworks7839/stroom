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

package stroom.shapeshifter.engine.function;

import stroom.shapeshifter.engine.value.TypedValue;

/**
 * What a function argument or result is, in design 17's terms: the five kinds of
 * {@code TypedValue}, {@code ANY} for "as it is", and {@code SEQUENCE} for an argument that
 * names a store and receives every entry of it (design 26 §2).
 */
public enum Kind {
    STRING,
    NUMBER,
    INTEGER,
    BOOLEAN,
    DATE,
    ANY,
    SEQUENCE
}
