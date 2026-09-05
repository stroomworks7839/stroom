package stroom.shapeshifter.engine.exec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window's tail belongs to nobody once the read comes up short, and the matcher will read
 * one byte of it: its contract is that the array holds the caller's data up to its length, and
 * it probes the byte after the region to decide whether the region ends mid-character.
 *
 * <p>Left stale, that byte is the previous buffer's. A continuation byte there says a character
 * continues past the region, and a legal empty match at the tail is refused — silently, and
 * according to what an earlier buffer happened to hold. Demonstrated at the library entry on
 * 2026-08-27; this pins the executor's side of it.
 */
class WindowTailTest {

    @Test
    void shortReadLeavesNoneOfThePreviousBufferBehind() {
        final byte[] window = new byte[8];
        Arrays.fill(window, (byte) 0x82);   // the last buffer: UTF-8 continuation bytes throughout

        final int got = InputWindow.fillAndBlankTail(
                new ByteArrayInputStream("ab".getBytes(StandardCharsets.UTF_8)), window, 0);

        assertThat(got).isEqualTo(2);
        assertThat(Arrays.copyOfRange(window, got, window.length))
                .as("everything past the fill is blank, so the matcher's probe reads the "
                    + "caller's data or a zero, never the last buffer's tail")
                .containsOnly((byte) 0);
    }

    @Test
    void refillPartwayThroughTheWindowBlanksFromItsOwnEnd() {
        final byte[] window = new byte[8];
        Arrays.fill(window, (byte) 0x82);
        window[0] = 'x';                    // a compacted remainder already at the front

        final int got = InputWindow.fillAndBlankTail(
                new ByteArrayInputStream("yz".getBytes(StandardCharsets.UTF_8)), window, 1);

        assertThat(got).isEqualTo(2);
        assertThat(Arrays.copyOfRange(window, 0, 3)).containsExactly((byte) 'x', (byte) 'y', (byte) 'z');
        assertThat(Arrays.copyOfRange(window, 3, window.length)).containsOnly((byte) 0);
    }
}
