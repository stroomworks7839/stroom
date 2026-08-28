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

package stroom.shapeshifter.engine.text;

import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.Map;

/**
 * The seam between this vocabulary and the regex library's (design 19 phase 3): each engine
 * encoding maps to the {@link stroom.shapeshifter.regex.Encoding} shape the library can
 * lower, or to null where it has none yet — which is what the compile-time refusal reads.
 * The library's shapes are its own, dependency-free type; this class is the one place the
 * two vocabularies meet, and the mapping is built from the same {@link Charset} the rest of
 * the engine decodes with, so the regex's view of a byte and a step's can never disagree.
 */
public final class RegexEncodings {

    private static final Map<Encoding, stroom.shapeshifter.regex.Encoding> CACHE =
            new EnumMap<>(Encoding.class);

    private RegexEncodings() {
    }

    /**
     * The regex library's shape for {@code encoding}, or null where the library has none:
     * RAW until the identity lowering lands (design 19 phase 4), and the transcode family
     * (UTF-16 and friends) by design.
     */
    public static synchronized stroom.shapeshifter.regex.Encoding forMatch(final Encoding encoding) {
        if (encoding.isUtf8Compatible()) {
            return stroom.shapeshifter.regex.Encoding.UTF_8;
        }
        if (CACHE.containsKey(encoding)) {
            return CACHE.get(encoding);
        }
        final stroom.shapeshifter.regex.Encoding mapped = build(encoding);
        CACHE.put(encoding, mapped);
        return mapped;
    }

    /** A 256-entry table for a single-byte charset, or null for anything else. */
    private static stroom.shapeshifter.regex.Encoding build(final Encoding encoding) {
        if (encoding == Encoding.RAW || !encoding.isAvailable()) {
            return null;
        }
        final Charset charset = encoding.charset();
        if (charset == null || charset.newEncoder().maxBytesPerChar() != 1.0f) {
            return null;
        }
        final int[] map = new int[256];
        for (int b = 0; b < 256; b++) {
            final String decoded = new String(new byte[]{(byte) b}, charset);
            final int codePoint = decoded.codePointAt(0);
            final byte[] back = decoded.getBytes(charset);
            // A byte earns a table entry only if it decodes to one real character that
            // round-trips to itself — the same faithfulness E22 demands of the names.
            map[b] = decoded.codePointCount(0, decoded.length()) == 1
                     && codePoint != 0xFFFD
                     && back.length == 1 && (back[0] & 0xFF) == b
                    ? codePoint
                    : -1;
        }
        return new stroom.shapeshifter.regex.Encoding.Table(encoding.label(), map);
    }
}
