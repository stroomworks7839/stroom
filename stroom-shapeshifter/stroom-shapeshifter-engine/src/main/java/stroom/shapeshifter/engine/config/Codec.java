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
 * A transformation applied to bytes rather than to text.
 *
 * <p>The first six are carried by the JDK — {@code java.util.zip} covers DEFLATE and GZIP —
 * and are always available; SNAPPY, ZSTD and LZ4 need a compression library, and are rejected
 * at compile time in builds that do not have one (D33).
 */
public enum Codec {

    /** Standard base64, with padding. */
    BASE64,
    /** URL-safe base64. */
    BASE64_URL,
    /** Hexadecimal. */
    HEX,
    /** Percent-encoding, as used in URLs. */
    URL_ENCODING,
    /** Raw DEFLATE. */
    DEFLATE,
    /** DEFLATE in a gzip wrapper. */
    GZIP,
    /** Snappy. */
    SNAPPY,
    /** Zstandard. */
    ZSTD,
    /** LZ4. */
    LZ4
}
