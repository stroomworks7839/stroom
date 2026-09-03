package stroom.shapeshifter.regex;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line idiom {@code ((?:[^\n]*\n)*?)lit} compiles to the tree's iterative {@code RunLoop}
 * (design 07 Phase 2) instead of the recursive {@code Loop}. These pin that the two agree with
 * each other and with the JDK — spans and captures — on the shapes that distinguish a lazy unit
 * loop from anything else, and that a 64 KiB region no longer trips the depth guard.
 */
class RunLoopTest {

    private static final String IDIOM = "  <batch id=\"([^\"]*)\">\\n((?:[^\\n]*\\n)*?)  </batch>\\n";

    private static byte[] batch(final int lines) {
        final StringBuilder sb = new StringBuilder("  <batch id=\"b-1\">\n");
        for (int i = 0; i < lines; i++) {
            sb.append("    <entry seq=\"").append(i).append("\">payload ").append(i).append("</entry>\n");
        }
        return sb.append("  </batch>\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void agreeWithJdk(final String pattern, final byte[] input) {
        final String text = new String(input, StandardCharsets.UTF_8);
        final Matcher jdk = Pattern.compile(pattern).matcher(text);
        for (final Engine engine : new Engine[]{Engine.TREE, Engine.SIMULATE}) {
            final ByteMatcher ours = BytePattern
                    .compileForcing(engine, pattern, EnumSet.noneOf(Flag.class)).matcher();
            final boolean found = ours.find(input);
            assertThat(found).as("%s finds like the JDK: %s", engine, pattern).isEqualTo(jdk.find());
            if (found) {
                for (int g = 0; g <= jdk.groupCount(); g++) {
                    // The JDK reports char offsets over the String; ours are byte offsets.
                    assertThat(ours.start(g)).as("%s group %d start", engine, g).isEqualTo(byteOffset(text, jdk.start(g)));
                    assertThat(ours.end(g)).as("%s group %d end", engine, g).isEqualTo(byteOffset(text, jdk.end(g)));
                }
            }
            jdk.reset();
        }
    }

    private static int byteOffset(final String text, final int charOffset) {
        return charOffset < 0 ? -1 : text.substring(0, charOffset).getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void theIdiomAgreesWithTheJdkAtEntrySize() {
        agreeWithJdk(IDIOM, batch(2));
    }

    @Test
    void sixtyFourKilobytesNoLongerTripsTheDepthGuard() {
        final byte[] input = batch(1_700); // ~1,700 lines: well past LOOP_DEPTH_LIMIT's 1,024
        assertThat(input.length).isGreaterThan(64 * 1024);
        final BytePattern tree = BytePattern.compileForcing(Engine.TREE, IDIOM, EnumSet.noneOf(Flag.class));
        final ByteMatcher m = tree.matcher();
        assertThat(m.find(input)).as("the tree finishes the region itself").isTrue();
        assertThat(m.group(2).length()).isEqualTo(input.length - "  <batch id=\"b-1\">\n".length() - "  </batch>\n".length());
        // The oracle here is the simulation, not the JDK: java.util.regex recurses once per
        // iteration of a lazy loop too, and overflows a default stack on this input
        // (Pattern$LazyLoop, 1,024 frames deep, 2026-09-03). The tree used to fail the same way
        // one level up, by its own depth guard; now neither engine of ours does.
        final ByteMatcher sim = BytePattern.compileForcing(Engine.SIMULATE, IDIOM, EnumSet.noneOf(Flag.class)).matcher();
        assertThat(sim.find(input)).isTrue();
        for (int g = 0; g <= 2; g++) {
            assertThat(m.start(g)).as("group %d start", g).isEqualTo(sim.start(g));
            assertThat(m.end(g)).as("group %d end", g).isEqualTo(sim.end(g));
        }
    }

    @Test
    void lazinessIsKept() {
        // Two terminators are present; the lazy loop must stop at the first the continuation accepts.
        agreeWithJdk("((?:[^\\n]*\\n)*?)END\\n", "a\nb\nEND\nc\nEND\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aMissingTerminatorFailsTheUnitNotTheMatch() {
        // The last line has no newline: the loop cannot consume it as a unit, so the continuation
        // must match before it or the whole thing fails — exactly as the recursive loop behaves.
        agreeWithJdk("((?:[^\\n]*\\n)*?)tail", "one\ntwo\ntail".getBytes(StandardCharsets.UTF_8));
        agreeWithJdk("((?:[^\\n]*\\n)*?)tail", "one\ntwo\nthree".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aUnitWhoseClassAcceptsTheTerminatorIsNotThisShape() {
        // .*\n: the run would swallow the newline, so the boundary is ambiguous and the general
        // loop must handle it. Agreement, not the node, is what is pinned here.
        agreeWithJdk("((?:.*\\n)*?)END", "a\nb\nEND".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void nonAsciiInsideTheRunIsWalkedNotStepped() {
        agreeWithJdk("((?:[^\\n]*\\n)*?)fin\\n", "café\nnaïve — ok\nfin\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void emptyLinesAreUnits() {
        agreeWithJdk("((?:[^\\n]*\\n)*?)x", "\n\n\nx".getBytes(StandardCharsets.UTF_8));
    }
}
