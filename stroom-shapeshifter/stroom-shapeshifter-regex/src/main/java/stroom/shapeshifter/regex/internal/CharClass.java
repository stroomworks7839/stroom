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

import java.util.ArrayList;
import java.util.List;

/**
 * A character class compiled for matching: the byte-range sequences that accept exactly its
 * members, plus a lead-byte table for fast rejection.
 * <p>
 * Matching consumes a whole character, however many bytes it occupies, so a group span can
 * never split one.
 */
public final class CharClass {

    /** 256 entries; 1 where the byte can begin an accepted character. */
    private final byte[] leadTable;

    /** Alternatives from {@link Utf8#sequences}, each {lo0, hi0, lo1, hi1, ...}. */
    private final int[][] sequences;

    /**
     * Alternatives indexed by lead byte, so matching selects candidates rather than scanning
     * them. A class can have many alternatives — every hole in a code-point range adds one — and
     * walking them in order would make the cost depend on how many there are and where the
     * matching one sits. Nearly every entry here holds a single candidate.
     */
    private final int[][][] byLeadByte;

    /** Every member is ASCII, so the lead table alone decides and a match is one byte. */
    private final boolean asciiOnly;

    /**
     * Every non-ASCII code point is a member, so scanning bytes and scanning characters cover
     * the same span. Lets {@code .*} and {@code [^,]+} stay byte-level scans.
     */
    private final boolean byteScanSafe;

    private final String label;

    CharClass(final CodePointSet set, final String label) {
        this.sequences = Utf8.sequences(set);
        this.asciiOnly = set.isAsciiOnly();
        this.byteScanSafe = set.containsAllNonAscii();
        this.label = label;

        this.leadTable = new byte[256];
        for (final int[] sequence : sequences) {
            for (int b = sequence[0]; b <= sequence[1]; b++) {
                leadTable[b] = 1;
            }
        }

        this.byLeadByte = new int[256][][];
        if (!asciiOnly) {
            final List<List<int[]>> buckets = new ArrayList<>(256);
            for (int b = 0; b < 256; b++) {
                buckets.add(new ArrayList<>(1));
            }
            for (final int[] sequence : sequences) {
                for (int b = sequence[0]; b <= sequence[1]; b++) {
                    buckets.get(b).add(sequence);
                }
            }
            for (int b = 0; b < 256; b++) {
                byLeadByte[b] = buckets.get(b).toArray(new int[0][]);
            }
        }
    }

    public byte[] leadTable() {
        return leadTable;
    }

    public boolean isAsciiOnly() {
        return asciiOnly;
    }

    public boolean isByteScanSafe() {
        return byteScanSafe;
    }

    public String label() {
        return label;
    }

    /**
     * Matches one character at {@code pos}.
     *
     * @return the number of bytes consumed, or -1 if no member matches there.
     */
    public int matchAt(final byte[] data, final int pos, final int to) {
        if (pos >= to) {
            return -1;
        }
        final int lead = data[pos] & 0xFF;
        if (leadTable[lead] == 0) {
            return -1;
        }
        if (asciiOnly || lead < 0x80) {
            // A byte below 0x80 can only ever begin a one-byte sequence, so the lead table has
            // already decided it. Worth its own branch rather than falling into the loop below:
            // a Unicode \w or \d is a class of many sequences whose input is overwhelmingly
            // ASCII, and this keeps that traffic at one table lookup per character.
            return 1;
        }
        // Only the alternatives this lead byte can begin are considered, so the work does not
        // grow with the number of alternatives the class happens to have.
        for (final int[] sequence : byLeadByte[lead]) {
            final int length = sequence.length / 2;
            if (pos + length > to) {
                continue;
            }
            boolean matched = true;
            for (int i = 1; i < length; i++) {
                final int value = data[pos + i] & 0xFF;
                if (value < sequence[2 * i] || value > sequence[2 * i + 1]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return length;
            }
        }
        return -1;
    }

    /**
     * Whether the bytes at {@code pos} could be the beginning of a member that the window has
     * cut short — the difference between "this character is not in the class" and "this
     * character has not fully arrived".
     */
    public boolean mayContinue(final byte[] data, final int pos, final int to) {
        if (pos >= to) {
            return true;
        }
        final int lead = data[pos] & 0xFF;
        if (leadTable[lead] == 0) {
            return false;
        }
        if (asciiOnly || lead < 0x80) {
            return false; // one byte, and it was there, so a failure here is a real one
        }
        for (final int[] sequence : byLeadByte[lead]) {
            final int length = sequence.length / 2;
            if (pos + length <= to) {
                continue; // fully available, so it was genuinely tested
            }
            boolean consistent = true;
            for (int i = 1; pos + i < to; i++) {
                final int value = data[pos + i] & 0xFF;
                if (value < sequence[2 * i] || value > sequence[2 * i + 1]) {
                    consistent = false;
                    break;
                }
            }
            if (consistent) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return label + " (" + sequences.length + " sequence"
               + (sequences.length == 1
                ? ""
                : "s")
               + (asciiOnly
                ? ", ascii"
                : "")
               + ")";
    }
}
