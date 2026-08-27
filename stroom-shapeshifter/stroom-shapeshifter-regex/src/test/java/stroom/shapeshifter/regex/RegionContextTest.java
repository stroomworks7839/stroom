package stroom.shapeshifter.regex;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract at the region's end: the array holds the caller's data up to its length, so the
 * byte after the region is the caller's too and decides whether the region ends mid-character.
 *
 * <p>Both halves are pinned because both have been got wrong. Reading the byte is R1's fix — a
 * slice ending mid-character must not admit a match inside that character. Reading it from a
 * buffer whose data stopped earlier is the caller's bug, and cost a silent refusal at the tail
 * of every short-filled window until {@code Executor.stream} learned to blank it (ISSUES.md,
 * 2026-08-27).
 */
class RegionContextTest {

    @Test
    void aCharacterContinuingPastTheRegionForbidsAMatchThere() {
        // "é" is two bytes; the region stops between them, so the region end is mid-character
        // and nothing — not even an empty match — may begin there.
        final byte[] data = "é".getBytes(StandardCharsets.UTF_8);
        assertThat(BytePattern.compile("b*").matcher().match(data, 1, 1, Anchoring.ANCHORED))
                .as("the region ends inside a character")
                .isFalse();
    }

    @Test
    void anArrayThatEndsWithTheDataAdmitsTheEmptyMatchAtItsEnd() {
        final byte[] data = "ab".getBytes(StandardCharsets.UTF_8);
        assertThat(BytePattern.compile("b*").matcher().match(data, 2, 2, Anchoring.ANCHORED))
                .as("nothing follows the data, so nothing continues past the region")
                .isTrue();
    }
}
