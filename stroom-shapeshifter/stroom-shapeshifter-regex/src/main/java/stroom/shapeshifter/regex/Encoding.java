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

    /** UTF-8 — the default, and until the table and RAW lowerings land, the only shape. */
    Encoding UTF_8 = new Utf8();

    /** The UTF-8 lowering: {@code Utf8.sequences} for classes, multi-byte literals as bytes. */
    record Utf8() implements Encoding {

        @Override
        public String toString() {
            return "UTF_8";
        }
    }
}
