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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Converts a set of code points into the byte-range sequences that recognise exactly their
 * UTF-8 encodings.
 * <p>
 * This is the algorithm the whole encoding design rests on: a character class becomes an
 * alternation of byte-range sequences, after which matching is pure bytes. Rust's regex crate
 * does the same thing internally ({@code regex_syntax::utf8::Utf8Sequences}); the difference in
 * the wider design is that this is meant to be parameterised by encoding rather than fixed to
 * UTF-8, with the other families arriving later.
 * <p>
 * Worked example — the Cyrillic block {@code U+0400–U+052F} cannot be one pair of byte ranges,
 * because {@code U+04FF} encodes to {@code D3 BF} while {@code U+052F} encodes to {@code D4 AF};
 * a naive {@code [D0-D4][80-AF]} would miss {@code U+04FF}. It becomes two sequences:
 * <pre>
 *   [D0-D3][80-BF]   covering U+0400–U+04FF
 *   [D4][80-AF]      covering U+0500–U+052F
 * </pre>
 * The produced sequences never accept ill-formed UTF-8, and never accept the encoding of a code
 * point outside the set.
 */
public final class Utf8 {

    /** Inclusive code point bounds for each UTF-8 encoded length, 1 to 4 bytes. */
    private static final int[][] LENGTH_BOUNDS = {
            {0x0000, 0x007F},
            {0x0080, 0x07FF},
            {0x0800, 0xFFFF},
            {0x10000, CodePointSet.MAX}};

    private Utf8() {
    }

    /**
     * A single alternative: one byte range per position, as {lo0, hi0, lo1, hi1, ...}.
     * A character matches if each of its bytes falls in the corresponding range.
     */
    public static int[][] sequences(final CodePointSet set) {
        final List<int[]> out = new ArrayList<>();
        final int[] ranges = set.ranges();
        for (int i = 0; i < ranges.length; i += 2) {
            encodeRange(ranges[i], ranges[i + 1], out);
        }
        // Alternatives are disjoint, so order cannot affect matching. Sorting by lead byte makes
        // the output deterministic and readable in plan listings.
        out.sort((x, y) -> x[0] != y[0]
                ? Integer.compare(x[0], y[0])
                : Integer.compare(x.length, y.length));
        return out.toArray(new int[0][]);
    }

    /** The bytes that can begin the encoding of a member — the class's first-set. */
    public static BitSet leadBytes(final CodePointSet set) {
        final BitSet bits = new BitSet(256);
        for (final int[] sequence : sequences(set)) {
            bits.set(sequence[0], sequence[1] + 1);
        }
        return bits;
    }

    /**
     * Whether a byte continues a character rather than beginning one.
     * <p>
     * Used to keep a match — in particular a zero-width one — from starting in the middle of a
     * character. Nothing else can start there anyway, since every class and literal compiles to
     * whole byte sequences, but an empty match would, and splitting a character with it produces
     * offsets that no caller can use.
     */
    public static boolean isContinuation(final byte b) {
        return (b & 0xC0) == 0x80;
    }

    /**
     * Whether {@code at} falls inside a character, and so cannot begin a match — not even an
     * empty one, which is the only kind that could. An offset that splits a character is of no
     * use to a caller reading the text back.
     *
     * <p>A region can end inside a character, so the byte just past the region may be
     * consulted — {@code at < data.length} is what makes the read safe, and the array holding
     * the caller's data up to its length is {@code ByteMatcher}'s stated contract: a caller
     * reusing a buffer blanks its tail. This method used to take that bound as a third
     * argument, {@code contextEnd}; it was {@code data.length} on every path since D37, the
     * tighter bound it kept expressible was ruled moot on 2026-08-27 in favour of the
     * contract, and D39 (2026-09-03) deleted it along with the field, setter and per-search
     * store it cost every engine.
     *
     * <p>The one shared search-start gate: every engine asks this question at every candidate
     * match start — four private spellings had drifted into three behaviours before it (the
     * audit ledger's R1 section holds the record). The two lookbehind body gates deliberately
     * stay hand-rolled: a body start is probed at an offset below the cursor, always inside
     * consumed input, so the beyond-region clause can never apply there.
     */
    public static boolean splitsCharacter(final byte[] data, final int at) {
        return at < data.length && isContinuation(data[at]);
    }

    /**
     * The same question with the array's end supplied by the caller, for {@code ByteMatcher}'s
     * search loops only. D39 deleted this form along with the {@code contextEnd} seam, and the
     * overnight chain convicted the deletion on the one row the D39 probe set did not carry:
     * scan-plan {@code line_miss} fell 22,909 → 3,074 ops/s. Restoring the bound as a field on
     * {@code ByteMatcher}, bound once per {@code match()} and passed here, reads 22,948; a local
     * hoisted above the loop reads 19,739; {@code data.length} inside the callee reads 3,074.
     * The JIT wants the bound as an instance field it can prove constant across the attempt
     * call — the argument-versus-state finding of R1, one more time. The engines keep the
     * two-argument form: their gates measured flat either way.
     */
    public static boolean splitsCharacter(final byte[] data, final int at, final int end) {
        return at < end && isContinuation(data[at]);
    }

    /** The length in bytes of the UTF-8 sequence a lead byte starts, or 0 if it cannot start one. */
    public static int sequenceLength(final int leadByte) {
        if (leadByte < 0x80) {
            return 1;
        }
        if (leadByte < 0xC2) {
            return 0; // a continuation byte, or an overlong lead
        }
        if (leadByte < 0xE0) {
            return 2;
        }
        if (leadByte < 0xF0) {
            return 3;
        }
        if (leadByte < 0xF5) {
            return 4;
        }
        return 0;
    }

