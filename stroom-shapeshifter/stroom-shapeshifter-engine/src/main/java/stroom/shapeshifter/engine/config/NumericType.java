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


/** The width and kind of a fixed-width binary number. */
public enum NumericType {

    /** 16 bits. */
    SHORT,
    /** 32 bits. */
    INT,
    /** 64 bits. */
    LONG,
    /** 32-bit IEEE 754. */
    FLOAT,
    /** 64-bit IEEE 754. */
    DOUBLE
}
