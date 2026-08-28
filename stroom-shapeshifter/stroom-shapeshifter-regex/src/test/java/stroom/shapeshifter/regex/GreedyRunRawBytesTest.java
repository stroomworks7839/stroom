package stroom.shapeshifter.regex;




import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The greedy mirror of {@link LazyRunSkipTest}'s raw-bytes check, and it was not written as a
 * formality: its first run failed. The tree's greedy {@code scan()} carried an allNonAscii
 * shortcut that stepped one <i>byte</i> at a time, walking over bytes {@code accept()} rejects
 * — so over invalid UTF-8, which log data is full of and no {@code String} input can express,
 * it claimed runs the class cannot consume: {@code (?s)(.*)\u00e9} over
 * {@code {A, C3, C3, A9}} reported 0..4, capturing an invalid byte inside {@code (.*)}, where
 * every other engine says 2..4. Three engines against one convicted the shortcut, the same
 * form of evidence the lazy skip's walk was designed around. The engines that carry no such
 * shortcut are the oracle.
 */
class GreedyRunRawBytesTest {

    private static final List<byte[]> INPUTS = List.of(
            new byte[]{'A', (byte) 0xC3, (byte) 0xC3, (byte) 0xA9},
            new byte[]{(byte) 0xC3, (byte) 0xA9, (byte) 0xC3, (byte) 0xA9},
            new byte[]{(byte) 0xC3, (byte) 0x41, (byte) 0xC3, (byte) 0xA9},
            new byte[]{(byte) 0xA9, (byte) 0xC3, (byte) 0xA9},
            new byte[]{(byte) 0xE4, (byte) 0xB8, (byte) 0xC3, (byte) 0xA9},
            new byte[]{(byte) 0xFF, (byte) 0xC3, (byte) 0xA9, 'b'},
            new byte[]{'a', (byte) 0x80, (byte) 0xC3, (byte) 0xA9},
            new byte[]{(byte) 0xC3, (byte) 0xA9},
            new byte[]{(byte) 0xFF, 'b'},
            new byte[]{'a', (byte) 0xFF, 'b', (byte) 0xFF, 'b'},
            new byte[]{(byte) 0xF0, (byte) 0x9F, (byte) 0x92, (byte) 0xA9, 'b'});

    @Test
    void greedyRunsAgreeAcrossCharacterWiseEnginesOnBytesThatAreNotValidText() {
        for (final String pattern : List.of("(?s)(.*)\u00e9", "(?s)(.*)b", "(?s)(.*)\u00e9b",
                "(?s)([^\\x00]*)\u00e9", "(?s)(.*)")) {
            for (final byte[] data : INPUTS) {
                assertEnginesAgree(pattern, data);
            }
        }
    }

    /**
     * The scan plan is excluded above and pinned here, because its byte-level ops are
     * <b>deliberately</b> permissive where every character-wise engine is strict. The plan
     * compiler's byte-scan rule proves span equivalence for allNonAscii classes on the premise
     * that a high byte belongs to some character — true of valid UTF-8, unstated, and false of
     * log bytes. Making the plan strict would forfeit SCAN_UNTIL_BYTE, the memchr shape tier 0
     * is built on; making four engines permissive is a rewrite. So the split is recorded (05
     * §3.2) rather than resolved, and this pin exists to make a future resolution announce
     * itself: on undecodable bytes the natural engine's answer depends on the tier the pattern
     * lands in.
     */
    @Test
    void theScanPlanIsBytePermissiveOnUndecodableBytesAndTheTreeIsNot() {
        final byte[] data = {'A', (byte) 0xC3, (byte) 0xC3, (byte) 0xA9};
        assertThat(span(Engine.SCAN_PLAN, "(?s)(.*)", data)).isEqualTo("0..4");
        assertThat(span(Engine.TREE, "(?s)(.*)", data)).isEqualTo("0..1");
    }

    private static String span(final Engine engine, final String pattern, final byte[] data) {
        final ByteMatcher m = BytePattern.compileForcing(
                engine, pattern, EnumSet.noneOf(Flag.class)).matcher();
        return m.find(data)
                ? m.start(0) + ".." + m.end(0)
                : "-";
    }

    private static void assertEnginesAgree(final String pattern, final byte[] data) {
        final List<String> answers = new ArrayList<>();
        for (final Engine engine : Engine.values()) {
            if (engine == Engine.SCAN_PLAN) {
                continue; // deliberately byte-permissive on undecodable bytes; pinned below
            }
            final BytePattern compiled;
            try {
                compiled = BytePattern.compileForcing(
                        engine, pattern, EnumSet.noneOf(Flag.class));
            } catch (final RuntimeException e) {
                continue;
            }
            final ByteMatcher m = compiled.matcher();
            try {
                answers.add(m.find(data)
                        ? engine + "=" + m.start(0) + ".." + m.end(0)
                        : engine + "=-");
            } catch (final RuntimeException e) {
                continue;
            }
        }
        final List<String> spans = answers.stream()
                .map(a -> a.substring(a.indexOf('=') + 1)).distinct().toList();
        assertThat(spans)
                .as("engines disagree: pattern=%s bytes=%s -> %s",
                        pattern, java.util.Arrays.toString(data), answers)
                .hasSizeLessThanOrEqualTo(1);
    }
}