    /**
     * Decodes the code point starting at {@code pos}, or -1 if the sequence is malformed or runs
     * past {@code limit}. The runtime-textual reader: backreference comparison and the word
     * boundary's is-word question, the two places matching genuinely needs a character back out
     * of the input — class membership stopped decoding when the tree's classes byte-compiled
     * (design 19 phase 2). The -1 is load-bearing everywhere: it is what makes each caller
     * strict (D38), and since 886066ec82 it covers overlong forms, surrogates and values past
     * U+10FFFF, which is what makes this definition and {@link #sequences}' the same one.
     */
    public static int decode(final byte[] data, final int pos, final int limit) {
        final int lead = data[pos] & 0xFF;
        if (lead < 0x80) {
            return lead;
        }
        final int length = sequenceLength(lead);
        if (length == 0 || pos + length > limit) {
            return -1;
        }
        int codePoint = lead & (0x3F >> (length - 1));
        for (int i = 1; i < length; i++) {
            final int next = data[pos + i] & 0xFF;
            if ((next & 0xC0) != 0x80) {
                return -1;
            }
            codePoint = (codePoint << 6) | (next & 0x3F);
        }
        // The strictness the sequence-compiled engines have by construction, which bit
        // arithmetic must check for: an overlong form (below the length's minimum), a UTF-16
        // surrogate, or a value past U+10FFFF is not an encoding of anything. Found when
        // {ED,A0,80} split the tree from the flat engines — the same three-against-one that
        // convicted the greedy scan shortcut, one layer further down.
        if (codePoint < LENGTH_BOUNDS[length - 1][0]
                || codePoint > CodePointSet.MAX
                || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            return -1;
        }
        return codePoint;
    }

    /** How many bytes {@link #decode} consumed for this code point. */
    public static int encodedLength(final int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    public static byte[] encode(final int codePoint) {
        return new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------------------
    // Range splitting
    // -----------------------------------------------------------------------------------

    private static void encodeRange(final int start, final int end, final List<int[]> out) {
        // A single range can span encoded lengths, and each length has its own byte structure,
        // so split on those boundaries first.
        for (final int[] bounds : LENGTH_BOUNDS) {
            final int lo = Math.max(start, bounds[0]);
            final int hi = Math.min(end, bounds[1]);
            if (lo <= hi) {
                split(encode(lo), encode(hi), out);
            }
        }
    }

    /**
     * Splits a range of equal-length encodings into alternatives whose trailing bytes each vary
     * over a contiguous range.
     * <p>
     * Where the two bounds share a leading byte the problem reduces to their tails. Where they
     * do not, the range is cut into at most three parts: the remainder of the low bound's block,
     * the whole blocks between, and the beginning of the high bound's block. Each part then has
     * uniform structure.
     */
    private static void split(final byte[] lo, final byte[] hi, final List<int[]> out) {
        final int n = lo.length;
        if (n == 1) {
            out.add(new int[]{lo[0] & 0xFF, hi[0] & 0xFF});
            return;
        }

        final int loLead = lo[0] & 0xFF;
        final int hiLead = hi[0] & 0xFF;

        if (loLead == hiLead) {
            final List<int[]> tails = new ArrayList<>();
            split(tail(lo), tail(hi), tails);
            for (final int[] t : tails) {
                out.add(prepend(loLead, loLead, t));
            }
            return;
        }

        int loStart = loLead;
        int hiEnd = hiLead;

        if (!isMinTail(lo)) {
            final List<int[]> tails = new ArrayList<>();
            split(tail(lo), maxTail(n - 1), tails);
            for (final int[] t : tails) {
                out.add(prepend(loLead, loLead, t));
            }
            loStart = loLead + 1;
        }

        if (!isMaxTail(hi)) {
            final List<int[]> tails = new ArrayList<>();
            split(minTail(n - 1), tail(hi), tails);
            for (final int[] t : tails) {
                out.add(prepend(hiLead, hiLead, t));
            }
            hiEnd = hiLead - 1;
        }

        if (loStart <= hiEnd) {
            out.add(prepend(loStart, hiEnd, fullTail(n - 1)));
        }
    }

    private static byte[] tail(final byte[] bytes) {
        final byte[] result = new byte[bytes.length - 1];
        System.arraycopy(bytes, 1, result, 0, result.length);
        return result;
    }

    private static boolean isMinTail(final byte[] bytes) {
        for (int i = 1; i < bytes.length; i++) {
            if ((bytes[i] & 0xFF) != 0x80) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMaxTail(final byte[] bytes) {
        for (int i = 1; i < bytes.length; i++) {
            if ((bytes[i] & 0xFF) != 0xBF) {
                return false;
            }
        }
        return true;
    }

    private static byte[] minTail(final int length) {
        final byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 0x80);
        return bytes;
    }

    private static byte[] maxTail(final int length) {
        final byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 0xBF);
        return bytes;
    }

    /** {0x80, 0xBF} repeated — every continuation byte position unconstrained. */
    private static int[] fullTail(final int length) {
        final int[] ranges = new int[length * 2];
        for (int i = 0; i < length; i++) {
            ranges[2 * i] = 0x80;
            ranges[2 * i + 1] = 0xBF;
        }
        return ranges;
    }

    private static int[] prepend(final int lo, final int hi, final int[] rest) {
        final int[] result = new int[rest.length + 2];
        result[0] = lo;
        result[1] = hi;
        System.arraycopy(rest, 0, result, 2, rest.length);
        return result;
    }
}
