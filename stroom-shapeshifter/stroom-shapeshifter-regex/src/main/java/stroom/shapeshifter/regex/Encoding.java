package stroom.shapeshifter.regex;

/**
 * The input encoding a pattern compiles against — the {@code E} of the language spec's §4
 * (design/01-regex-language.md): a pattern is authored as code points, and compilation lowers
 * every literal and class into the byte sequences {@code E} uses for them, so that matching
 * is bytes with no decode step.
 *
 * <p>The spec names three compiled-in shapes: UTF-8, a 256-entry single-byte table, and RAW
 * identity. This type grows one shape per phase of the encoding plan (design/19); phase 1
 * declares only {@link #UTF_8}, which is what every pattern compiled against before the
 * parameter existed. The sealed hierarchy is the enumeration of what the compiler can lower —
 * an encoding this library cannot compile is a type that does not exist here, which is the
 * same refusal-by-name the engine's charset resolution practises (E22), enforced by the
 * type system instead of a check.
 *
 * <p>This module is dependency-free, so this type is its own: callers with a richer encoding
 * vocabulary (the engine's {@code text.Encoding}) map onto it and keep the transcode-or-refuse
 * decision on their side of the seam.
 */
public sealed interface Encoding {

    /** UTF-8 — the default. */
    Encoding UTF_8 = new Utf8();

    /** The UTF-8 lowering: {@code Utf8.sequences} for classes, multi-byte literals as bytes. */
    record Utf8() implements Encoding {

        @Override
        public String toString() {
            return "UTF_8";
        }
    }

    /**
     * A single-byte encoding: each byte is one character, given by a 256-entry table — the
     * shape 01 §4.0 calls "compiled into the pattern", covering Latin-1, the Windows-125x
     * family, ISO-8859-x, KOI8-R and the rest of the engine's single-byte vocabulary. Byte
     * offsets are character offsets, so every span is directly a span of the source.
     *
     * <p>Three rules the factory enforces rather than documents. The low half is identity —
     * every encoding this shape is for is ASCII-compatible, and the engines' ASCII fast
     * paths, line-anchor bytes and delimiter handling assume it. A high entry is either
     * {@code -1} (the byte encodes nothing; under D38 it is matchable by nothing) or a
     * non-ASCII, non-surrogate code point. And the mapping is injective, because encoding
     * must be a function: one code point, one byte.
     *
     * @param name           the encoding's name, for messages and identity
     * @param byteToCodePoint 256 entries, byte value to code point, {@code -1} for unmapped
     */
    record Table(String name, int[] byteToCodePoint) implements Encoding {

        public Table {
            java.util.Objects.requireNonNull(name, "name");
            if (byteToCodePoint == null || byteToCodePoint.length != 256) {
                throw new IllegalArgumentException("a table encoding needs exactly 256 entries");
            }
            byteToCodePoint = byteToCodePoint.clone();
            final boolean[] seen = new boolean[0x110000];
            for (int b = 0; b < 256; b++) {
                final int codePoint = byteToCodePoint[b];
                if (b < 0x80) {
                    if (codePoint != b) {
                        throw new IllegalArgumentException(name + ": the low half must be "
                                + "identity, but byte 0x" + Integer.toHexString(b)
                                + " maps to 0x" + Integer.toHexString(codePoint));
                    }
                    seen[b] = true;
                    continue;
                }
                if (codePoint == -1) {
                    continue;
                }
                if (codePoint < 0x80 || codePoint > 0x10FFFF
                        || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                    throw new IllegalArgumentException(name + ": byte 0x"
                            + Integer.toHexString(b) + " maps to an impossible code point 0x"
                            + Integer.toHexString(codePoint));
                }
                if (seen[codePoint]) {
                    throw new IllegalArgumentException(name + ": two bytes map to 0x"
                            + Integer.toHexString(codePoint) + ", so encoding is not a function");
                }
                seen[codePoint] = true;
            }
        }

        @Override
        public int[] byteToCodePoint() {
            return byteToCodePoint.clone();
        }

        /** The code point byte {@code b} encodes, or -1: the uncloned read for hot callers. */
        public int codePointOf(final int b) {
            return byteToCodePoint[b];
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Table table
                   && name.equals(table.name)
                   && java.util.Arrays.equals(byteToCodePoint, table.byteToCodePoint);
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + java.util.Arrays.hashCode(byteToCodePoint);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
