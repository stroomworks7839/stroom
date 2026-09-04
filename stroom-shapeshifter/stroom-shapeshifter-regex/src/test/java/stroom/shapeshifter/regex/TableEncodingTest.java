package stroom.shapeshifter.regex;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 of the encoding plan (design/19): the {@link Encoding.Table} shape, compiled and
 * matched. The table here is built from the JDK's own windows-1252 decoder, so the oracle
 * relationship is exact: decode the bytes with the same charset, run {@code java.util.regex}
 * on the resulting text, and every offset maps one-to-one — a single-byte encoding's spans
 * are character spans.
 */
class TableEncodingTest {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final Encoding TABLE = table("windows-1252", WINDOWS_1252);

    /** The JDK decoder as a 256-entry table; undecodable and roundtrip-unfaithful bytes -1. */
    private static Encoding table(final String name, final Charset charset) {
        final int[] map = new int[256];
        for (int b = 0; b < 256; b++) {
            final String decoded = new String(new byte[]{(byte) b}, charset);
            final int codePoint = decoded.codePointAt(0);
            final byte[] back = decoded.getBytes(charset);
            map[b] = decoded.codePointCount(0, decoded.length()) == 1
                     && codePoint != 0xFFFD
                     && back.length == 1 && (back[0] & 0xFF) == b
                    ? codePoint
                    : -1;
        }
        return new Encoding.Table(name, map);
    }

    private static ByteMatcher matcher(final String pattern, final Flag... flags) {
        final Set<Flag> set = flags.length == 0
                ? EnumSet.noneOf(Flag.class)
                : EnumSet.of(flags[0], flags);
        return BytePattern.compile(pattern, set, TABLE).matcher();
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(WINDOWS_1252);
    }

    @Test
    void literalLowersToItsTableByte() {
        final ByteMatcher m = matcher("é");
        assertThat(m.find(new byte[]{'a', (byte) 0xE9, 'b'})).isTrue();
        assertThat(m.start(0)).isEqualTo(1);
        assertThat(m.end(0)).isEqualTo(2); // one byte, one character
        // and the UTF-8 spelling of the same character is not matched
        assertThat(matcher("é").find("aéb".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void matchMayStartOnAByteUtf8WouldCallAContinuation() {
        // 0x93 is a left curly quote in windows-1252 and a continuation byte in UTF-8. The
        // phase-4 audit found ByteMatcher's start gate still asking UTF-8's question, which
        // silently unseeded every match starting in 0x80–0xBF under a single-byte form.
        final ByteMatcher m = matcher("(“[a-z]+”)"); // U+201C and U+201D, the curly double quotes
        final byte[] data = {(byte) 0x93, 'h', 'i', (byte) 0x94};
        assertThat(m.find(data)).isTrue();
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.end(1)).isEqualTo(4);
    }

    @Test
    void classesLowerToByteRanges() {
        final ByteMatcher m = matcher("([À-ÿ]+)");
        final byte[] data = bytes("xÀÁé!");
        assertThat(m.find(data)).isTrue();
        assertThat(m.start(1)).isEqualTo(1);
        assertThat(m.end(1)).isEqualTo(4);
    }

    @Test
    void anUnmappedByteIsMatchableByNothing() {
        // 0x81 encodes nothing in windows-1252. Strictness (D38): no character construct
        // can consume it — not dot-all, not a negated class.
        final byte[] data = {'a', (byte) 0x81, 'b'};
        final ByteMatcher dotAll = matcher("(?s)(.+)");
        assertThat(dotAll.find(data)).isTrue();
        assertThat(dotAll.end(0)).isEqualTo(1); // the run stops where the encoding does
        final ByteMatcher negated = matcher("([^,]+)");
        assertThat(negated.find(data)).isTrue();
        assertThat(negated.end(0)).isEqualTo(1);
    }

    @Test
    void wordCharactersAndBoundariesFollowTheTable() {
        // é is a word character; 0xE9 is its whole encoding, so \w and \b see it in one byte.
        final ByteMatcher word = matcher("(\\w+)");
        final byte[] data = {' ', (byte) 0xE9, 'x', ' '};
        assertThat(word.find(data)).isTrue();
        assertThat(word.start(1)).isEqualTo(1);
        assertThat(word.end(1)).isEqualTo(3);
        final ByteMatcher bounded = matcher("\\bé\\w*\\b");
        assertThat(bounded.find(data)).isTrue();
    }

    @Test
    void caseFoldingStaysAtCodePointLevelAndLowersPerTable() {
        final ByteMatcher m = matcher("é", Flag.CASE_INSENSITIVE);
        assertThat(m.find(new byte[]{(byte) 0xC9})).isTrue(); // É is 0xC9 in windows-1252
    }

    @Test
    void theUnencodableIsRefusedByName() {
        assertThatThrownBy(() -> BytePattern.compile("中", EnumSet.noneOf(Flag.class), TABLE))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("no encoding under windows-1252");
        assertThatThrownBy(() -> BytePattern.compile("[中]", EnumSet.noneOf(Flag.class), TABLE))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("can never match");
    }

    @Test
    void patternIdentityCarriesTheTable() {
        assertThat(BytePattern.compile("a", EnumSet.noneOf(Flag.class), TABLE).encoding())
                .isEqualTo(TABLE);
    }

    /**
     * The differential oracle a single-byte encoding makes exact: decode with the same
     * charset, run the JDK on the text, and offsets map one-to-one. Patterns span the tiers
     * — one-pass, ambiguous, fancy — and the inputs are random mapped bytes with the
     * accented rows deliberately frequent.
     */
    @Test
    void agreesWithTheJdkThroughTheCharset() {
        final List<String> patterns = List.of(
                "^([^,]+),([^,]+)$", "([À-ÿ]+)", "(\\w+)é", "(.*?)é", "(é+)\\1",
                "\\b[a-zé]+\\b");
        final Random random = new Random(7);
        int compared = 0;
        for (int round = 0; round < 200; round++) {
            final byte[] data = new byte[3 + random.nextInt(24)];
            for (int i = 0; i < data.length; i++) {
                if (random.nextBoolean()) {
                    data[i] = (byte) ('a' + random.nextInt(26));
                } else {
                    data[i] = switch (random.nextInt(5)) {
                        case 0 -> (byte) 0xE9;
                        case 1 -> (byte) 0xC9;
                        case 2 -> (byte) (0xC0 + random.nextInt(0x20));
                        // 0x91–0x94: curly quotes, squarely in UTF-8's continuation range —
                        // the bytes whose match-starts the phase-4 audit found the public
                        // matcher's UTF-8 gate silently unseeding.
                        case 3 -> (byte) (0x91 + random.nextInt(4));
                        default -> ',';
                    };
                }
            }
            final String text = new String(data, WINDOWS_1252);
            for (final String pattern : patterns) {
                final ByteMatcher ours = BytePattern
                        .compile(pattern, EnumSet.noneOf(Flag.class), TABLE).matcher();
                final Matcher jdk = JdkOracle.compile(pattern).matcher(text);
                final boolean found = ours.find(data);
                assertThat(found).as(pattern + " over " + text).isEqualTo(jdk.find());
                if (found) {
                    assertThat(ours.start(0)).as(pattern + " over " + text)
                            .isEqualTo(jdk.start());
                    assertThat(ours.end(0)).as(pattern + " over " + text).isEqualTo(jdk.end());
                }
                compared++;
            }
        }
        assertThat(compared).isEqualTo(200 * patterns.size());
    }
}
