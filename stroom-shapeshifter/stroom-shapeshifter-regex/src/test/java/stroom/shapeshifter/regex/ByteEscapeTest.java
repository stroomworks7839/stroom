package stroom.shapeshifter.regex;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 of the encoding plan (design/19): {@code \B{HH}} byte escapes — 01 §4.4's raw
 * byte, matched as itself and never re-encoded, under any encoding. The brace is the whole
 * of the disambiguation: bare {@code \B} stays the non-word-boundary it has always been,
 * because {@code \Bad} must not silently become byte 0xAD.
 */
class ByteEscapeTest {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private static ByteMatcher utf8(final String pattern) {
        return BytePattern.compile(pattern).matcher();
    }

    @Test
    void byteEscapeMatchesTheByteAndIsNeverReEncoded() {
        // Under UTF-8, é's encoding is C3 A9 — but \B{E9} is the byte E9, dirt included.
        final ByteMatcher m = utf8("a\\B{E9}b");
        assertThat(m.find(new byte[]{'a', (byte) 0xE9, 'b'})).isTrue();
        assertThat(utf8("a\\B{E9}b").find("aéb".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void bareBoundaryAndItsNeighboursAreUntouched() {
        // \Bad is a boundary then the literal "ad" — the ambiguity the brace rule exists for.
        final ByteMatcher m = utf8("\\Bad");
        final byte[] data = "road".getBytes(StandardCharsets.UTF_8);
        assertThat(m.find(data)).isTrue();
        assertThat(m.start(0)).isEqualTo(2);
    }

    @Test
    void theEscapeIsExactlyTwoHexDigits() {
        assertThatThrownBy(() -> BytePattern.compile("\\B{F}"))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("exactly two hexadecimal digits");
        assertThatThrownBy(() -> BytePattern.compile("\\B{123}"))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("exactly two hexadecimal digits");
        // The traded corner, deliberate and documented: a brace after \B is always the byte
        // escape, so a quantified assertion is spelt (?:\B){2}.
        assertThat(utf8("\\B{12}").find(new byte[]{0x12})).isTrue();
    }

    @Test
    void highByteUnderUtf8CarriesTheStraddleWarning() {
        final BytePattern pattern = BytePattern.compile("a\\B{93}b");
        assertThat(pattern.warnings()).anyMatch(w -> w.contains("raw byte")
                && w.contains("continuation"));
        assertThat(BytePattern.compile("a\\B{12}b").warnings()).isEmpty();
    }

    @Test
    void singleByteFormEscapeCoincidesWithTheCharacter() {
        final int[] map = new int[256];
        for (int b = 0; b < 256; b++) {
            final String decoded = new String(new byte[]{(byte) b}, WINDOWS_1252);
            final byte[] back = decoded.getBytes(WINDOWS_1252);
            map[b] = decoded.codePointCount(0, decoded.length()) == 1
                     && decoded.codePointAt(0) != 0xFFFD
                     && back.length == 1 && (back[0] & 0xFF) == b
                    ? decoded.codePointAt(0)
                    : -1;
        }
        final Encoding table = new Encoding.Table("windows-1252", map);
        // \B{93} and the curly quote are the same byte machine under the table — and no
        // warning, because one byte is one character.
        final BytePattern escape = BytePattern.compile("\\B{93}hi",
                EnumSet.noneOf(Flag.class), table);
        assertThat(escape.warnings()).isEmpty();
        final byte[] data = {(byte) 0x93, 'h', 'i'};
        assertThat(escape.matcher().find(data)).isTrue();
        assertThat(BytePattern.compile("“hi", EnumSet.noneOf(Flag.class), table)
                .matcher().find(data)).isTrue();
        // In a class, the byte means its character.
        assertThat(BytePattern.compile("[\\B{93}\\B{94}]hi", EnumSet.noneOf(Flag.class), table)
                .matcher().find(data)).isTrue();
    }

    @Test
    void underRawTheEscapeCoincidesWithHex() {
        // 01 §4.4's sentence, executable: \B{FF} and \xFF coincide under RAW.
        final byte[] data = {(byte) 0xFF};
        assertThat(BytePattern.compile("\\B{FF}", EnumSet.noneOf(Flag.class), Encoding.RAW)
                .matcher().find(data)).isTrue();
        assertThat(BytePattern.compile("\\xFF", EnumSet.noneOf(Flag.class), Encoding.RAW)
                .matcher().find(data)).isTrue();
        assertThat(BytePattern.compile("[\\B{FE}\\B{FF}]", EnumSet.noneOf(Flag.class),
                Encoding.RAW).matcher().find(data)).isTrue();
    }

    @Test
    void byteInAUtf8ClassIsRefusedWithDirections() {
        assertThatThrownBy(() -> BytePattern.compile("[\\B{FF}]"))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("whole character")
                .hasMessageContaining("alternation");
        // [\B] without a brace stays what it always was: the literal B.
        assertThat(utf8("[\\B]").find("B".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void matchStartLimitsAreTheWarningsNotSurprises() {
        // A pattern beginning with a continuation-range byte cannot seed a match under
        // UTF-8 — the search gate reads 0x93 as mid-character. That is what the straddle
        // warning warns about; mid-pattern the byte matches fine (the first test above).
        assertThat(utf8("\\B{93}x").find(new byte[]{(byte) 0x93, 'x'})).isFalse();
    }
}
