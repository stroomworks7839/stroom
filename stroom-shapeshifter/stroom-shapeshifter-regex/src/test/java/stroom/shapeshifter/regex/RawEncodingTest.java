package stroom.shapeshifter.regex;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 of the encoding plan (design/19): {@code Encoding.RAW}, each byte its own code
 * point — the mode for binary formats (01 §4.4). Lowering-wise it is the identity table, so
 * what these tests pin beyond {@code TableEncodingTest}'s ground is the dialect semantics:
 * {@code u} forced off, {@code \p} refused, {@code \xHH} meaning the byte.
 */
class RawEncodingTest {

    private static ByteMatcher matcher(final String pattern, final Flag... flags) {
        final Set<Flag> set = flags.length == 0
                ? EnumSet.noneOf(Flag.class)
                : EnumSet.of(flags[0], flags);
        return BytePattern.compile(pattern, set, Encoding.RAW).matcher();
    }

    @Test
    void dotIsAnyByteExceptNewline() {
        final byte[] data = {(byte) 0xFF, 0x00, (byte) 0xC3, '\n', 'x'};
        final ByteMatcher m = matcher("(.+)");
        assertThat(m.find(data)).isTrue();
        assertThat(m.end(1)).isEqualTo(3); // every byte is a character; \n still ends it
        final ByteMatcher dotAll = matcher("(?s)(.+)");
        assertThat(dotAll.find(data)).isTrue();
        assertThat(dotAll.end(1)).isEqualTo(5);
    }

    @Test
    void hexEscapesMeanTheByte() {
        // \xFF is byte FF under RAW where UTF-8 would lower U+00FF to C3 BF.
        final ByteMatcher raw = matcher("\\xFF");
        assertThat(raw.find(new byte[]{(byte) 0xFF})).isTrue();
        assertThat(BytePattern.compile("\\xFF").matcher()
                .find(new byte[]{(byte) 0xFF})).isFalse();
        assertThat(BytePattern.compile("\\xFF").matcher()
                .find(new byte[]{(byte) 0xC3, (byte) 0xBF})).isTrue();
    }

    @Test
    void byteClassesAndSpansAreByteLevel() {
        final ByteMatcher m = matcher("([\\x80-\\xFF]+)");
        final byte[] data = {'a', (byte) 0x80, (byte) 0xFF, (byte) 0xE9, 'b'};
        assertThat(m.find(data)).isTrue();
        assertThat(m.start(1)).isEqualTo(1);
        assertThat(m.end(1)).isEqualTo(4);
    }

    @Test
    void theUnicodeVocabularyIsRefusedNotIgnored() {
        assertThatThrownBy(() -> BytePattern.compile("\\p{L}", EnumSet.noneOf(Flag.class),
                Encoding.RAW))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("RAW");
        assertThatThrownBy(() -> BytePattern.compile("a", EnumSet.of(Flag.UNICODE),
                Encoding.RAW))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("forced off");
        assertThatThrownBy(() -> BytePattern.compile("(?u)a", EnumSet.noneOf(Flag.class),
                Encoding.RAW))
                .isInstanceOf(PatternCompileException.class)
                .hasMessageContaining("forced off");
    }

    @Test
    void shorthandsAndFoldingKeepTheAsciiReading() {
        // \w is [a-zA-Z0-9_]: byte E9 earns nothing under RAW (E5's doctrine, one layer down).
        final ByteMatcher word = matcher("(\\w+)");
        final byte[] data = {'a', (byte) 0xE9, 'b'};
        assertThat(word.find(data)).isTrue();
        assertThat(word.end(1)).isEqualTo(1);
        // (?i) folds ASCII only: é does not match É.
        assertThat(matcher("é", Flag.CASE_INSENSITIVE).find(new byte[]{(byte) 0xC9})).isFalse();
        assertThat(matcher("a", Flag.CASE_INSENSITIVE).find(new byte[]{'A'})).isTrue();
    }

    /**
     * RAW's identity map coincides with ISO-8859-1 over every byte, so the JDK on the
     * Latin-1-decoded text is an exact oracle for patterns that stay off the shorthands
     * (whose Unicode-off meaning the JDK spells differently).
     */
    @Test
    void agreesWithTheJdkThroughLatin1() {
        final List<String> patterns = List.of(
                "([\\x80-\\xFF]+)", "(?s)(.*?)é", "x(.)y", "^([^,]+),");
        final Random random = new Random(11);
        for (int round = 0; round < 150; round++) {
            final byte[] data = new byte[2 + random.nextInt(20)];
            random.nextBytes(data);
            final String text = new String(data, StandardCharsets.ISO_8859_1);
            for (final String pattern : patterns) {
                final ByteMatcher ours = BytePattern
                        .compile(pattern, EnumSet.noneOf(Flag.class), Encoding.RAW).matcher();
                final Matcher jdk = Pattern.compile(pattern, Pattern.DOTALL).matcher(text);
                // DOTALL only mirrors the (?s) variants; compile plain for the rest.
                final Matcher oracle = pattern.startsWith("(?s)")
                        ? jdk
                        : Pattern.compile(pattern).matcher(text);
                final boolean found = ours.find(data);
                assertThat(found).as(pattern + " over " + round).isEqualTo(oracle.find());
                if (found) {
                    assertThat(ours.start(0)).isEqualTo(oracle.start());
                    assertThat(ours.end(0)).isEqualTo(oracle.end());
                }
            }
        }
    }
}
