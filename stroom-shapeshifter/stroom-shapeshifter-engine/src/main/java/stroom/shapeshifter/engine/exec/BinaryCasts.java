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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.config.BinaryCast;
import stroom.shapeshifter.engine.value.TypedValue;

/**
 * A labelled run of bytes as the value its cast names (design 38 §3): the reading the old
 * {@code ReadNumeric}, {@code ReadVarint} and {@code Tell} steps did inside the matcher, done
 * once when a match binds its groups — or, for a {@code read} part (design 39, D56), at the
 * cursor as the sequence runs, with the width the cast's own and no pattern in between. The
 * bytes are read where they lie — a slice's range over its array — and nothing is copied. A
 * run of the wrong width for a fixed-width cast, or a varint that never terminates, is absent
 * rather than a wrong number when a pattern bound it, and fails the match when a read did.
 */
final class BinaryCasts {

    private BinaryCasts() {
    }

    /**
     * @param value    the group's bytes, as the match bound them
     * @param position the group's offset from the match's start, for {@link BinaryCast#POSITION}
     */
    static TypedValue apply(final BinaryCast cast, final TypedValue value, final int position) {
        if (cast == BinaryCast.POSITION) {
            return new TypedValue.Integer(position);
        }
        // The bytes as matched, never a decoded form: a raw slice's UTF-8 range is its bytes
        // decoded as code points, and a byte above 0x7F would read as two.
        final byte[] a;
        final int from;
        final int n;
        if (value instanceof final TypedValue.ByteSlice slice) {
            a = slice.array();
            from = slice.from();
            n = slice.to() - slice.from();
        } else if (value instanceof final TypedValue.Bytes bytes) {
            a = bytes.value();
            from = 0;
            n = a.length;
        } else {
            return null;
        }
        return apply(cast, a, from, n);
    }

    /**
     * A read at the cursor (design 39, D56): the cast's width measured from the bytes where
     * they lie — a varint's by its high bits, the rest fixed — and its value made from them
     * into {@code into[group]}. Returns the cursor after it, or −1 when the bytes do not reach:
     * a truncated record, which fails the match as a short take does.
     */
    static int read(final BinaryCast cast, final byte[] a, final int cursor, final int from, final int to,
                    final TypedValue[] into, final int group) {
        final int width;
        if (cast == BinaryCast.POSITION) {
            into[group] = new TypedValue.Integer(cursor - from);
            return cursor;
        }
        if (cast == BinaryCast.VARINT || cast == BinaryCast.ZIGZAG) {
            // Up to ten bytes, the last without its high bit; a run that never ends, or ends
            // past the region, is a truncated record and fails the match.
            int i = cursor;
            while (i < to && i - cursor < 10 && (a[i] & 0x80) != 0) {
                i++;
            }
            if (i >= to || i - cursor >= 10) {
                return -1;
            }
            width = i - cursor + 1;
        } else {
            width = cast.width();
            if (cursor + width > to) {
                return -1;
            }
        }
        into[group] = apply(cast, a, cursor, width);
        return cursor + width;
    }

    private static TypedValue apply(final BinaryCast cast, final byte[] a, final int from, final int n) {
        // The casts the real formats read — a varint in both signs, a byte, a flag, a double —
        // in a dispatcher under the JIT's hot-method size, so a cast inlines where a match
        // binds it; the fixed widths are behind one call (design 38 §8, the census of the
        // parts path).
        return switch (cast) {
            case ZIGZAG -> varint(a, from, n, true);
            case VARINT -> varint(a, from, n, false);
            case UINT8 -> n == 1 ? new TypedValue.Integer(a[from] & 0xFF) : null;
            case BOOL8 -> n == 1 ? new TypedValue.Bool(a[from] != 0) : null;
            case FLOAT64LE -> n == 8 ? new TypedValue.Double(Double.longBitsToDouble(le(a, from, 8))) : null;
            default -> applyRare(cast, a, from, n);
        };
    }

    /** The fixed-width casts no corpus format is hot on. */
    private static TypedValue applyRare(final BinaryCast cast, final byte[] a, final int from, final int n) {
        return switch (cast) {
            case INT8 -> n == 1 ? new TypedValue.Integer(a[from]) : null;
            case UINT16LE -> n == 2 ? new TypedValue.Integer(le(a, from, 2)) : null;
            case UINT16BE -> n == 2 ? new TypedValue.Integer(be(a, from, 2)) : null;
            case INT16LE -> n == 2 ? new TypedValue.Integer((short) le(a, from, 2)) : null;
            case INT16BE -> n == 2 ? new TypedValue.Integer((short) be(a, from, 2)) : null;
            case UINT32LE -> n == 4 ? new TypedValue.Integer(le(a, from, 4)) : null;
            case UINT32BE -> n == 4 ? new TypedValue.Integer(be(a, from, 4)) : null;
            case INT32LE -> n == 4 ? new TypedValue.Integer((int) le(a, from, 4)) : null;
            case INT32BE -> n == 4 ? new TypedValue.Integer((int) be(a, from, 4)) : null;
            case INT64LE -> n == 8 ? new TypedValue.Integer(le(a, from, 8)) : null;
            case INT64BE -> n == 8 ? new TypedValue.Integer(be(a, from, 8)) : null;
            case FLOAT32LE -> n == 4 ? new TypedValue.Double(Float.intBitsToFloat((int) le(a, from, 4))) : null;
            case FLOAT32BE -> n == 4 ? new TypedValue.Double(Float.intBitsToFloat((int) be(a, from, 4))) : null;
            case FLOAT64BE -> n == 8 ? new TypedValue.Double(Double.longBitsToDouble(be(a, from, 8))) : null;
            case ZIGZAG, VARINT, UINT8, BOOL8, FLOAT64LE, POSITION -> throw new IllegalStateException("hot arm");
        };
    }

    private static long le(final byte[] a, final int from, final int width) {
        long v = 0;
        for (int i = width - 1; i >= 0; i--) {
            v = (v << 8) | (a[from + i] & 0xFFL);
        }
        return v;
    }

    private static long be(final byte[] a, final int from, final int width) {
        long v = 0;
        for (int i = 0; i < width; i++) {
            v = (v << 8) | (a[from + i] & 0xFFL);
        }
        return v;
    }

    /** LEB128: seven bits a byte, low group first, the high bit saying another follows; ten bytes at most. */
    private static TypedValue varint(final byte[] a, final int from, final int n, final boolean zigzag) {
        if (n == 0 || n > 10) {
            return null;
        }
        long v = 0;
        int shift = 0;
        for (int i = 0; i < n; i++) {
            final int b = a[from + i] & 0xFF;
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (i != n - 1) {
                    return null; // terminated before the run ended: not one varint
                }
                return new TypedValue.Integer(zigzag ? (v >>> 1) ^ -(v & 1) : v);
            }
            shift += 7;
        }
        return null; // never terminated
    }
}
