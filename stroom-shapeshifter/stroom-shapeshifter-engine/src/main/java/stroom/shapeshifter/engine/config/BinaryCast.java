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

import java.util.Locale;

/**
 * What a labelled run of bytes means as a value (design 38 §3): the interpretation the old
 * {@code ReadNumeric}, {@code ReadVarint} and {@code Tell} steps carried inside the matcher,
 * now a cast on the capture — where {@code as} already lives — so the regex library never
 * learns that a byte run is a number. Applied when the match binds its groups; a body reads
 * the labelled group as the integer, double or boolean it names.
 *
 * <p>{@code POSITION} is the odd one: not a reading of the group's bytes but of where the
 * group starts, as an offset into the match — the old {@code Tell}, as a cast on an empty
 * labelled node.
 */
public enum BinaryCast {
    UINT8, INT8,
    UINT16LE, UINT16BE, INT16LE, INT16BE,
    UINT32LE, UINT32BE, INT32LE, INT32BE,
    INT64LE, INT64BE,
    FLOAT32LE, FLOAT32BE, FLOAT64LE, FLOAT64BE,
    /** An unsigned LEB128 varint, as protobuf spells it. */
    VARINT,
    /** A zigzag-encoded varint, as Avro and protobuf's {@code sint} spell a signed integer. */
    ZIGZAG,
    /** One byte, zero false and anything else true. */
    BOOL8,
    /** The group's offset from the start of the match. */
    POSITION;

    /** The spelling in a configuration: lower case, as written above. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BinaryCast of(final String label) {
        for (final BinaryCast cast : values()) {
            if (cast.label().equals(label)) {
                return cast;
            }
        }
        return null;
    }
}
