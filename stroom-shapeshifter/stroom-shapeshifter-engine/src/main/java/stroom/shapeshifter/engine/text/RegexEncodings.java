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

    /**
     * Built eagerly for every constant, so the lookup on the step-evaluation path is one
     * unsynchronised {@link EnumMap} read — the phase-3 audit found a {@code synchronized}
     * here, paid per regex step on every single-byte feed.
     */
    private static final Map<Encoding, stroom.shapeshifter.regex.Encoding> CACHE = buildAll();

    private static Map<Encoding, stroom.shapeshifter.regex.Encoding> buildAll() {
        final Map<Encoding, stroom.shapeshifter.regex.Encoding> cache =
                new EnumMap<>(Encoding.class);
        for (final Encoding encoding : Encoding.values()) {
            final stroom.shapeshifter.regex.Encoding mapped = build(encoding);
            if (mapped != null) {
                cache.put(encoding, mapped);
            }
        }
        return cache;
    }

    private RegexEncodings() {
    }

    /**
     * The regex library's shape for {@code encoding}, or null where the library has none —
     * only the transcode family (UTF-16 and friends), by design. A template's compile never
     * meets the null: a transcode-family source is decoded whole to UTF-8 first, and a template
     * may not declare one of its own, so E29's refusal of a regex under such an encoding is
     * made by those two rules before any pattern is compiled.
     */
    public static stroom.shapeshifter.regex.Encoding forMatch(final Encoding encoding) {
        if (encoding.isUtf8Compatible()) {
            return stroom.shapeshifter.regex.Encoding.UTF_8;
        }
        if (encoding == Encoding.RAW) {
            return stroom.shapeshifter.regex.Encoding.RAW;
        }
        return CACHE.get(encoding);
    }

    /**
     * Whether {@code encoding} is served by whole-source transcoding (design 19 phase 6):
     * the library has no lowering for it, but the JDK has a charset — UTF-16 and the CJK
     * multi-byte family. RAW is not this (it lowers natively), and an unavailable charset
     * is not either (nothing can decode it; the refusal stands).
     */
    public static boolean needsTranscode(final Encoding encoding) {
        return forMatch(encoding) == null
               && encoding != Encoding.RAW
               && encoding.isAvailable()
               && encoding.charset() != null;
    }

    /** A 256-entry table for a single-byte charset, or null for anything else. */
    private static stroom.shapeshifter.regex.Encoding build(final Encoding encoding) {
        if (!encoding.isAvailable()) {
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
