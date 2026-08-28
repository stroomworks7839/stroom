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

package stroom.shapeshifter.regex.internal;

/**
 * The backreference comparison, shared by every engine that can run one.
 * <p>
 * One implementation on purpose: two engines carrying their own copies of the case-folding
 * rules is how differential bugs are born, and the folding here is deliberately the JDK's —
 * upper case equal, or lower case equal — so the oracle stays usable on exactly this corner.
 */
final class Backrefs {

    /** Every byte in hand agreed, but the region ended before the reference did. Callers
     * treat it as a plain failure since D37 retired the edge reporting; the constant records
     * the cause. */
    static final int TRUNCATED = -2;

    /** The input does not match the captured span. */
    static final int MISMATCH = -1;

    private Backrefs() {
    }

    /**
     * Matches the captured span {@code [from, until)} against the input at {@code pos}.
     *
     * @return the number of input bytes consumed, which under folding can differ from the
     * span's length; or {@link #MISMATCH}; or {@link #TRUNCATED}, which callers treat as a
     * plain failure.
     */
    static int compare(final ByteForm form,
                       final byte[] data,
                       final int pos,
                       final int to,
                       final int from,
                       final int until,
                       final boolean fold,
                       final boolean unicode) {
        if (!fold) {
            final int length = until - from;
            final int available = to - pos;
            final int comparable = Math.min(length, available);
            for (int i = 0; i < comparable; i++) {
                if (data[from + i] != data[pos + i]) {
                    return MISMATCH;
                }
            }
            return length > available
                    ? TRUNCATED
                    : length;
        }

        // Folded: both spans walk a code point at a time, so the consumed length can differ
        // from the captured length when folding crosses byte-length boundaries.
        int captured = from;
        int input = pos;
        while (captured < until) {
            if (input >= to) {
                return TRUNCATED;
            }
            final int wanted = form.decode(data, captured, until);
            if (wanted < 0) {
                return MISMATCH; // the span is not whole characters; nothing can fold-match it
            }
            final int have = form.decode(data, input, to);
            if (have < 0) {
                return TRUNCATED; // a character split by the window edge
            }
            if (wanted != have && !foldedEqual(wanted, have, unicode)) {
                return MISMATCH;
            }
            captured += form.encodedLength(wanted);
            input += form.encodedLength(have);
        }
        return input - pos;
    }

    private static boolean foldedEqual(final int a, final int b, final boolean unicode) {
        if (!unicode) {
            // (?i-u): ASCII letters fold, nothing else does.
            return (a | 0x20) == (b | 0x20)
                   && (a | 0x20) >= 'a' && (a | 0x20) <= 'z'
                   && a <= 0x7F && b <= 0x7F;
        }
        return Character.toUpperCase(a) == Character.toUpperCase(b)
               || Character.toLowerCase(a) == Character.toLowerCase(b);
    }
}
