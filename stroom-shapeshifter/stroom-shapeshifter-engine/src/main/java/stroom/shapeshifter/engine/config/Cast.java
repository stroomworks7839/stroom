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

package stroom.shapeshifter.engine.config;

/**
 * An explicit typed read — the {@code as} of design/17 §8, applying the casting table of
 * §3.1 wherever a value is about to be compared or ordered. There is no implicit coercion
 * anywhere in the engine: a comparison between different kinds is simply false, and an
 * author who means a numeric or boolean reading says so with one of these. A cast that
 * fails yields absence, and absence compares false.
 *
 * <p>{@code DATE} — the ISO-8601 reading — arrives with the {@code Instant} work (phase 4)
 * and is refused at compile time until then.
 */
public enum Cast {

    /** The string form — total: every value has one. */
    STRING,

    /** The numeric reading, absent when the value has none. */
    NUMBER,

    /** The lexical boolean reading: {@code true}/{@code 1}/{@code false}/{@code 0}. */
    BOOLEAN,

    /** The ISO-8601 instant reading. Refused until the {@code Instant} variant lands. */
    DATE
}
