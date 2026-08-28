package stroom.shapeshifter.regex;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 of the encoding plan (design/19): the {@link Encoding} parameter exists, only
 * {@link Encoding#UTF_8} exists to pass, and passing it changes nothing — the same spans from
 * the same machines. The value of the phase is the seam, so what these tests pin is that the
 * seam is inert: when the table and RAW shapes arrive, their tests assert differences, and
 * this one keeps asserting UTF-8's identity.
 */
class EncodingSeamTest {

    /** One pattern per tier: one-pass (plan), ambiguous (tree + simulation), fancy. */
    private static final List<String> PATTERNS = List.of(
            "^([^,]+),([^,]+)$",
            "(.*?)é",
            "(a+)\\1");

    @Test
    void explicitUtf8IsTheDefaultToTheByte() {
        final byte[] input = "café,naïve café é (a+)aa".getBytes(StandardCharsets.UTF_8);
        for (final String pattern : PATTERNS) {
            final ByteMatcher defaulted = BytePattern.compile(pattern).matcher();
            final ByteMatcher explicit = BytePattern
                    .compile(pattern, EnumSet.noneOf(Flag.class), Encoding.UTF_8)
                    .matcher();
            final boolean found = defaulted.find(input);
            assertThat(explicit.find(input)).as(pattern).isEqualTo(found);
            if (found) {
                assertThat(explicit.start(0)).as(pattern).isEqualTo(defaulted.start(0));
                assertThat(explicit.end(0)).as(pattern).isEqualTo(defaulted.end(0));
            }
        }
    }

    @Test
    void everyCompilePathReportsItsEncoding() {
        assertThat(BytePattern.compile("a").encoding()).isEqualTo(Encoding.UTF_8);
        assertThat(BytePattern.compile("a", Flag.CASE_INSENSITIVE).encoding())
                .isEqualTo(Encoding.UTF_8);
        assertThat(BytePattern
                .compile("a", EnumSet.noneOf(Flag.class), Encoding.UTF_8).encoding())
                .isEqualTo(Encoding.UTF_8);
        for (final Engine engine : Engine.values()) {
            assertThat(BytePattern
                    .compileForcing(engine, "abc", EnumSet.noneOf(Flag.class)).encoding())
                    .as(engine.toString())
                    .isEqualTo(Encoding.UTF_8);
        }
    }

    @Test
    void nullEncodingIsRefusedNotDefaulted() {
        assertThatThrownBy(() ->
                BytePattern.compile("a", EnumSet.noneOf(Flag.class), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("encoding");
    }
}
