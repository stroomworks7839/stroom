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
 * A set of Unicode code points, held as sorted non-overlapping inclusive ranges.
 * <p>
 * Character classes are defined over code points, not bytes — that is what lets {@code \w}
 * match {@code é} and what makes the same class compile correctly for different encodings.
 * The conversion to bytes happens in {@link Utf8}, at compile time.
 * <p>
 * Surrogate code points (U+D800–U+DFFF) are never members: they have no encoding, so a class
 * containing them could never match.
 */
public final class CodePointSet {

    public static final int MAX = 0x10FFFF;
    private static final int SURROGATE_LO = 0xD800;
    private static final int SURROGATE_HI = 0xDFFF;

    /** Pairs of inclusive bounds: {lo0, hi0, lo1, hi1, ...}, sorted and disjoint. */
    private final int[] ranges;

    private CodePointSet(final int[] ranges) {
        this.ranges = ranges;
    }

    public static CodePointSet of(final int... loHiPairs) {
        final Builder builder = new Builder();
        for (int i = 0; i < loHiPairs.length; i += 2) {
            builder.add(loHiPairs[i], loHiPairs[i + 1]);
        }
        return builder.build();
    }

    public static CodePointSet single(final int codePoint) {
        return of(codePoint, codePoint);
    }

    /** Every encodable code point. */
    public static CodePointSet all() {
        return of(0, MAX);
    }

    public int[] ranges() {
        return ranges;
    }

    public boolean isEmpty() {
        return ranges.length == 0;
    }

    /** The sole member, or -1 if the set does not hold exactly one code point. */
    public int singleCodePoint() {
        return ranges.length == 2 && ranges[0] == ranges[1]
                ? ranges[0]
                : -1;
    }

    public boolean contains(final int codePoint) {
        int lo = 0;
        int hi = ranges.length / 2 - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (codePoint < ranges[2 * mid]) {
                hi = mid - 1;
            } else if (codePoint > ranges[2 * mid + 1]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /** The members above ASCII, kept for the multi-byte arm of a hybrid class loop. */
    public CodePointSet nonAscii() {
        final Builder builder = new Builder();
        for (int i = 0; i < ranges.length; i += 2) {
            if (ranges[i + 1] >= 0x80) {
                builder.add(Math.max(ranges[i], 0x80), ranges[i + 1]);
            }
        }
        return builder.build();
    }

    /** True if every member is ASCII, so the class is exactly a set of single bytes. */
    public boolean isAsciiOnly() {
        return ranges.length == 0 || ranges[ranges.length - 1] <= 0x7F;
    }

    /**
     * True if every non-ASCII code point is a member.
     * <p>
     * Such a class — {@code .} and negated ASCII classes like {@code [^,]} are the common cases
     * — accepts any multi-byte character whole, so an unbounded scan over it can run at byte
     * level and still produce character-aligned spans. That keeps the fast path for the
     * patterns that need it most.
     */
    public boolean containsAllNonAscii() {
        int expected = 0x80;
        for (int i = 0; i < ranges.length; i += 2) {
            if (ranges[i + 1] < 0x80) {
                continue;
            }
            if (ranges[i] > expected) {
                return false;
            }
            expected = Math.max(expected, ranges[i + 1] + 1);
            // Surrogates are unencodable, so a gap across them does not break the property.
            if (expected == SURROGATE_LO) {
                expected = SURROGATE_HI + 1;
            }
        }
        return expected > MAX;
    }

    public CodePointSet union(final CodePointSet other) {
        final Builder builder = new Builder();
        for (int i = 0; i < ranges.length; i += 2) {
            builder.add(ranges[i], ranges[i + 1]);
        }
        for (int i = 0; i < other.ranges.length; i += 2) {
            builder.add(other.ranges[i], other.ranges[i + 1]);
        }
        return builder.build();
    }

    /** The complement within the encodable code points. */
    public CodePointSet negate() {
        final Builder builder = new Builder();
        int next = 0;
        for (int i = 0; i < ranges.length; i += 2) {
            if (ranges[i] > next) {
                builder.add(next, ranges[i] - 1);
            }
            next = Math.max(next, ranges[i + 1] + 1);
        }
        if (next <= MAX) {
            builder.add(next, MAX);
        }
        return builder.build();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ranges.length; i += 2) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%04X", ranges[i]));
            if (ranges[i + 1] != ranges[i]) {
                sb.append('-').append(String.format("%04X", ranges[i + 1]));
            }
        }
        return sb.append(']').toString();
    }

    /** Accumulates ranges, normalising to sorted disjoint form and dropping surrogates. */
    public static final class Builder {

        private final List<int[]> pending = new ArrayList<>();

        public Builder add(final int lo, final int hi) {
            addEncodable(Math.max(lo, 0), Math.min(hi, MAX));
            return this;
        }

        public Builder add(final CodePointSet set) {
            for (int i = 0; i < set.ranges.length; i += 2) {
                add(set.ranges[i], set.ranges[i + 1]);
            }
            return this;
        }

        private void addEncodable(final int lo, final int hi) {
            if (lo > hi) {
                return;
            }
            if (lo <= SURROGATE_HI && hi >= SURROGATE_LO) {
                // Split around the surrogate block rather than admitting unencodable members.
                if (lo < SURROGATE_LO) {
                    pending.add(new int[]{lo, SURROGATE_LO - 1});
                }
                if (hi > SURROGATE_HI) {
                    pending.add(new int[]{SURROGATE_HI + 1, hi});
                }
                return;
            }
            pending.add(new int[]{lo, hi});
        }

        public CodePointSet build() {
            if (pending.isEmpty()) {
                return new CodePointSet(new int[0]);
            }
            pending.sort((a, b) -> Integer.compare(a[0], b[0]));
            final List<int[]> merged = new ArrayList<>();
            int[] current = pending.getFirst().clone();
            for (int i = 1; i < pending.size(); i++) {
                final int[] range = pending.get(i);
                if (range[0] <= current[1] + 1) {
                    current[1] = Math.max(current[1], range[1]);
                } else {
                    merged.add(current);
                    current = range.clone();
                }
            }
            merged.add(current);

            final int[] flat = new int[merged.size() * 2];
            for (int i = 0; i < merged.size(); i++) {
                flat[2 * i] = merged.get(i)[0];
                flat[2 * i + 1] = merged.get(i)[1];
            }
            return new CodePointSet(flat);
        }
    }
}
